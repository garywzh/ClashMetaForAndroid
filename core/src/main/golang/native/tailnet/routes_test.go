package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/tailscale/client/local"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/ipn/ipnstate"
	"github.com/metacubex/tailscale/tailcfg"
	"github.com/metacubex/tailscale/tsnet"
	"github.com/metacubex/tailscale/types/key"
	"github.com/metacubex/tailscale/types/views"
)

func subnetStatus(routes ...string) *ipnstate.Status {
	prefixes := make([]netip.Prefix, 0, len(routes))
	for _, route := range routes {
		prefixes = append(prefixes, netip.MustParsePrefix(route))
	}
	allowed := views.SliceOf(prefixes)
	return &ipnstate.Status{
		BackendState: "Running",
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			{}: {AllowedIPs: &allowed},
		},
	}
}

func installSubnets(t *testing.T, routes ...string) *subnetSession {
	t.Helper()
	session := &subnetSession{enabled: true}
	session.update(subnetStatus(routes...), nil)
	previous := manager.subnets.Swap(session)
	t.Cleanup(func() {
		session.close()
		manager.subnets.Store(previous)
	})
	return session
}

func TestExistingSettingsDoNotOptInToSubnets(t *testing.T) {
	var settings Settings
	if err := json.Unmarshal([]byte(`{"enabled":true,"forwards":[{"id":"web","name":"Web","enabled":true,"localPort":8000,"tailnetPort":8080}]}`), &settings); err != nil {
		t.Fatal(err)
	}
	if settings.AcceptRoutes || !settings.Enabled || len(settings.Forwards) != 1 {
		t.Fatal("legacy settings changed meaning", settings)
	}
	testHome(t)
	settings.AcceptRoutes = true
	if err := saveSettings(settings); err != nil {
		t.Fatal(err)
	}
	got, err := ReadSettings()
	if err != nil || !reflect.DeepEqual(got, settings) {
		t.Fatal("subnet opt-in did not persist", got, err)
	}
}

func TestRoutesCommandOnlyReadsSnapshot(t *testing.T) {
	testHome(t)
	if err := saveSettings(Settings{Enabled: true, AcceptRoutes: true}); err != nil {
		t.Fatal(err)
	}
	installSubnets(t, "192.168.31.0/24")
	manager.server = &tsnet.Server{}
	t.Cleanup(func() { manager.server = nil })
	status := Command("routes", "")
	if !reflect.DeepEqual(status.SubnetRoutes, []string{"192.168.31.0/24"}) {
		t.Fatal("Android VPN routes do not reflect native snapshot", status)
	}
	// A zero-value server has no LocalClient yet. The command must not start one.
	if err := saveSettings(Settings{Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if status = Command("routes", ""); len(status.SubnetRoutes) != 0 {
		t.Fatal("disabled settings leaked subnet routes to Android", status)
	}
}

func TestApprovedSubnetsExcludeExitAndNodeAddresses(t *testing.T) {
	status := subnetStatus(
		"192.168.31.7/24", "192.168.31.0/24", "10.0.0.2/32", "fd00:1234::/64",
		"100.64.0.1/32", "fd7a:115c:a1e0::1/128", "0.0.0.0/0", "::/0",
		"127.0.0.0/8", "169.254.0.0/16", "fe80::/64", "224.0.0.0/4",
		"::ffff:192.168.99.0/120",
	)
	want := []netip.Prefix{
		netip.MustParsePrefix("10.0.0.2/32"), netip.MustParsePrefix("192.168.31.0/24"),
		netip.MustParsePrefix("fd00:1234::/64"),
	}
	if got := approvedSubnets(status); !reflect.DeepEqual(got, want) {
		t.Fatalf("accepted unexpected routes: %v, want %v", got, want)
	}
	status.Peer[key.NodePublic{}].Expired = true
	if got := approvedSubnets(status); len(got) != 0 {
		t.Fatal("expired router's routes were accepted", got)
	}
}

func TestSubnetRuleTracksChangesWithoutReload(t *testing.T) {
	session := installSubnets(t, "192.168.31.0/24", "fd00:1234::/64")
	rule := &subnetRule{}
	metadata := &C.Metadata{DstIP: netip.MustParseAddr("192.168.31.5")}
	match := func(want bool) {
		t.Helper()
		matched, adapter := rule.Match(metadata, C.RuleMatchHelper{})
		if matched != want || adapter != Name {
			t.Fatalf("match = %v, %s; want %v, %s", matched, adapter, want, Name)
		}
	}
	match(true) // Tailnet wins even if a subscription would send LAN IPs DIRECT.
	session.update(subnetStatus("10.2.0.0/16"), nil)
	match(false)
	metadata.DstIP = netip.MustParseAddr("10.2.1.1")
	match(true)
	session.setEnabled(false)
	match(false)
	session.setEnabled(true)
	match(true)
	session.update(subnetStatus(), nil)
	match(false)
}

func TestSubnetRuleResolvesDomainsOnlyWhenNeeded(t *testing.T) {
	session := installSubnets(t, "192.168.31.0/24")
	metadata := &C.Metadata{Host: "nas.example.org"}
	lookups := 0
	helper := C.RuleMatchHelper{ResolveIP: func() {
		lookups++
		metadata.DstIP = netip.MustParseAddr("192.168.31.5")
	}}
	if matched, _ := (&subnetRule{}).Match(metadata, helper); !matched || lookups != 1 {
		t.Fatal("domain resolving to an accepted subnet did not match")
	}
	metadata.DstIP = netip.Addr{}
	session.setEnabled(false)
	if matched, _ := (&subnetRule{}).Match(metadata, helper); matched || lookups != 1 {
		t.Fatal("disabled subnet rule changed DNS behavior")
	}
}

func TestSubnetValidationOnlyAllowsAcceptedDestinations(t *testing.T) {
	session := installSubnets(t, "192.168.31.0/24", "fd00:1234::/64", "203.0.113.0/24")
	for _, address := range []string{"192.168.31.5", "::ffff:192.168.31.5", "fd00:1234::5", "203.0.113.5", "100.64.0.5"} {
		if err := validateDestination(netip.MustParseAddr(address)); err != nil {
			t.Fatal(address, err)
		}
	}
	for _, address := range []string{"192.168.32.5", "10.0.0.1", "1.1.1.1", "fd00:1235::5", "127.0.0.1", "fe80::1"} {
		if validateDestination(netip.MustParseAddr(address)) == nil {
			t.Fatal("unaccepted destination allowed", address)
		}
	}
	session.setEnabled(false)
	if validateDestination(netip.MustParseAddr("192.168.31.5")) == nil {
		t.Fatal("disabling subnet routes did not revoke outbound access")
	}
	if err := validateDestination(netip.MustParseAddr("100.64.0.5")); err != nil {
		t.Fatal("disabling subnets also disabled ordinary Tailnet access", err)
	}
}

func TestSessionClearsRoutesOnStopLogoutAndFailure(t *testing.T) {
	for _, state := range []string{"Stopped", "NeedsLogin", "NeedsMachineAuth", "Starting"} {
		t.Run(state, func(t *testing.T) {
			session := installSubnets(t, "192.168.31.0/24")
			status := subnetStatus("192.168.31.0/24")
			status.BackendState = state
			session.update(status, nil)
			if session.contains(netip.MustParseAddr("192.168.31.1")) {
				t.Fatal("non-running session retained subnet routes")
			}
		})
	}
	session := installSubnets(t, "192.168.31.0/24")
	session.update(nil, errors.New("watch failed"))
	if session.contains(netip.MustParseAddr("192.168.31.1")) || session.current.Load().err == "" {
		t.Fatal("failure did not clear routes or report an error")
	}
	session.close()
	session.setEnabled(true)
	session.update(subnetStatus("192.168.31.0/24"), nil)
	if session.contains(netip.MustParseAddr("192.168.31.1")) {
		t.Fatal("late notification resurrected a closed session")
	}
}

type recordingPacketConn struct {
	net.PacketConn
	writes int
}

func (c *recordingPacketConn) WriteTo(data []byte, _ net.Addr) (int, error) {
	c.writes++
	return len(data), nil
}

func TestUDPSocketRevalidatesAfterRouteWithdrawal(t *testing.T) {
	session := installSubnets(t, "192.168.31.0/24")
	upstream := &recordingPacketConn{}
	pc := &checkedPacketConn{PacketConn: upstream, subnets: session}
	destination := net.UDPAddrFromAddrPort(netip.MustParseAddrPort("192.168.31.5:53"))
	if _, err := pc.WriteTo([]byte("dns"), destination); err != nil {
		t.Fatal(err)
	}
	session.update(subnetStatus(), nil)
	if _, err := pc.WriteTo([]byte("dns"), destination); err == nil || upstream.writes != 1 {
		t.Fatal("cached UDP socket bypassed route withdrawal")
	}
	// A replacement server must not grant access to an old server's UDP socket.
	session.close()
	installSubnets(t, "192.168.31.0/24")
	if _, err := pc.WriteTo([]byte("dns"), destination); err == nil {
		t.Fatal("old socket inherited new VPN's permissions")
	}
}

func waitFor(t *testing.T, predicate func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if predicate() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("timed out waiting for route update")
}

func TestAndroidPeerNotificationsRefreshAndWithdrawRoutes(t *testing.T) {
	var status atomic.Pointer[ipnstate.Status]
	var statusFailure atomic.Bool
	status.Store(subnetStatus("192.168.31.0/24"))
	notifications := make(chan ipn.Notify, 8)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/localapi/v0/status":
			if statusFailure.Load() {
				http.Error(w, "status temporarily unavailable", http.StatusServiceUnavailable)
				return
			}
			json.NewEncoder(w).Encode(status.Load())
		case "/localapi/v0/watch-ipn-bus":
			w.WriteHeader(http.StatusOK)
			w.(http.Flusher).Flush()
			for {
				select {
				case n := <-notifications:
					if err := json.NewEncoder(w).Encode(n); err != nil {
						return
					}
					w.(http.Flusher).Flush()
				case <-r.Context().Done():
					return
				}
			}
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	lc := &local.Client{
		OmitAuth: true,
		Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	session := &subnetSession{enabled: true, cancel: cancel}
	done := make(chan struct{})
	go func() { defer close(done); session.watch(ctx, lc) }()
	t.Cleanup(func() {
		session.close()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("watcher did not stop")
		}
	})
	notifications <- ipn.Notify{InitialStatus: status.Load()}
	ip := netip.MustParseAddr("192.168.31.5")
	waitFor(t, func() bool { return session.contains(ip) })

	// Android does not send NetMap after the initial notification.
	status.Store(subnetStatus("10.2.0.0/16"))
	notifications <- ipn.Notify{PeersChanged: []*tailcfg.Node{{ID: 1}}}
	waitFor(t, func() bool { return session.contains(netip.MustParseAddr("10.2.0.5")) && !session.contains(ip) })
	status.Store(subnetStatus())
	notifications <- ipn.Notify{PeersRemoved: []tailcfg.NodeID{1}}
	waitFor(t, func() bool { return len(session.current.Load().prefixes) == 0 })

	statusFailure.Store(true)
	notifications <- ipn.Notify{PeersChanged: []*tailcfg.Node{{ID: 1}}}
	waitFor(t, func() bool { return session.current.Load().err != "" })
	statusFailure.Store(false)
	status.Store(subnetStatus("192.168.31.0/24"))
	notifications <- ipn.Notify{InitialStatus: status.Load()}
	waitFor(t, func() bool { return session.contains(ip) && session.current.Load().err == "" })
}

func TestConcurrentSubnetReadersAndUpdates(t *testing.T) {
	session := installSubnets(t, "192.168.31.0/24")
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				session.contains(netip.MustParseAddr("192.168.31.5"))
				(&subnetRule{}).Match(&C.Metadata{DstIP: netip.MustParseAddr("192.168.31.5")}, C.RuleMatchHelper{})
			}
		}()
	}
	for j := 0; j < 100; j++ {
		session.update(subnetStatus("192.168.31.0/24"), nil)
		session.setEnabled(j%2 == 0)
	}
	session.close()
	wg.Wait()
}
