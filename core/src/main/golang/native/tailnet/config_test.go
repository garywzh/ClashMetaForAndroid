package tailnet

import (
	"net/netip"
	"os"
	"reflect"
	"testing"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	_ "github.com/metacubex/mihomo/hub/executor" // supplies config's temporaryUpdateGeneral linkname
	"github.com/metacubex/tailscale/tsnet"
)

func rawConfig(t *testing.T) *config.RawConfig {
	t.Helper()
	cfg, err := config.UnmarshalRawConfig([]byte(`
proxies:
  - {name: Existing, type: direct}
rules:
  - DOMAIN,example.org,Existing
  - MATCH,DIRECT
dns:
  enable: true
  nameserver: [1.1.1.1]
  nameserver-policy:
    '+.example.org': [9.9.9.9]
    '+.ts.net': [8.8.8.8]
`))
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func testHome(t *testing.T) {
	t.Helper()
	old := C.Path.HomeDir()
	C.SetHomeDir(t.TempDir())
	t.Cleanup(func() { C.SetHomeDir(old) })
}

func TestPatchPreservesSubscription(t *testing.T) {
	cfg := rawConfig(t)
	originalRules := append([]string{}, cfg.Rule...)
	originalProxy := cfg.Proxy[0]
	if err := Patch(cfg, Settings{Enabled: true}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg.Rule[3:], originalRules) || !reflect.DeepEqual(cfg.Proxy[0], originalProxy) {
		t.Fatal("existing proxies or rule order changed")
	}
	if len(cfg.Proxy) != 2 || cfg.Proxy[1]["type"] != "direct" {
		t.Fatal("validation must use a side-effect-free placeholder")
	}
	if cfg.DNS.NameServerPolicy.Oldest().Key != "+.ts.net" {
		t.Fatal("MagicDNS must precede subscription DNS policies")
	}
	if value, _ := cfg.DNS.NameServerPolicy.Get("+.example.org"); !reflect.DeepEqual(value, []any{"9.9.9.9"}) {
		t.Fatalf("subscription DNS policy changed: %#v", value)
	}
	if manager.server != nil {
		t.Fatal("patching started a server")
	}
}

func TestDisabledPatchIsNoOp(t *testing.T) {
	cfg := rawConfig(t)
	rules, proxies, policies := cfg.Rule, cfg.Proxy, cfg.DNS.NameServerPolicy
	if err := Patch(cfg, Settings{}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg.Rule, rules) || !reflect.DeepEqual(cfg.Proxy, proxies) || cfg.DNS.NameServerPolicy != policies {
		t.Fatal("disabled integration modified the subscription")
	}
}

func TestReservedProxyFailsBeforeModification(t *testing.T) {
	cfg := rawConfig(t)
	cfg.Proxy[0]["name"] = Name
	if err := Patch(cfg, Settings{Enabled: true}); err == nil {
		t.Fatal("reserved name collision was accepted")
	}
	if len(cfg.Rule) != 2 || len(cfg.Proxy) != 1 {
		t.Fatal("collision partially modified the profile")
	}
}

func TestPatchedConfigParsesWithoutStartingServer(t *testing.T) {
	cfg := rawConfig(t)
	if err := Patch(cfg, Settings{Enabled: true}); err != nil {
		t.Fatal(err)
	}
	parsed, err := config.ParseRawConfig(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if parsed.Proxies[Name] == nil || manager.server != nil {
		t.Fatal("validation started a server or lost the placeholder")
	}
}

func TestSettingsPersistPrivatelyAndDefaultOff(t *testing.T) {
	testHome(t)
	settings, err := ReadSettings()
	if err != nil || settings.Enabled || len(settings.Forwards) != 0 {
		t.Fatal("new installations must default to off", settings, err)
	}
	want := Settings{Enabled: true, Forwards: []ForwardRule{{ID: "web", Name: "Web", Enabled: true, LocalPort: 8000, TailnetPort: 8080}}}
	if err = saveSettings(want); err != nil {
		t.Fatal(err)
	}
	got, err := ReadSettings()
	if err != nil || !reflect.DeepEqual(got, want) {
		t.Fatal("settings did not round trip", got, err)
	}
	info, err := os.Stat(C.Path.Resolve("tailscale-settings.json"))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("settings file permissions are not private", err)
	}
}

func TestCorruptSettingsAreReported(t *testing.T) {
	testHome(t)
	if err := os.WriteFile(C.Path.Resolve("tailscale-settings.json"), []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadSettings(); err == nil {
		t.Fatal("corrupt settings silently accepted")
	}
}

func TestProfileAdapterDoesNotOwnServer(t *testing.T) {
	r := &runtime{server: &tsnet.Server{}}
	same := r.server
	if err := r.startLocked(); err != nil || r.server != same {
		t.Fatal("profile reload replaced the server", err)
	}
	if err := newOutbound().Close(); err != nil {
		t.Fatal(err)
	}
	if r.server != same {
		t.Fatal("closing a profile adapter closed the VPN's server")
	}
}

func TestProfileReloadKeepsServerAndProxyReferences(t *testing.T) {
	testHome(t)
	if err := saveSettings(Settings{Enabled: true}); err != nil {
		t.Fatal(err)
	}
	// A pre-existing server avoids network activity while exercising live attachment.
	server := &tsnet.Server{}
	manager.server = server
	t.Cleanup(func() { manager.server = nil })
	for i := 0; i < 2; i++ {
		raw := rawConfig(t)
		if err := Patch(raw, Settings{Enabled: true}); err != nil {
			t.Fatal(err)
		}
		cfg, err := config.ParseRawConfig(raw)
		if err != nil {
			t.Fatal(err)
		}
		placeholder := cfg.Proxies[Name]
		if err = Attach(cfg); err != nil {
			t.Fatal(err)
		}
		if cfg.Proxies[Name] != placeholder || cfg.Proxies[Name].Type() != C.Tailscale {
			t.Fatal("attachment broke proxy references or retained the direct placeholder")
		}
		if _, ok := cfg.Rules[0].(*subnetRule); !ok {
			t.Fatal("live subnet rule must precede subscription rules after each reload")
		}
		cfg.Proxies[Name].Close()
		if manager.server != server {
			t.Fatal("profile lifecycle replaced the server")
		}
	}
}

func TestTailnetAdapterRejectsPublicDestinations(t *testing.T) {
	for _, address := range []string{"1.1.1.1", "192.168.1.1", "2001:4860:4860::8888"} {
		if validateDestination(netip.MustParseAddr(address)) == nil {
			t.Fatal("non-Tailnet destination accepted", address)
		}
	}
	for _, address := range []string{"100.93.42.52", "::ffff:100.93.42.52", "fd7a:115c:a1e0::1"} {
		if err := validateDestination(netip.MustParseAddr(address)); err != nil {
			t.Fatal("Tailnet destination rejected", address, err)
		}
	}
}

func TestStoppedAdapterCannotStartServer(t *testing.T) {
	if _, err := currentServer(); err == nil {
		t.Fatal("stopped integration returned a server")
	}
	if manager.server != nil {
		t.Fatal("querying a stopped adapter started the server")
	}
}

func TestLoginWhileStoppedIsActionable(t *testing.T) {
	testHome(t)
	status := Command("login", "")
	if status.Error == "" || status.State != "Stopped" || manager.server != nil {
		t.Fatal("stopped login must not start a hidden Tailscale connection", status)
	}
	t.Cleanup(func() { manager.lastError = "" })
}
