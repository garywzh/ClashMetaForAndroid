package tailnet

import (
	"fmt"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/common/orderedmap"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/dns"
)

// Patch only changes the in-memory configuration. Validation must never start tsnet.
func Patch(cfg *config.RawConfig, settings Settings) error {
	if !settings.Enabled {
		return nil
	}
	for _, proxy := range cfg.Proxy {
		if proxy["name"] == Name {
			return fmt.Errorf("proxy name %q is reserved for the Tailscale integration", Name)
		}
	}
	// A placeholder lets Mihomo validate rule targets without constructing a server.
	cfg.Proxy = append(cfg.Proxy, map[string]any{"name": Name, "type": "direct"})
	cfg.Rule = append([]string{
		"IP-CIDR,100.64.0.0/10," + Name + ",no-resolve",
		"IP-CIDR6,fd7a:115c:a1e0::/48," + Name + ",no-resolve",
		"DOMAIN-SUFFIX,ts.net," + Name,
	}, cfg.Rule...)
	if cfg.DNS.NameServerPolicy == nil {
		cfg.DNS.NameServerPolicy = orderedmap.New[string, any]()
	}
	// Put MagicDNS ahead of subscription policies, including broad wildcard policies.
	policies := orderedmap.New[string, any]()
	policies.Set("+.ts.net", []string{"tailscale://" + Name})
	for pair := cfg.DNS.NameServerPolicy.Oldest(); pair != nil; pair = pair.Next() {
		if pair.Key != "+.ts.net" {
			policies.Set(pair.Key, pair.Value)
		}
	}
	cfg.DNS.NameServerPolicy = policies
	return nil
}

func Attach(cfg *config.Config) error {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	settings, err := ReadSettings()
	if err != nil {
		return err
	}
	if !settings.Enabled {
		manager.stopLocked()
		return nil
	}
	proxy, ok := cfg.Proxies[Name].(*adapter.Proxy)
	if !ok {
		return fmt.Errorf("missing Tailscale proxy placeholder")
	}
	if err = manager.startLocked(); err != nil {
		manager.lastError = err.Error()
		return err
	}
	manager.configureForwardsLocked(settings.Forwards)
	// Adapter lifetimes belong to profiles; the underlying server belongs to the VPN.
	// Keep the wrapper identity: proxy groups/providers also hold references to it.
	proxy.ProxyAdapter = newOutbound()
	// A live rule reads the current snapshot; route changes need no profile
	// reload and always precede subscription LAN/DIRECT rules.
	cfg.Rules = append([]C.Rule{&subnetRule{}}, cfg.Rules...)
	return nil
}

func init() {
	dns.RegisterTailscaleDnsClient(Name, dnsTransport{})
}
