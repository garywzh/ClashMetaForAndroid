package taildrop

import (
	"context"
	"fmt"
	"io"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/http/httptest"
)

func awaitOffer(t *testing.T, m *manager) fileOffer {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if offers := m.pendingOffers(); len(offers) > 0 {
			return offers[0]
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("missing receive request")
	return fileOffer{}
}

func TestPeerAPIRejectionReturnsWithoutDrainingAnUnsentBody(t *testing.T) {
	fops, err := newFileOps(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	m := managerOptions{fileOps: fops, RequireApproval: true}.New()
	defer m.Shutdown()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlePeerPutWithBackend(testPeer{same: true}, testPutBackend{m, true}, w, r)
	}))
	defer server.Close()
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, "PUT", server.URL+"/v0/put/test.txt", reader)
	if err != nil {
		t.Fatal(err)
	}
	req.ContentLength = 1024 * 1024
	req.Header.Set("Expect", "100-continue")
	transport := &http.Transport{ExpectContinueTimeout: time.Minute}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport}
	done := make(chan error, 1)
	go func() {
		resp, err := client.Do(req)
		if err == nil {
			defer resp.Body.Close()
			if resp.StatusCode != 403 {
				err = fmt.Errorf("rejection status: %d", resp.StatusCode)
			}
		}
		done <- err
	}()
	offer := awaitOffer(t, m)
	if err := m.decideOffer(offer.ID, false); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal("rejection waited for unapproved content")
	}
}

type observedBody struct {
	io.Reader
	reads atomic.Int64
}

func (b *observedBody) Read(p []byte) (int, error) { b.reads.Add(1); return b.Reader.Read(p) }
func (b *observedBody) Close() error               { return nil }

func TestPeerAPIAwaitsApprovalWithoutReadingOrWriting(t *testing.T) {
	for _, accept := range []bool{true, false} {
		t.Run(map[bool]string{true: "accept", false: "reject"}[accept], func(t *testing.T) {
			dir := t.TempDir()
			fops, err := newFileOps(dir)
			if err != nil {
				t.Fatal(err)
			}
			m := managerOptions{fileOps: fops, RequireApproval: true}.New()
			defer m.Shutdown()
			body := &observedBody{Reader: strings.NewReader("payload")}
			r := httptest.NewRequest("PUT", "http://peer/v0/put/test.txt", nil)
			r.Body, r.ContentLength = body, 7
			w := httptest.NewRecorder()
			done := make(chan struct{})
			go func() {
				defer close(done)
				handlePeerPutWithBackend(testPeer{same: true}, testPutBackend{m, true}, w, r)
			}()
			offer := awaitOffer(t, m)
			if offer.Name != "test.txt" || offer.Size != 7 || offer.Sender != "sender" {
				t.Fatalf("bad offer: %+v", offer)
			}
			if body.reads.Load() != 0 {
				t.Fatal("unapproved body read")
			}
			files, err := os.ReadDir(dir)
			if err != nil || len(files) != 0 {
				t.Fatalf("unapproved files: %+v %v", files, err)
			}
			if err := m.decideOffer(offer.ID, accept); err != nil {
				t.Fatal(err)
			}
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				t.Fatal("request did not complete")
			}
			if len(m.pendingOffers()) != 0 {
				t.Fatal("stale receive request")
			}
			if err := m.decideOffer(offer.ID, true); err == nil {
				t.Fatal("stale approval accepted")
			}
			filesWaiting, err := m.WaitingFiles()
			if err != nil {
				t.Fatal(err)
			}
			if accept {
				if w.Code != 200 || len(filesWaiting) != 1 || body.reads.Load() == 0 {
					t.Fatalf("accepted request failed: %d %+v", w.Code, filesWaiting)
				}
			} else if w.Code != 403 || len(filesWaiting) != 0 || body.reads.Load() != 0 {
				t.Fatalf("declined request wrote data: %d %+v", w.Code, filesWaiting)
			}
		})
	}
}

func TestApprovalCancellationTimeoutAndShutdown(t *testing.T) {
	for _, reason := range []string{"cancel", "timeout", "shutdown"} {
		t.Run(reason, func(t *testing.T) {
			m := managerOptions{RequireApproval: true}.New()
			defer m.Shutdown()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			timeout := time.Minute
			if reason == "timeout" {
				timeout = 30 * time.Millisecond
			}
			done := make(chan error, 1)
			go func() { done <- m.awaitApproval(ctx, "sender", "MacBook", "test.txt", 10, timeout) }()
			offer := awaitOffer(t, m)
			switch reason {
			case "cancel":
				cancel()
			case "shutdown":
				m.Shutdown()
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("unapproved file allowed")
				}
			case <-time.After(time.Second):
				t.Fatal("blocked receive request")
			}
			if len(m.pendingOffers()) != 0 {
				t.Fatal("pending request leaked")
			}
			if m.decideOffer(offer.ID, true) == nil {
				t.Fatal("expired approval allowed")
			}
		})
	}
}

func TestApprovalDecisionsArePerFileAndBounded(t *testing.T) {
	m := managerOptions{RequireApproval: true}.New()
	defer m.Shutdown()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 2)
	go func() { done <- m.awaitApproval(ctx, "sender", "MacBook", "a", 1, time.Minute) }()
	first := awaitOffer(t, m)
	go func() { done <- m.awaitApproval(ctx, "sender", "MacBook", "b", 2, time.Minute) }()
	deadline := time.Now().Add(time.Second)
	for len(m.pendingOffers()) != 2 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(m.pendingOffers()) != 2 {
		t.Fatal("missing second file")
	}
	if err := m.awaitApproval(ctx, "sender", "MacBook", "a", 1, time.Minute); err != ErrFileExists {
		t.Fatalf("duplicate: %v", err)
	}
	if err := m.decideOffer(first.ID, true); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	remaining := m.pendingOffers()
	if len(remaining) != 1 || remaining[0].Name != "b" {
		t.Fatalf("approval affected other files: %+v", remaining)
	}
	m.decideOffer(remaining[0].ID, false)
	if err := <-done; err != errReceiveRejected {
		t.Fatalf("rejection: %v", err)
	}
	m.approvalMu.Lock()
	for i := 0; i < 32; i++ {
		m.offers[string(rune(i))] = &pendingOffer{}
	}
	m.approvalMu.Unlock()
	if err := m.awaitApproval(ctx, "sender", "MacBook", "overflow", 1, time.Minute); err != errApprovalBusy {
		t.Fatalf("limit: %v", err)
	}
}
