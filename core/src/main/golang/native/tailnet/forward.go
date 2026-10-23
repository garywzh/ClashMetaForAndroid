package tailnet

import (
	"context"
	"io"
	"net"
	"sync"
	"time"
)

type forwarder struct {
	listener net.Listener
	ctx      context.Context
	cancel   context.CancelFunc
	wg       sync.WaitGroup
}

func (r *runtime) configureForwardLocked(enabled bool) {
	if !enabled && r.forward != nil {
		r.forward.Close()
		r.forward = nil
	}
	if !enabled || r.forward != nil {
		return
	}
	// tsnet.Listen binds only inside the Tailnet, never on Android's LAN interfaces.
	listener, err := r.server.Listen("tcp", ":8080")
	if err != nil {
		r.lastError = err.Error()
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{listener: listener, ctx: ctx, cancel: cancel}
	r.forward = f
	f.wg.Add(1)
	go f.serve()
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
				relay(f.ctx, conn, "127.0.0.1:8080")
			}()
		default:
			conn.Close()
		}
	}
}

// Forward raw TCP, leaving HTTP paths and the MCP server's Bearer check untouched.
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
