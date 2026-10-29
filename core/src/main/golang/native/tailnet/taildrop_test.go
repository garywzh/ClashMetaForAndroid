package tailnet

import (
	"cfa/native/taildrop"
	"context"
	"errors"
	"github.com/metacubex/http"
	"github.com/metacubex/tailscale/tsnet"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/tailscale/client/tailscale/apitype"
	"github.com/metacubex/tailscale/tailcfg"
)

type fakeDropClient struct {
	push    func(context.Context, tailcfg.StableNodeID, int64, string, io.Reader) error
	get     func(context.Context, string) (io.ReadCloser, int64, error)
	deleted bool
}

func (f *fakeDropClient) PushFile(c context.Context, id tailcfg.StableNodeID, n int64, name string, r io.Reader) error {
	return f.push(c, id, n, name, r)
}
func (f *fakeDropClient) GetWaitingFile(c context.Context, name string) (io.ReadCloser, int64, error) {
	return f.get(c, name)
}
func (f *fakeDropClient) DeleteWaitingFile(context.Context, string) error {
	f.deleted = true
	return nil
}
func (f *fakeDropClient) WaitingFiles(context.Context) ([]apitype.WaitingFile, error) {
	return nil, nil
}
func (f *fakeDropClient) FileTargets(context.Context) ([]apitype.FileTarget, error) { return nil, nil }
func (f *fakeDropClient) IncomingFiles(context.Context) ([]dropIncoming, error)     { return nil, nil }
func (f *fakeDropClient) CancelIncoming(context.Context, string) error              { return nil }

func dropFile(t *testing.T, data string) *os.File {
	t.Helper()
	f, err := os.CreateTemp(t.TempDir(), "drop")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { f.Close() })
	if _, err = f.WriteString(data); err != nil {
		t.Fatal(err)
	}
	if _, err = f.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	return f
}
func awaitDrop(t *testing.T, s *taildropSession) DropStatus {
	t.Helper()
	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		s.close()
		t.Fatal("transfer did not stop")
	}
	return s.snapshot()
}
func TestTaildropSendStreamsUnknownLength(t *testing.T) {
	data := strings.Repeat("stream", 1<<17)
	client := &fakeDropClient{push: func(ctx context.Context, id tailcfg.StableNodeID, size int64, name string, r io.Reader) error {
		if id != "node-1" || size != -1 || name != "file.txt" {
			return errors.New("wrong metadata")
		}
		n, err := io.Copy(io.Discard, r)
		if n != int64(len(data)) {
			return io.ErrUnexpectedEOF
		}
		return err
	}}
	s := makeTaildropSession(client)
	defer s.close()
	f := dropFile(t, data)
	if err := s.start(dropRequest{Name: "file.txt", Target: "node-1", Size: -1}, f, false); err != nil {
		t.Fatal(err)
	}
	result := awaitDrop(t, s)
	if got := result.Transfers[0]; got.State != "done" || got.Done != int64(len(data)) {
		t.Fatalf("%+v", got)
	}
	if _, err := f.Read(make([]byte, 1)); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("descriptor leaked: %v", err)
	}
}
func TestTaildropSaveKeepsInbox(t *testing.T) {
	for _, size := range []int64{7, 100} {
		t.Run(time.Duration(size).String(), func(t *testing.T) {
			client := &fakeDropClient{get: func(context.Context, string) (io.ReadCloser, int64, error) {
				return io.NopCloser(strings.NewReader("payload")), size, nil
			}}
			s := makeTaildropSession(client)
			defer s.close()
			f := dropFile(t, "")
			path := f.Name()
			if err := s.start(dropRequest{Name: "file.txt"}, f, true); err != nil {
				t.Fatal(err)
			}
			result := awaitDrop(t, s)
			expected := "done"
			if size != 7 {
				expected = "failed"
			}
			if result.Transfers[0].State != expected {
				t.Fatalf("%+v", result)
			}
			if client.deleted {
				t.Fatal("save deleted inbox source")
			}
			if data, err := os.ReadFile(path); err != nil || string(data) != "payload" {
				t.Fatalf("%q %v", data, err)
			}
		})
	}
}
func TestTaildropCancelClosesBlockedPipe(t *testing.T) {
	started := make(chan struct{})
	client := &fakeDropClient{push: func(_ context.Context, _ tailcfg.StableNodeID, _ int64, _ string, r io.Reader) error {
		close(started)
		_, err := io.Copy(io.Discard, r)
		return err
	}}
	s := makeTaildropSession(client)
	defer s.close()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	if err := s.start(dropRequest{Name: "pipe", Target: "node"}, r, false); err != nil {
		t.Fatal(err)
	}
	<-started
	if err := s.cancelJob("1"); err != nil {
		t.Fatal(err)
	}
	result := awaitDrop(t, s)
	if result.Transfers[0].State != "cancelled" {
		t.Fatalf("%+v", result)
	}
}
func TestTaildropQueueBoundAndStop(t *testing.T) {
	client := &fakeDropClient{push: func(ctx context.Context, _ tailcfg.StableNodeID, _ int64, _ string, _ io.Reader) error {
		<-ctx.Done()
		return ctx.Err()
	}}
	s := makeTaildropSession(client)
	var files []*os.File
	for i := 0; i < 32; i++ {
		f := dropFile(t, "")
		files = append(files, f)
		if err := s.start(dropRequest{Name: "file", Target: "node"}, f, false); err != nil {
			t.Fatal(err)
		}
	}
	extra := dropFile(t, "")
	if err := s.start(dropRequest{Name: "extra", Target: "node"}, extra, false); err == nil {
		t.Fatal("unbounded queue")
	}
	s.close()
	result := awaitDrop(t, s)
	for _, job := range result.Transfers {
		if job.State != "cancelled" {
			t.Fatalf("%+v", job)
		}
	}
	for _, f := range files {
		if _, err := f.Stat(); !errors.Is(err, os.ErrClosed) {
			t.Fatalf("descriptor leaked: %v", err)
		}
	}
	if err := s.start(dropRequest{Name: "stale", Target: "node"}, extra, false); err == nil {
		t.Fatal("closed session accepted file")
	}
}
func TestTaildropFailureAndHistory(t *testing.T) {
	client := &fakeDropClient{push: func(context.Context, tailcfg.StableNodeID, int64, string, io.Reader) error {
		return errors.New("peer denied")
	}}
	s := makeTaildropSession(client)
	defer s.close()
	for i := 0; i < 110; i++ {
		if err := s.start(dropRequest{Name: "file", Target: "node"}, dropFile(t, ""), false); err != nil {
			t.Fatal(err)
		}
		result := awaitDrop(t, s)
		if got := result.Transfers[len(result.Transfers)-1]; got.State != "failed" || got.Error != "peer denied" {
			t.Fatalf("%+v", got)
		}
	}
	if len(s.snapshot().Transfers) != 100 {
		t.Fatal("unbounded history")
	}
}
func TestTaildropConcurrentCancelSnapshot(t *testing.T) {
	client := &fakeDropClient{push: func(ctx context.Context, _ tailcfg.StableNodeID, _ int64, _ string, _ io.Reader) error {
		<-ctx.Done()
		return ctx.Err()
	}}
	s := makeTaildropSession(client)
	defer s.close()
	if err := s.start(dropRequest{Name: "file", Target: "node"}, dropFile(t, ""), false); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				s.snapshot()
				s.cancelJob("1")
			}
		}()
	}
	wg.Wait()
	awaitDrop(t, s)
}

func TestTaildropLocalAPIsRegistered(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")
	server := &tsnet.Server{
		Dir:      t.TempDir(),
		Hostname: "taildrop-test",
		Logf:     t.Logf,
		UserLogf: t.Logf,
		SystemDialer: func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("test network disabled")
		},
		SystemPacketListener: func(_ context.Context, network, _ string) (net.PacketConn, error) {
			address := "127.0.0.1:0"
			if network == "udp6" {
				address = "[::1]:0"
			}
			conn, err := net.ListenPacket(network, address)
			if err != nil {
				return nil, err
			}
			return blockedDropPacket{conn}, nil
		},
	}
	if err := taildrop.EnableStagingRoot(server.Dir); err != nil {
		t.Fatal(err)
	}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	lc, err := server.LocalClient()
	if err != nil {
		t.Fatal(err)
	}
	client := nativeDropClient{lc}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if files, err := client.IncomingFiles(ctx); err != nil || len(files) != 0 {
		t.Fatalf("incoming endpoint: %+v %v", files, err)
	}
	for _, path := range []string{"file-targets", "files/", "file-put/node/file"} {
		req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/"+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		resp, err := lc.DoLocalRequest(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode == 404 {
			t.Fatalf("missing LocalAPI %s", path)
		}
	}
}

type blockedDropPacket struct{ net.PacketConn }

func (blockedDropPacket) WriteTo([]byte, net.Addr) (int, error) {
	return 0, errors.New("test network disabled")
}
