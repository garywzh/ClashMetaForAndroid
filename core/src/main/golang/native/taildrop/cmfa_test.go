package taildrop

import (
	"context"
	"errors"
	"github.com/metacubex/http"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/ipn/ipnext"
	"github.com/metacubex/tailscale/tsnet"
	"io"
	"net"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/http/httptest"
	"github.com/metacubex/tailscale/ipn/ipnlocal"
	"github.com/metacubex/tailscale/tailcfg"
	"github.com/metacubex/tailscale/tstime"
)

type testPeer struct {
	same, unsigned bool
	caps           tailcfg.PeerCapMap
}

func (h testPeer) Peer() tailcfg.NodeView {
	return (&tailcfg.Node{StableID: "sender", UnsignedPeerAPIOnly: h.unsigned}).View()
}
func (h testPeer) PeerCaps() tailcfg.PeerCapMap         { return h.caps }
func (h testPeer) CanDebug() bool                       { return false }
func (h testPeer) Self() tailcfg.NodeView               { return (&tailcfg.Node{}).View() }
func (h testPeer) LocalBackend() *ipnlocal.LocalBackend { return nil }
func (h testPeer) IsSelfUntagged() bool                 { return h.same }
func (h testPeer) RemoteAddr() netip.AddrPort           { return netip.MustParseAddrPort("100.64.0.1:1234") }
func (h testPeer) Logf(string, ...any)                  {}

type testPutBackend struct {
	m   *manager
	cap bool
}

func (b testPutBackend) manager() *manager       { return b.m }
func (b testPutBackend) hasCapFileSharing() bool { return b.cap }
func (b testPutBackend) Clock() tstime.Clock     { return tstime.StdClock{} }

func TestPeerAPIPermissionsAndFilenames(t *testing.T) {
	tests := []struct {
		name     string
		peer     testPeer
		cap      bool
		filename string
		code     int
	}{
		{"same-user", testPeer{same: true}, true, "hello.txt", 200},
		{"other-user", testPeer{}, true, "hello.txt", 403},
		{"unsigned", testPeer{same: true, unsigned: true}, true, "hello.txt", 403},
		{"admin-disabled", testPeer{same: true}, false, "hello.txt", 403},
		{"explicit-grant", testPeer{caps: tailcfg.PeerCapMap{tailcfg.PeerCapabilityFileSharingSend: nil}}, true, "hello.txt", 200},
		{"traversal", testPeer{same: true}, true, "../secret", 400},
		{"partial-name", testPeer{same: true}, true, "secret.partial", 400},
		{"unicode", testPeer{same: true}, true, "文件 #1.txt", 200},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fops, err := newFileOps(t.TempDir())
			if err != nil {
				t.Fatal(err)
			}
			m := managerOptions{fileOps: fops}.New()
			defer m.Shutdown()
			r := httptest.NewRequest("PUT", "http://peer/v0/put/"+url.PathEscape(tt.filename), strings.NewReader("payload"))
			w := httptest.NewRecorder()
			handlePeerPutWithBackend(tt.peer, testPutBackend{m, tt.cap}, w, r)
			if w.Code != tt.code {
				t.Fatalf("%d: %s", w.Code, w.Body.String())
			}
			files, err := m.WaitingFiles()
			if err != nil {
				t.Fatal(err)
			}
			if tt.code == 200 {
				if len(files) != 1 || files[0].Name != tt.filename || files[0].Size != 7 {
					t.Fatalf("%+v", files)
				}
			} else if len(files) != 0 {
				t.Fatal("unauthorized file written")
			}
		})
	}
}
func TestReceiveProgressAndDuplicateDoNotTruncate(t *testing.T) {
	dir := t.TempDir()
	fops, err := newFileOps(dir)
	if err != nil {
		t.Fatal(err)
	}
	m := managerOptions{fileOps: fops}.New()
	defer m.Shutdown()
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	finished := make(chan error, 1)
	go func() { _, err := m.PutFile("sender", "file", reader, 0, 6); finished <- err }()
	if _, err := writer.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for {
		files := m.IncomingFiles()
		if len(files) == 1 && files[0].Received == 3 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("missing progress: %+v", files)
		}
		time.Sleep(time.Millisecond)
	}
	if _, err := m.PutFile("sender", "file", strings.NewReader("xxxxxx"), 0, 6); !errors.Is(err, ErrFileExists) {
		t.Fatalf("duplicate: %v", err)
	}
	if _, err := writer.Write([]byte("def")); err != nil {
		t.Fatal(err)
	}
	writer.Close()
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(dir + "/file")
	if err != nil || string(data) != "abcdef" {
		t.Fatalf("%q %v", data, err)
	}
}
func TestRestartResumesAndRenamesConflicts(t *testing.T) {
	fops, err := newFileOps(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	m := managerOptions{fileOps: fops}.New()
	if _, err := m.PutFile("sender", "file", strings.NewReader("abc"), 0, 6); err == nil {
		t.Fatal("accepted short body")
	}
	m.Shutdown()
	m = managerOptions{fileOps: fops}.New()
	defer m.Shutdown()
	if _, err := m.PutFile("sender", "file", strings.NewReader("def"), 3, 3); err != nil {
		t.Fatal(err)
	}
	if _, err := m.PutFile("sender", "file", strings.NewReader("different"), 0, 9); err != nil {
		t.Fatal(err)
	}
	files, err := m.WaitingFiles()
	if err != nil || len(files) != 2 {
		t.Fatalf("%+v %v", files, err)
	}
	rc, _, err := m.OpenFile("file")
	if err != nil {
		t.Fatal(err)
	}
	defer rc.Close()
	data, err := io.ReadAll(rc)
	if err != nil || string(data) != "abcdef" {
		t.Fatalf("%q %v", data, err)
	}
	if _, _, err := m.OpenFile("../state"); err == nil {
		t.Fatal("path traversal")
	}
}

type putTestNodes struct {
	ipnext.NodeBackend
	target tailcfg.NodeView
	url    string
}

func (n putTestNodes) AppendMatchingPeers(peers []tailcfg.NodeView, f func(tailcfg.NodeView) bool) []tailcfg.NodeView {
	if f(n.target) {
		return append(peers, n.target)
	}
	return peers
}
func (n putTestNodes) PeerAPIBase(tailcfg.NodeView) string { return n.url }

type testBlockedPacket struct{ net.PacketConn }

func (testBlockedPacket) WriteTo([]byte, net.Addr) (int, error) {
	return 0, errors.New("network disabled")
}

func TestLocalClientToPeerAPIRoundTrip(t *testing.T) {
	t.Setenv("TS_AUTHKEY", "")
	fops, err := newFileOps(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	received := managerOptions{fileOps: fops}.New()
	defer received.Shutdown()
	var allowed atomic.Bool
	allowed.Store(true)
	peer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlePeerPutWithBackend(testPeer{same: true}, testPutBackend{received, allowed.Load()}, w, r)
	}))
	defer peer.Close()
	server := &tsnet.Server{Dir: t.TempDir(), Hostname: "taildrop-roundtrip", Logf: t.Logf, UserLogf: t.Logf,
		SystemDialer: func(context.Context, string, string) (net.Conn, error) { return nil, errors.New("network disabled") },
		SystemPacketListener: func(_ context.Context, network, _ string) (net.PacketConn, error) {
			addr := "127.0.0.1:0"
			if network == "udp6" {
				addr = "[::1]:0"
			}
			conn, err := net.ListenPacket(network, addr)
			if err != nil {
				return nil, err
			}
			return testBlockedPacket{conn}, nil
		},
	}
	if err := EnableStagingRoot(server.Dir); err != nil {
		t.Fatal(err)
	}
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	lb := tsnet.TestHooks.LocalBackend(server)
	ext, ok := ipnlocal.GetExt[*Extension](lb)
	if !ok {
		t.Fatal("extension not registered")
	}
	online := true
	ext.mu.Lock()
	ext.selfUID = 1
	ext.backendState = ipn.Running
	ext.capFileSharing = true
	ext.nodeBackendForTest = putTestNodes{target: (&tailcfg.Node{User: 1, StableID: "receiver", Name: "receiver.test", Online: &online, Hostinfo: (&tailcfg.Hostinfo{OS: "linux"}).View()}).View(), url: peer.URL}
	ext.mu.Unlock()
	lc, err := server.LocalClient()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, name := range []string{"plain.txt", "文件 #1? 100%.txt"} {
		if err := lc.PushFile(ctx, "receiver", 7, name, strings.NewReader("payload")); err != nil {
			t.Fatal(err)
		}
		rc, size, err := received.OpenFile(name)
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil || size != 7 || string(data) != "payload" {
			t.Fatalf("%q %d %v", data, size, err)
		}
	}
	if err := lc.PushFile(ctx, "receiver", -1, "unknown-size", strings.NewReader("unknown")); err != nil {
		t.Fatal(err)
	}
	allowed.Store(false)
	if err := lc.PushFile(ctx, "receiver", 1, "denied", strings.NewReader("x")); err == nil {
		t.Fatal("ignored HTTP rejection")
	}
	ext.mu.Lock()
	defer ext.mu.Unlock()
	for _, file := range ext.outgoingFiles {
		if file.Name == "denied" && (!file.Finished || file.Succeeded) {
			t.Fatalf("wrong failure progress: %+v", file)
		}
	}
}

type rootTestBackend struct {
	ipnext.SafeBackend
	root string
}

func (b rootTestBackend) TailscaleVarRoot() string { return b.root }
func TestTaildropRequiresExplicitNodeOptIn(t *testing.T) {
	e := &Extension{sb: rootTestBackend{root: t.TempDir()}}
	if err := e.Init(nil); !errors.Is(err, ipnext.SkipExtension) {
		t.Fatalf("unrelated tsnet node enabled Taildrop: %v", err)
	}
}

func TestIncomingCancelInterruptsBlockedHTTPBody(t *testing.T) {
	fops, err := newFileOps(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	m := managerOptions{fileOps: fops}.New()
	defer m.Shutdown()
	finished := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(finished)
		handlePeerPutWithBackend(testPeer{same: true}, testPutBackend{m, true}, w, r)
	}))
	defer server.Close()
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "PUT", server.URL+"/v0/put/file", reader)
	if err != nil {
		t.Fatal(err)
	}
	req.ContentLength = 6
	response := make(chan error, 1)
	go func() {
		resp, err := server.Client().Do(req)
		if resp != nil {
			resp.Body.Close()
		}
		response <- err
	}()
	if _, err := writer.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		files := m.IncomingFiles()
		if len(files) == 1 && files[0].Received == 3 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("no receive progress: %+v", files)
		}
		time.Sleep(time.Millisecond)
	}
	cancelled := make(chan struct{})
	go func() {
		m.incomingFiles.All()(func(_ incomingFileKey, f *incomingFile) bool { f.cancel(); return true })
		close(cancelled)
	}()
	select {
	case <-cancelled:
	case <-ctx.Done():
		t.Fatal("cancel blocked on request body")
	}
	select {
	case <-finished:
	case <-ctx.Done():
		t.Fatal("HTTP receive did not stop")
	}
	writer.Close()
	<-response
	files, err := m.WaitingFiles()
	if err != nil || len(files) != 0 {
		t.Fatalf("cancelled upload finalized: %+v %v", files, err)
	}
}

func TestCancelIncomingSeparatesSameFilenameSenders(t *testing.T) {
	m := managerOptions{}.New()
	defer m.Shutdown()
	first, second := false, false
	m.incomingFiles.LoadOrInit(incomingFileKey{"first", "same.txt"}, func() *incomingFile { return &incomingFile{cancel: func() { first = true }} })
	m.incomingFiles.LoadOrInit(incomingFileKey{"second", "same.txt"}, func() *incomingFile { return &incomingFile{cancel: func() { second = true }} })
	if err := m.cancelIncoming("first/same.txt"); err != nil {
		t.Fatal(err)
	}
	if !first || second {
		t.Fatalf("cancelled wrong transfer: first=%v second=%v", first, second)
	}
	if err := m.cancelIncoming("first/../state"); err == nil {
		t.Fatal("accepted malformed transfer ID")
	}
}
