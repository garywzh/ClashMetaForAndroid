package tailnet

import (
	"context"
	"io"
	"net"
	"strconv"
	"sync"
	"time"
)

type forwarder struct {
	rule     ForwardRule
	listener net.Listener
	ctx      context.Context
	cancel   context.CancelFunc
	wg       sync.WaitGroup
}

type ForwardStatus struct {
	ID        string `json:"id"`
	Listening bool   `json:"listening"`
	Error     string `json:"error"`
}

func (r *runtime) configureForwardsLocked(rules []ForwardRule) {
	// tsnet.Listen binds only inside the Tailnet, never on Android's LAN interfaces.
	if r.server == nil {
		rules = nil
	}
	r.reconcileForwards(rules, func(network, address string) (net.Listener, error) {
		return r.server.Listen(network, address)
	})
}

func (r *runtime) reconcileForwards(rules []ForwardRule, listen func(string, string) (net.Listener, error)) {
	wanted := make(map[string]ForwardRule)
	for _, rule := range rules {
		if rule.Enabled {
			wanted[rule.ID] = rule
		}
	}
	for id, f := range r.forwards {
		rule, exists := wanted[id]
		if !exists || f.rule.LocalPort != rule.LocalPort || f.rule.TailnetPort != rule.TailnetPort {
			f.Close()
			delete(r.forwards, id)
		}
	}
	if r.forwards == nil {
		r.forwards = make(map[string]*forwarder)
	}
	r.forwardErrors = make(map[string]string)
	for _, rule := range rules {
		if !rule.Enabled || r.forwards[rule.ID] != nil {
			continue
		}
		listener, err := listen("tcp", ":"+strconv.Itoa(rule.TailnetPort))
		if err != nil {
			r.forwardErrors[rule.ID] = err.Error()
			continue
		}
		ctx, cancel := context.WithCancel(context.Background())
		f := &forwarder{rule: rule, listener: listener, ctx: ctx, cancel: cancel}
		r.forwards[rule.ID] = f
		f.wg.Add(1)
		go f.serve()
	}
}

func (f *forwarder) serve() {
	defer f.wg.Done()
	slots := make(chan struct{}, 32)
	for {
		conn, err := f.listener.Accept()
		if err != nil {
			return
		}
		select {
		case slots <- struct{}{}:
			f.wg.Add(1)
			go func() {
				defer f.wg.Done()
				defer func() { <-slots }()
				relay(f.ctx, conn, net.JoinHostPort("127.0.0.1", strconv.Itoa(f.rule.LocalPort)))
			}()
		default:
			conn.Close()
		}
	}
}

// Forward raw TCP without interpreting protocols or bypassing service authentication.
func relay(ctx context.Context, remote net.Conn, target string) {
	defer remote.Close()
	local, err := (&net.Dialer{Timeout: 3 * time.Second}).DialContext(ctx, "tcp", target)
	if err != nil {
		return
	}
	defer local.Close()
	stop := context.AfterFunc(ctx, func() { remote.Close(); local.Close() })
	defer stop()
	done := make(chan struct{})
	go func() {
		io.Copy(local, remote)
		if tcp, ok := local.(interface{ CloseWrite() error }); ok {
			tcp.CloseWrite()
		}
		close(done)
	}()
	io.Copy(remote, local)
	remote.Close()
	local.Close()
	<-done
}

func (f *forwarder) Close() {
	f.cancel()
	f.listener.Close()
	f.wg.Wait()
}
