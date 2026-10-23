package tailnet

import (
	"context"
	"io"
	"net"
	"testing"
	"time"
)

func TestRelayPreservesBearerAndResponse(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	request := "POST /mcp HTTP/1.1\r\nAuthorization: Bearer test-token\r\n\r\n"
	response := "HTTP/1.1 401 Unauthorized\r\n\r\n"
	serverResult := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			serverResult <- err
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		buffer := make([]byte, len(request))
		_, err = io.ReadFull(conn, buffer)
		if err == nil && string(buffer) != request {
			err = io.ErrUnexpectedEOF
		}
		if err == nil {
			_, err = io.WriteString(conn, response)
		}
		serverResult <- err
	}()
	client, remote := net.Pipe()
	defer client.Close()
	client.SetDeadline(time.Now().Add(3 * time.Second))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go relay(ctx, remote, listener.Addr().String())
	if _, err = io.WriteString(client, request); err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(client)
	if err != nil || string(data) != response {
		t.Fatalf("response changed: %q, %v", data, err)
	}
	if err = <-serverResult; err != nil {
		t.Fatal(err)
	}
}

func TestRelayCancellationClosesActiveConnections(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() { conn, _ := listener.Accept(); accepted <- conn }()
	client, remote := net.Pipe()
	defer client.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { relay(ctx, remote, listener.Addr().String()); close(done) }()
	var local net.Conn
	select {
	case local = <-accepted:
	case <-time.After(3 * time.Second):
		t.Fatal("relay did not connect")
	}
	defer local.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("relay leaked after cancellation")
	}
	client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err = client.Read(make([]byte, 1)); err != io.EOF {
		t.Fatal("remote connection was not closed", err)
	}
}
