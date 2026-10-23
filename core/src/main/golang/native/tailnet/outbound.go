package tailnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"

	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"
	D "github.com/miekg/dns"
)

type tailnetOutbound struct{ *outbound.Base }

func newOutbound() *tailnetOutbound {
	return &tailnetOutbound{outbound.NewBase(outbound.BaseOption{Name: Name, Type: C.Tailscale, UDP: true})}
}

func (t *tailnetOutbound) DialContext(ctx context.Context, metadata *C.Metadata) (C.Conn, error) {
	if err := t.ResolveUDP(ctx, metadata); err != nil {
		return nil, err
	}
	s, err := currentServer()
	if err != nil {
		return nil, err
	}
	stack, err := s.Netstack(ctx)
	if err != nil {
		return nil, err
	}
	v4, v6 := s.TailscaleIPs()
	source := v4
	if metadata.DstIP.Is6() {
		source = v6
	}
	conn, err := stack.DialContextTCPWithBind(ctx, source, netip.AddrPortFrom(metadata.DstIP, metadata.DstPort))
	if err != nil {
		return nil, err
	}
	return outbound.NewConn(conn, t), nil
}

func (t *tailnetOutbound) IsL3Protocol(*C.Metadata) bool { return true }

func (t *tailnetOutbound) ResolveUDP(ctx context.Context, metadata *C.Metadata) error {
	if metadata.Resolved() {
		return validateDestination(metadata.DstIP)
	}
	for _, queryType := range []uint16{D.TypeA, D.TypeAAAA} {
		msg := new(D.Msg)
		msg.SetQuestion(D.Fqdn(metadata.Host), queryType)
		response, err := (dnsTransport{}).ExchangeContext(ctx, msg)
		if err != nil {
			return err
		}
		for _, rr := range response.Answer {
			var ip net.IP
			switch answer := rr.(type) {
			case *D.A:
				ip = answer.A
			case *D.AAAA:
				ip = answer.AAAA
			}
			address, ok := netip.AddrFromSlice(ip)
			if ok && validateDestination(address.Unmap()) == nil {
				metadata.DstIP = address.Unmap()
				return nil
			}
		}
	}
	return errors.New("no Tailnet address found")
}

var tailnetIPv4 = netip.MustParsePrefix("100.64.0.0/10")
var tailnetIPv6 = netip.MustParsePrefix("fd7a:115c:a1e0::/48")

func validateDestination(ip netip.Addr) error {
	if tailnetIPv4.Contains(ip.Unmap()) || tailnetIPv6.Contains(ip) {
		return nil
	}
	return errors.New("Tailscale integration only routes Tailnet addresses")
}

func (t *tailnetOutbound) ListenPacketContext(ctx context.Context, metadata *C.Metadata) (C.PacketConn, error) {
	if err := t.ResolveUDP(ctx, metadata); err != nil {
		return nil, err
	}
	s, err := currentServer()
	if err != nil {
		return nil, err
	}
	// Wait for authentication before asking tsnet to bind a local Tailnet address.
	if _, err = s.Netstack(ctx); err != nil {
		return nil, err
	}
	v4, v6 := s.TailscaleIPs()
	source := v4
	if metadata.DstIP.Is6() {
		source = v6
	}
	pc, err := s.ListenPacket("udp", net.JoinHostPort(source.String(), "0"))
	if err != nil {
		return nil, err
	}
	return outbound.NewPacketConn(pc, t), nil
}

type dnsTransport struct{}

func (dnsTransport) Address() string  { return "tailscale://" + Name }
func (dnsTransport) ResetConnection() {}
func (dnsTransport) ExchangeContext(ctx context.Context, msg *D.Msg) (*D.Msg, error) {
	if len(msg.Question) != 1 {
		return nil, errors.New("expected one DNS question")
	}
	s, err := currentServer()
	if err != nil {
		return nil, err
	}
	lc, err := s.LocalClient()
	if err != nil {
		return nil, err
	}
	question := msg.Question[0]
	queryType, ok := D.TypeToString[question.Qtype]
	if !ok {
		return nil, fmt.Errorf("unsupported DNS type %d", question.Qtype)
	}
	data, _, err := lc.QueryDNS(ctx, question.Name, queryType)
	if err != nil {
		return nil, err
	}
	response := new(D.Msg)
	if err = response.Unpack(data); err != nil {
		return nil, err
	}
	response.Id = msg.Id
	return response, nil
}
