package tailnet

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	C "github.com/metacubex/mihomo/constant"
)

const Name = "CMFA-Tailscale"

type Settings struct {
	Enabled      bool          `json:"enabled"`
	AcceptRoutes bool          `json:"acceptRoutes"`
	Forwards     []ForwardRule `json:"forwards"`
}

type ForwardRule struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Enabled     bool   `json:"enabled"`
	LocalPort   int    `json:"localPort"`
	TailnetPort int    `json:"tailnetPort"`
}

func (s Settings) validate() error {
	if len(s.Forwards) > 32 {
		return errors.New("at most 32 port forwarding rules are allowed")
	}
	ids, ports := map[string]bool{}, map[int]bool{}
	for _, rule := range s.Forwards {
		if strings.TrimSpace(rule.ID) == "" || ids[rule.ID] {
			return errors.New("port forwarding rule IDs must be nonempty and unique")
		}
		if strings.TrimSpace(rule.Name) == "" || len([]rune(rule.Name)) > 80 {
			return errors.New("port forwarding names must contain 1 to 80 characters")
		}
		if rule.LocalPort < 1 || rule.LocalPort > 65535 || rule.TailnetPort < 1 || rule.TailnetPort > 65535 {
			return errors.New("port forwarding ports must be between 1 and 65535")
		}
		if ports[rule.TailnetPort] {
			return fmt.Errorf("Tailnet port %d is already used by another rule", rule.TailnetPort)
		}
		ids[rule.ID], ports[rule.TailnetPort] = true, true
	}
	return nil
}

func ReadSettings() (Settings, error) {
	var settings Settings
	data, err := os.ReadFile(C.Path.Resolve("tailscale-settings.json"))
	if errors.Is(err, os.ErrNotExist) {
		settings.Forwards = []ForwardRule{}
		return settings, nil
	}
	if err != nil {
		return settings, err
	}
	err = json.Unmarshal(data, &settings)
	if err != nil {
		return settings, err
	}
	// Keep the previously enabled local service accessible after upgrading.
	var legacy struct {
		MCPEnabled bool `json:"mcpEnabled"`
	}
	if settings.Forwards == nil && json.Unmarshal(data, &legacy) == nil && legacy.MCPEnabled {
		settings.Forwards = []ForwardRule{{ID: "local-8080", Name: "MCP", Enabled: true, LocalPort: 8080, TailnetPort: 8080}}
	}
	if settings.Forwards == nil {
		settings.Forwards = []ForwardRule{}
	}
	return settings, settings.validate()
}

func saveSettings(settings Settings) error {
	if err := settings.validate(); err != nil {
		return err
	}
	data, err := json.Marshal(settings)
	if err != nil {
		return err
	}
	path := C.Path.Resolve("tailscale-settings.json")
	file, err := os.CreateTemp(filepath.Dir(path), ".tailscale-settings-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	if _, err = file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
