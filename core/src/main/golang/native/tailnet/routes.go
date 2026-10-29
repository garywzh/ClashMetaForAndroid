package tailnet

import (
	"context"
	"net/netip"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/tailscale/client/local"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/ipn/ipnstate"
)

type subnetSnapshot struct {
	prefixes []netip.Prefix
	err      string
}

// Each server owns a separate session, so a late notification from an old VPN
// cannot republish routes after stop/start. Readers only see immutable snapshots.
type subnetSession struct {
	mu      sync.Mutex
	enabled bool
	closed  bool
	known   []netip.Prefix
	cancel  context.CancelFunc
	current atomic.Pointer[subnetSnapshot]
}

func (s *subnetSession) publishLocked(err string) {
	snapshot := &subnetSnapshot{err: err}
	if s.enabled && !s.closed {
		snapshot.prefixes = s.known
	}
	s.current.Store(snapshot)
}

func (s *subnetSession) setEnabled(enabled bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.enabled = enabled
	s.publishLocked("")
}

func (s *subnetSession) update(status *ipnstate.Status, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.known = nil
	if err != nil {
		s.publishLocked(err.Error())
		return
	}
	if status != nil && status.BackendState == "Running" {
		s.known = approvedSubnets(status)
	}
	s.publishLocked("")
}

func (s *subnetSession) close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	s.known = nil
	s.publishLocked("")
	if s.cancel != nil {
		s.cancel()
	}
}

func approvedSubnets(status *ipnstate.Status) []netip.Prefix {
	unique := make(map[netip.Prefix]struct{})
	for _, peer := range status.Peer {
		if peer == nil || peer.Expired || peer.AllowedIPs == nil {
			continue
		}
		// AllowedIPs comes from the control plane, not Hostinfo.RoutableIPs
		// (which can contain unapproved advertisements). Tailscale handles HA.
		for _, prefix := range peer.AllowedIPs.AsSlice() {
			if !prefix.IsValid() || prefix.Bits() == 0 || prefix.Addr().Is4In6() {
				continue
			}
			prefix = prefix.Masked()
			if (prefix.Bits() >= tailnetIPv4.Bits() && tailnetIPv4.Contains(prefix.Addr())) ||
				(prefix.Bits() >= tailnetIPv6.Bits() && tailnetIPv6.Contains(prefix.Addr())) {
				continue
			}
			if !prefix.Addr().IsGlobalUnicast() {
				continue
			}
			unique[prefix] = struct{}{}
		}
	}
	prefixes := make([]netip.Prefix, 0, len(unique))
	for prefix := range unique {
		prefixes = append(prefixes, prefix)
	}
	sort.Slice(prefixes, func(i, j int) bool {
		if n := prefixes[i].Addr().Compare(prefixes[j].Addr()); n != 0 {
			return n < 0
		}
		return prefixes[i].Bits() < prefixes[j].Bits()
	})
	return prefixes
}

func (s *subnetSession) contains(ip netip.Addr) bool {
	ip = ip.Unmap().WithZone("")
	if !ip.IsGlobalUnicast() {
		return false
	}
	if snapshot := s.current.Load(); snapshot != nil {
		for _, prefix := range snapshot.prefixes {
			if prefix.Contains(ip) {
				return true
			}
		}
	}
	return false
}

func (s *subnetSession) refresh(ctx context.Context, lc *local.Client) error {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	status, err := lc.Status(ctx)
	s.update(status, err)
	return err
}

func (s *subnetSession) watch(ctx context.Context, lc *local.Client) {
	for ctx.Err() == nil {
		err := s.watchOnce(ctx, lc)
		if ctx.Err() != nil {
			return
		}
		s.update(nil, err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(2 * time.Second):
		}
	}
}

func (s *subnetSession) watchOnce(ctx context.Context, lc *local.Client) error {
	watcher, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyInitialStatus|ipn.NotifyInitialPrefs|ipn.NotifyPeerChanges)
	if err != nil {
		return err
	}
	defer watcher.Close()
	for {
		n, err := watcher.Next()
		if err != nil {
			return err
		}
		// Full netmaps after the initial notification are Windows-only in this
		// Tailscale version. Peer-change notifications also work on Android.
		if n.State != nil || n.InitialStatus != nil || n.Prefs != nil || n.SelfChange != nil ||
			len(n.PeersChanged) != 0 || len(n.PeersRemoved) != 0 || len(n.PeerChangedPatch) != 0 {
			if err := s.refresh(ctx, lc); err != nil {
				return err
			}
		}
	}
}

type subnetRule struct{}

func (*subnetRule) RuleType() C.RuleType    { return C.IPCIDR }
func (*subnetRule) Adapter() string         { return Name }
func (*subnetRule) Payload() string         { return "Tailscale subnet routes" }
func (*subnetRule) ProviderNames() []string { return nil }
func (*subnetRule) Match(metadata *C.Metadata, helper C.RuleMatchHelper) (bool, string) {
	session := manager.subnets.Load()
	if session == nil {
		return false, Name
	}
	if snapshot := session.current.Load(); snapshot == nil || len(snapshot.prefixes) == 0 {
		return false, Name
	}
	if !metadata.Resolved() && helper.ResolveIP != nil {
		helper.ResolveIP()
	}
	return session.contains(metadata.DstIP), Name
}

var _ C.Rule = (*subnetRule)(nil)
