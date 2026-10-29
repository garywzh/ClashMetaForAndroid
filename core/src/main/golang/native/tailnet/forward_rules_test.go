package tailnet

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"reflect"
	"testing"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

func forwardingRule(id string, localPort, tailnetPort int) ForwardRule {
	return ForwardRule{ID: id, Name: id, Enabled: true, LocalPort: localPort, TailnetPort: tailnetPort}
}

func TestForwardRuleValidation(t *testing.T) {
	valid := forwardingRule("web", 8000, 8080)
	tests := map[string][]ForwardRule{
		"empty ID":       {func() ForwardRule { r := valid; r.ID = " "; return r }()},
		"empty name":     {func() ForwardRule { r := valid; r.Name = " "; return r }()},
		"long name":      {func() ForwardRule { r := valid; r.Name = string(bytes.Repeat([]byte("a"), 81)); return r }()},
		"zero local":     {forwardingRule("web", 0, 8080)},
		"negative local": {forwardingRule("web", -1, 8080)},
		"large local":    {forwardingRule("web", 65536, 8080)},
		"zero tailnet":   {forwardingRule("web", 8000, 0)},
		"large tailnet":  {forwardingRule("web", 8000, 65536)},
		"duplicate ID":   {valid, forwardingRule("web", 9000, 9090)},
		"duplicate port": {valid, forwardingRule("other", 9000, 8080)},
	}
	for name, rules := range tests {
		t.Run(name, func(t *testing.T) {
			if (Settings{Forwards: rules}).validate() == nil {
				t.Fatal("invalid rules accepted", rules)
			}
		})
	}
	if err := (Settings{Forwards: []ForwardRule{forwardingRule("low", 1, 1), forwardingRule("high", 65535, 65535)}}).validate(); err != nil {
		t.Fatal("valid boundary ports rejected", err)
	}
	if err := (Settings{Forwards: make([]ForwardRule, 33)}).validate(); err == nil {
		t.Fatal("unbounded rule list accepted")
	}
}

func TestPreviouslyEnabledMCPBecomesOrdinaryForwardRule(t *testing.T) {
	testHome(t)
	path := C.Path.Resolve("tailscale-settings.json")
	if err := os.WriteFile(path, []byte(`{"enabled":true,"mcpEnabled":true,"acceptRoutes":true}`), 0600); err != nil {
		t.Fatal(err)
	}
	settings, err := ReadSettings()
	if err != nil || !settings.Enabled || !settings.AcceptRoutes || len(settings.Forwards) != 1 {
		t.Fatal("existing enabled service was lost", settings, err)
	}
	rule := settings.Forwards[0]
	if !rule.Enabled || rule.LocalPort != 8080 || rule.TailnetPort != 8080 {
		t.Fatal("existing forwarding changed", rule)
	}
	settings.Forwards = []ForwardRule{}
	if err = saveSettings(settings); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	if bytes.Contains(data, []byte("mcpEnabled")) {
		t.Fatal("old feature-specific setting was written back")
	}
	settings, err = ReadSettings()
	if err != nil || len(settings.Forwards) != 0 {
		t.Fatal("deleted old service came back", settings, err)
	}
}

func TestForwardCommandWhileStoppedPreservesOtherSettings(t *testing.T) {
	testHome(t)
	if err := saveSettings(Settings{Enabled: true, AcceptRoutes: true}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { manager.lastError = "" })
	rules := []ForwardRule{forwardingRule("ssh", 8022, 2222)}
	data, _ := json.Marshal(rules)
	status := Command("forwards", string(data))
	if status.Error != "" || !status.Enabled || !status.AcceptRoutes || !reflect.DeepEqual(status.Forwards, rules) || manager.server != nil {
		t.Fatal("editing forwarding changed unrelated settings or started a server", status)
	}
	rules[0].LocalPort = 0
	data, _ = json.Marshal(rules)
	if status = Command("forwards", string(data)); status.Error == "" || status.Forwards[0].LocalPort != 8022 {
		t.Fatal("invalid forwarding overwrote saved settings", status)
	}
	status = Command("forwards", "[]")
	if status.Error != "" || len(status.Forwards) != 0 {
		t.Fatal("cannot delete last forwarding rule", status)
	}
}

func TestGeneralSettingsChangesPreserveForwardRules(t *testing.T) {
	testHome(t)
	rules := []ForwardRule{forwardingRule("web", 8000, 8080)}
	if err := saveSettings(Settings{Enabled: true, AcceptRoutes: true, Forwards: rules}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { manager.lastError = "" })
	status := Command("settings", `{"enabled":false,"acceptRoutes":false}`)
	if status.Error != "" || status.Enabled || status.AcceptRoutes || !reflect.DeepEqual(status.Forwards, rules) {
		t.Fatal("changing general settings lost forwards or ignored false toggles", status)
	}
}

func testForwardListener(t *testing.T, network, address string) (net.Listener, error) {
	t.Helper()
	if network != "tcp" {
		t.Fatal("forwarding changed protocol", network)
	}
	return net.Listen(network, "127.0.0.1:0")
}

func TestForwardReconciliationKeepsUnchangedConnectionsAndReleasesPorts(t *testing.T) {
	r := &runtime{}
	t.Cleanup(r.stopLocked)
	first, second := forwardingRule("first", 8000, 8080), forwardingRule("second", 9000, 9090)
	var requested []string
	listen := func(network, address string) (net.Listener, error) {
		requested = append(requested, address)
		return testForwardListener(t, network, address)
	}
	r.reconcileForwards([]ForwardRule{first, second}, listen)
	f1, f2 := r.forwards[first.ID], r.forwards[second.ID]
	if f1 == nil || f2 == nil || !reflect.DeepEqual(requested, []string{":8080", ":9090"}) {
		t.Fatal("rules did not bind independent Tailnet ports", requested)
	}
	first.Name = "renamed"
	r.reconcileForwards([]ForwardRule{second, first}, listen)
	if r.forwards[first.ID] != f1 || r.forwards[second.ID] != f2 || len(requested) != 2 {
		t.Fatal("rename or reorder interrupted existing listeners")
	}
	first.LocalPort = 8022
	r.reconcileForwards([]ForwardRule{first, second}, listen)
	if r.forwards[first.ID] == f1 || r.forwards[second.ID] != f2 {
		t.Fatal("changed rule was not replaced independently")
	}
	if conn, err := net.DialTimeout("tcp", f1.listener.Addr().String(), time.Second); err == nil {
		conn.Close()
		t.Fatal("old listener still accepts connections")
	}
	first.Enabled = false
	r.reconcileForwards([]ForwardRule{first}, listen)
	if len(r.forwards) != 0 {
		t.Fatal("disabled and removed rules kept listening")
	}
}

func TestForwardListenFailureIsIsolatedAndRetryable(t *testing.T) {
	r := &runtime{}
	t.Cleanup(r.stopLocked)
	rules := []ForwardRule{forwardingRule("busy", 8000, 8080), forwardingRule("working", 9000, 9090)}
	r.reconcileForwards(rules, func(network, address string) (net.Listener, error) {
		if address == ":8080" {
			return nil, errors.New("address in use")
		}
		return testForwardListener(t, network, address)
	})
	working := r.forwards["working"]
	if r.forwards["busy"] != nil || working == nil || r.forwardErrors["busy"] == "" {
		t.Fatal("one failed listener disrupted another rule")
	}
	r.reconcileForwards(rules, func(network, address string) (net.Listener, error) {
		return testForwardListener(t, network, address)
	})
	if len(r.forwardErrors) != 0 || r.forwards["busy"] == nil || r.forwards["working"] != working {
		t.Fatal("failed listener could not recover independently")
	}
}

func TestForwarderUsesConfiguredLocalPortForBinaryTCP(t *testing.T) {
	local, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer local.Close()
	serverDone := make(chan struct{})
	go func() {
		defer close(serverDone)
		conn, err := local.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(3 * time.Second))
		io.Copy(conn, conn)
	}()
	r := &runtime{}
	t.Cleanup(r.stopLocked)
	r.reconcileForwards([]ForwardRule{forwardingRule("binary", local.Addr().(*net.TCPAddr).Port, 2222)}, func(network, address string) (net.Listener, error) {
		return testForwardListener(t, network, address)
	})
	conn, err := net.Dial("tcp", r.forwards["binary"].listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	payload := []byte{0, 255, 128, 4, 13, 10, 0}
	if _, err = conn.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err = io.ReadFull(conn, got); err != nil || !bytes.Equal(got, payload) {
		t.Fatal("TCP payload was changed or sent to the wrong local port", got, err)
	}
	r.stopLocked()
	if len(r.forwards) != 0 {
		t.Fatal("stopping VPN retained listeners")
	}
	if _, err = conn.Read(got); err == nil {
		t.Fatal("stopping VPN retained an active connection")
	}
	select {
	case <-serverDone:
	case <-time.After(3 * time.Second):
		t.Fatal("stopping VPN leaked local connection")
	}
}
