package tailnet

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/tailscale/client/local"
	"github.com/metacubex/tailscale/client/tailscale/apitype"
	"github.com/metacubex/tailscale/tailcfg"
)

type dropClient interface {
	PushFile(context.Context, tailcfg.StableNodeID, int64, string, io.Reader) error
	GetWaitingFile(context.Context, string) (io.ReadCloser, int64, error)
	DeleteWaitingFile(context.Context, string) error
	WaitingFiles(context.Context) ([]apitype.WaitingFile, error)
	FileTargets(context.Context) ([]apitype.FileTarget, error)
	IncomingFiles(context.Context) ([]dropIncoming, error)
	CancelIncoming(context.Context, string) error
}

type detailedDropClient interface {
	waitingDetails(context.Context) ([]DropFile, error)
}

type approvalDropClient interface {
	pendingOffers(context.Context) ([]DropOffer, error)
	decideOffer(context.Context, string, bool) error
}

type DropOffer struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Sender string `json:"sender"`
}

type DropTarget struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Online bool   `json:"online"`
}

type dropIncoming struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	Received int64  `json:"received"`
	Done     bool   `json:"done"`
}

type DropFile struct {
	ID   string `json:"id,omitempty"`
	Name string `json:"name"`
	Size int64  `json:"size"`
}

type DropTransfer struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Direction string `json:"direction"`
	Size      int64  `json:"size"`
	Done      int64  `json:"done"`
	State     string `json:"state"`
	Error     string `json:"error"`
}

type DropStatus struct {
	Session   string         `json:"session"`
	Running   bool           `json:"running"`
	Targets   []DropTarget   `json:"targets,omitempty"`
	Files     []DropFile     `json:"files,omitempty"`
	Incoming  []DropTransfer `json:"incoming,omitempty"`
	Offers    []DropOffer    `json:"offers,omitempty"`
	Transfers []DropTransfer `json:"transfers,omitempty"`
	Error     string         `json:"error"`
}

type dropJob struct {
	DropTransfer
	count  atomic.Int64
	cancel context.CancelFunc
	file   *os.File
}

// Transfers belong to the VPN session, not an Activity or Binder connection.
// Cancelling the session closes Android pipe/file descriptors as well as HTTP.
type taildropSession struct {
	client dropClient
	ctx    context.Context
	cancel context.CancelFunc
	mu     sync.Mutex
	jobs   []*dropJob
	seq    uint64
	id     string
	queue  chan struct{}
	wg     sync.WaitGroup
}

func makeTaildropSession(client dropClient) *taildropSession {
	ctx, cancel := context.WithCancel(context.Background())
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		panic(err)
	}
	return &taildropSession{client: client, ctx: ctx, cancel: cancel, id: hex.EncodeToString(id[:]), queue: make(chan struct{}, 2)}
}

func newTaildropSession(client *local.Client) *taildropSession {
	return makeTaildropSession(nativeDropClient{client})
}

type nativeDropClient struct{ *local.Client }

func (c nativeDropClient) backendRunning(ctx context.Context) bool {
	status, err := c.StatusWithoutPeers(ctx)
	return err == nil && status.BackendState == "Running"
}

func (c nativeDropClient) IncomingFiles(ctx context.Context) ([]dropIncoming, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/file-incoming", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.DoLocalRequest(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("incoming files: HTTP %d", resp.StatusCode)
	}
	var result []dropIncoming
	err = json.NewDecoder(resp.Body).Decode(&result)
	return result, err
}

func (c nativeDropClient) waitingDetails(ctx context.Context) ([]DropFile, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/file-waiting", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.DoLocalRequest(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("waiting files: HTTP %d", resp.StatusCode)
	}
	var result []DropFile
	err = json.NewDecoder(resp.Body).Decode(&result)
	return result, err
}

func (c nativeDropClient) pendingOffers(ctx context.Context) ([]DropOffer, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", "http://local-tailscaled.sock/localapi/v0/file-offers", nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.DoLocalRequest(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("pending files: HTTP %d", resp.StatusCode)
	}
	var result []DropOffer
	err = json.NewDecoder(resp.Body).Decode(&result)
	return result, err
}

func (c nativeDropClient) decideOffer(ctx context.Context, id string, accept bool) error {
	body, _ := json.Marshal(struct {
		Accept bool `json:"accept"`
	}{accept})
	req, err := http.NewRequestWithContext(ctx, "POST", "http://local-tailscaled.sock/localapi/v0/file-decision/"+url.PathEscape(id), bytes.NewReader(body))
	if err != nil {
		return err
	}
	resp, err := c.DoLocalRequest(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return fmt.Errorf("file decision: %s", strings.TrimSpace(string(message)))
	}
	return nil
}

func (s *taildropSession) checkFile(ctx context.Context, name, id string) error {
	if id == "" {
		return nil
	}
	client, ok := s.client.(detailedDropClient)
	if !ok {
		return nil
	}
	files, err := client.waitingDetails(ctx)
	if err != nil {
		return err
	}
	for _, file := range files {
		if file.Name == name && file.ID == id {
			return nil
		}
	}
	return errors.New("received file changed; refresh and try again")
}

func (c nativeDropClient) CancelIncoming(ctx context.Context, name string) error {
	req, err := http.NewRequestWithContext(ctx, "POST", "http://local-tailscaled.sock/localapi/v0/file-cancel/"+url.PathEscape(name), nil)
	if err != nil {
		return err
	}
	resp, err := c.DoLocalRequest(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("cancel incoming: HTTP %d", resp.StatusCode)
	}
	return nil
}

func (s *taildropSession) cancelAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, job := range s.jobs {
		if !dropFinished(job.State) {
			job.cancel()
			job.file.Close()
		}
	}
}

func (s *taildropSession) close() {
	s.cancel()
	s.cancelAll()
	// Do not wait for workers under runtime.mu. The cancelled session can
	// never be reused, even if a document provider takes time to release a pipe.
}

func dropFinished(state string) bool {
	return state == "done" || state == "failed" || state == "cancelled"
}

type dropRequest struct {
	Session  string `json:"session"`
	FD       int    `json:"fd"`
	ID       string `json:"id"`
	Target   string `json:"target"`
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	FileID   string `json:"fileId"`
	Download bool   `json:"download"`
}

// TaildropCommand only transports metadata. File bytes cross JNI through an
// owned descriptor opened by the service's ContentResolver, never JSON/base64.
func TaildropCommand(command, content string) DropStatus {
	req := dropRequest{FD: -1, Size: -1}
	if content != "" {
		if err := json.Unmarshal([]byte(content), &req); err != nil {
			return DropStatus{Error: err.Error()}
		}
	}
	var file *os.File
	if command == "taildrop-send" || command == "taildrop-save" {
		if req.FD < 0 {
			return DropStatus{Error: "missing file descriptor"}
		}
		_ = syscall.SetNonblock(req.FD, true)
		file = os.NewFile(uintptr(req.FD), req.Name)
	}
	manager.mu.Lock()
	s := manager.drop
	manager.mu.Unlock()
	if s == nil || s.ctx.Err() != nil {
		if file != nil {
			file.Close()
		}
		return DropStatus{Error: "start Clash with Tailscale enabled first"}
	}
	var err error
	if req.Session != "" && req.Session != s.id {
		if file != nil {
			file.Close()
		}
		return DropStatus{Error: "Taildrop session changed; refresh and try again"}
	}
	switch command {
	case "taildrop-session":
		return s.snapshot()
	case "taildrop-status":
		return s.status()
	case "taildrop-accept", "taildrop-reject":
		if req.Session != s.id {
			err = errors.New("Taildrop session changed; refresh and try again")
			break
		}
		if client, ok := s.client.(approvalDropClient); ok {
			ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
			err = client.decideOffer(ctx, req.ID, command == "taildrop-accept")
			cancel()
		} else {
			err = errors.New("file approval unavailable")
		}
	case "taildrop-send", "taildrop-save":
		if command == "taildrop-save" {
			ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
			err = s.checkFile(ctx, req.Name, req.FileID)
			cancel()
		}
		if err == nil {
			err = s.start(req, file, command == "taildrop-save")
		}
		if err != nil {
			file.Close()
		}
	case "taildrop-cancel":
		err = s.cancelJob(req.ID)
	case "taildrop-cancel-incoming":
		ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
		defer cancel()
		err = s.client.CancelIncoming(ctx, req.ID)
	case "taildrop-delete":
		ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
		defer cancel()
		err = s.checkFile(ctx, req.Name, req.FileID)
		if err == nil {
			err = s.client.DeleteWaitingFile(ctx, req.Name)
		}
	case "taildrop-dismiss":
		s.mu.Lock()
		var active []*dropJob
		for _, job := range s.jobs {
			if !dropFinished(job.State) {
				active = append(active, job)
			}
		}
		s.jobs = active
		s.mu.Unlock()
	default:
		err = errors.New("unknown Taildrop command")
	}
	result := s.snapshot()
	if err != nil {
		result.Error = err.Error()
	}
	return result
}

func (s *taildropSession) snapshot() DropStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	result := DropStatus{Session: s.id, Running: s.ctx.Err() == nil}
	for _, job := range s.jobs {
		transfer := job.DropTransfer
		transfer.Done = job.count.Load()
		result.Transfers = append(result.Transfers, transfer)
	}
	return result
}

func (s *taildropSession) status() DropStatus {
	result := s.snapshot()
	ctx, cancel := context.WithTimeout(s.ctx, 8*time.Second)
	defer cancel()
	if client, ok := s.client.(nativeDropClient); ok && !client.backendRunning(ctx) {
		result.Running = false
		return result
	}
	if client, ok := s.client.(approvalDropClient); ok {
		var err error
		result.Offers, err = client.pendingOffers(ctx)
		if err != nil {
			result.Error = err.Error()
		}
	}
	var err error
	if client, ok := s.client.(detailedDropClient); ok {
		result.Files, err = client.waitingDetails(ctx)
	} else {
		var files []apitype.WaitingFile
		files, err = s.client.WaitingFiles(ctx)
		for _, file := range files {
			result.Files = append(result.Files, DropFile{Name: file.Name, Size: file.Size})
		}
	}
	if err != nil {
		result.Error = err.Error()
	}
	incoming, incomingErr := s.client.IncomingFiles(ctx)
	if incomingErr != nil && result.Error == "" {
		result.Error = incomingErr.Error()
	}
	for _, file := range incoming {
		if !file.Done {
			result.Incoming = append(result.Incoming, DropTransfer{ID: file.ID, Name: file.Name, Size: file.Size, Done: file.Received, Direction: "receive", State: "receiving"})
		}
	}
	sort.Slice(result.Incoming, func(i, j int) bool { return result.Incoming[i].ID < result.Incoming[j].ID })
	targets, err := s.client.FileTargets(ctx)
	if err != nil {
		result.Error = err.Error()
	} else {
		for _, target := range targets {
			n := target.Node
			if n == nil || n.Expired || n.StableID == "" {
				continue
			}
			name := n.ComputedName
			if name == "" {
				name = n.Name
			}
			result.Targets = append(result.Targets, DropTarget{ID: string(n.StableID), Name: name, Online: n.Online != nil && *n.Online})
		}
	}
	sort.Slice(result.Files, func(i, j int) bool { return result.Files[i].Name < result.Files[j].Name })
	return result
}

func (s *taildropSession) start(req dropRequest, file *os.File, save bool) error {
	if req.Name == "" || (!save && req.Target == "") || req.Size < -1 {
		return errors.New("invalid file or target")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ctx.Err() != nil {
		return s.ctx.Err()
	}
	active := 0
	for _, job := range s.jobs {
		if !dropFinished(job.State) {
			active++
		}
	}
	if active >= 32 {
		return errors.New("Taildrop queue is full (32 files)")
	}
	if len(s.jobs) >= 100 {
		for i, job := range s.jobs {
			if dropFinished(job.State) {
				s.jobs = append(s.jobs[:i], s.jobs[i+1:]...)
				break
			}
		}
	}
	s.seq++
	direction := "send"
	if save {
		direction = "save"
		if req.Download {
			direction = "download"
		}
	}
	id := strconv.FormatUint(s.seq, 10)
	if req.ID != "" {
		for _, job := range s.jobs {
			if job.ID == req.ID {
				return errors.New("duplicate transfer ID")
			}
		}
		id = req.ID
	}
	ctx, cancel := context.WithCancel(s.ctx)
	job := &dropJob{DropTransfer: DropTransfer{ID: id, Name: req.Name, Direction: direction, Size: req.Size, State: "queued"}, cancel: cancel, file: file}
	s.jobs = append(s.jobs, job)
	s.wg.Add(1)
	go s.run(ctx, job, req.Target)
	return nil
}

func (s *taildropSession) run(ctx context.Context, job *dropJob, target string) {
	defer s.wg.Done()
	defer job.cancel()
	defer job.file.Close()
	var err error
	select {
	case s.queue <- struct{}{}:
		defer func() { <-s.queue }()
		s.mu.Lock()
		job.State = "sending"
		if job.Direction != "send" {
			job.State = "saving"
		}
		s.mu.Unlock()
		if job.Direction != "send" {
			var reader io.ReadCloser
			var size int64
			reader, size, err = s.client.GetWaitingFile(ctx, job.Name)
			if err == nil {
				s.mu.Lock()
				job.Size = size
				s.mu.Unlock()
				// Cancellation must unblock both a document-provider pipe and
				// the LocalAPI stream, including while the provider is stalled.
				stop := context.AfterFunc(ctx, func() { reader.Close(); job.file.Close() })
				var copied int64
				copied, err = io.Copy(&dropWriter{Writer: job.file, count: &job.count}, reader)
				stop()
				reader.Close()
				if err == nil && size >= 0 && copied != size {
					err = io.ErrUnexpectedEOF
				}
				if closeErr := job.file.Close(); err == nil {
					err = closeErr
				}
			}
			// The Android coordinator owns publication and identity-checked
			// staging cleanup. A completed copy alone must never delete it.
		} else {
			stop := context.AfterFunc(ctx, func() { job.file.Close() })
			err = s.client.PushFile(ctx, tailcfg.StableNodeID(target), job.Size, job.Name, &dropReader{Reader: job.file, count: &job.count})
			stop()
		}
	case <-ctx.Done():
		err = ctx.Err()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	switch {
	case ctx.Err() != nil:
		job.State = "cancelled"
	case err != nil:
		job.State, job.Error = "failed", err.Error()
	default:
		job.State = "done"
	}
}

func (s *taildropSession) cancelJob(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, job := range s.jobs {
		if job.ID == id {
			job.cancel()
			job.file.Close()
			return nil
		}
	}
	return fmt.Errorf("unknown transfer %q", id)
}

type dropReader struct {
	io.Reader
	count *atomic.Int64
}

func (r *dropReader) Read(p []byte) (int, error) {
	n, err := r.Reader.Read(p)
	r.count.Add(int64(n))
	return n, err
}

type dropWriter struct {
	io.Writer
	count *atomic.Int64
}

func (w *dropWriter) Write(p []byte) (int, error) {
	n, err := w.Writer.Write(p)
	w.count.Add(int64(n))
	return n, err
}
