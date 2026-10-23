package tailnet

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/metacubex/mihomo/component/ca"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/tsnet"
)

type Status struct {
	Settings
	State        string   `json:"state"`
	AuthURL      string   `json:"authUrl"`
	IPs          []string `json:"ips"`
	Health       []string `json:"health"`
	MCPListening bool     `json:"mcpListening"`
	Error        string   `json:"error"`
}

type runtime struct {
	mu        sync.Mutex
	server    *tsnet.Server
	forward   *forwarder
	lastError string
}

var manager runtime
var configurationMu sync.Mutex

// Serialize profile application with settings changes so placeholder routes can
// never be applied after the integration has been disabled.
func WithConfiguration(apply func() error) error {
	configurationMu.Lock()
	defer configurationMu.Unlock()
	return apply()
}

func (r *runtime) startLocked() error {
	if r.server != nil {
		return nil
	}
	d := dialer.NewDialer()
	s := &tsnet.Server{
		Dir:          C.Path.Resolve("tailscale-native"),
		Hostname:     "android-clash",
		SystemDialer: d.DialContext,
		SystemPacketListener: func(ctx context.Context, network, address string) (net.PacketConn, error) {
			return d.ListenPacket(ctx, network, address, netip.AddrPort{})
		},
		LookupHook: func(ctx context.Context, host string) ([]netip.Addr, error) {
			return resolver.LookupIPWithResolver(ctx, host, resolver.ProxyServerHostResolver)
		},
		ExtraRootCAs: ca.GetCertPool(),
		UserLogf:     func(format string, args ...any) { log.Infoln("[Tailscale] "+format, args...) },
		Logf:         func(format string, args ...any) { log.Debugln("[Tailscale] "+format, args...) },
	}
	if err := s.Start(); err != nil {
		return err
	}
	lc, err := s.LocalClient()
	if err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err = lc.EditPrefs(ctx, &ipn.MaskedPrefs{
			Prefs:          ipn.Prefs{WantRunning: true, RouteAll: false},
			WantRunningSet: true,
			RouteAllSet:    true,
		})
	}
	if err != nil {
		s.Close()
		return err
	}
	r.server = s
	r.lastError = ""
	return nil
}

func (r *runtime) stopLocked() {
	if r.forward != nil {
		r.forward.Close()
		r.forward = nil
	}
	if r.server != nil {
		r.server.Close()
		r.server = nil
	}
	r.lastError = ""
}

func Stop() {
	configurationMu.Lock()
	defer configurationMu.Unlock()
	manager.mu.Lock()
	defer manager.mu.Unlock()
	manager.stopLocked()
}

// Never start a server from a stale profile adapter after the VPN has stopped.
func currentServer() (*tsnet.Server, error) {
	manager.mu.Lock()
	defer manager.mu.Unlock()
	if manager.server == nil {
		return nil, errors.New("Tailscale is not running; start Clash first")
	}
	return manager.server, nil
}

func Command(command, content string) Status {
	configurationMu.Lock()
	defer configurationMu.Unlock()
	manager.mu.Lock()
	defer manager.mu.Unlock()
	var err error
	if command != "status" {
		manager.lastError = ""
	}
	switch command {
	case "settings":
		var settings Settings
		if err = json.Unmarshal([]byte(content), &settings); err == nil {
			err = saveSettings(settings)
			if err == nil && !settings.Enabled {
				manager.stopLocked()
			}
		}
	case "login", "logout":
		if manager.server == nil {
			err = errors.New("start Clash with Tailscale enabled first")
			break
		}
		lc, clientErr := manager.server.LocalClient()
		if err = clientErr; err != nil {
			break
		}
		ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
		defer cancel()
		if command == "login" {
			err = lc.StartLoginInteractive(ctx)
			if err == nil {
				settings, readErr := ReadSettings()
				err = readErr
				if err == nil {
					manager.configureForwardLocked(settings.MCPEnabled)
				}
			}
		} else {
			manager.configureForwardLocked(false)
			err = lc.Logout(ctx)
		}
	case "status":
	default:
		err = errors.New("unknown Tailscale command")
	}
	if err != nil {
		manager.lastError = err.Error()
	}
	return manager.statusLocked()
}

func (r *runtime) statusLocked() Status {
	settings, err := ReadSettings()
	result := Status{Settings: settings, State: "Stopped", IPs: []string{}, Health: []string{}, Error: r.lastError}
	if err != nil {
		result.Error = err.Error()
	}
	if r.server == nil {
		return result
	}
	result.MCPListening = r.forward != nil
	lc, err := r.server.LocalClient()
	if err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		status, statusErr := lc.StatusWithoutPeers(ctx)
		err = statusErr
		if err == nil {
			result.State = status.BackendState
			result.AuthURL = status.AuthURL
			result.Health = status.Health
			if result.Health == nil {
				result.Health = []string{}
			}
			for _, ip := range status.TailscaleIPs {
				result.IPs = append(result.IPs, ip.String())
			}
		}
	}
	if err != nil {
		result.Error = err.Error()
	}
	return result
}
