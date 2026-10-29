package tailnet

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"

	C "github.com/metacubex/mihomo/constant"
)

const Name = "CMFA-Tailscale"

type Settings struct {
	Enabled      bool `json:"enabled"`
	MCPEnabled   bool `json:"mcpEnabled"`
	AcceptRoutes bool `json:"acceptRoutes"`
}

func ReadSettings() (Settings, error) {
	var settings Settings
	data, err := os.ReadFile(C.Path.Resolve("tailscale-settings.json"))
	if errors.Is(err, os.ErrNotExist) {
		return settings, nil
	}
	if err != nil {
		return settings, err
	}
	err = json.Unmarshal(data, &settings)
	return settings, err
}

func saveSettings(settings Settings) error {
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
