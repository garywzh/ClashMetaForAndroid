package taildrop

import (
	"encoding/json"
	"io"
	"net/url"
	"path/filepath"
	"strings"
	"sync"

	"github.com/metacubex/http"
	"github.com/metacubex/tailscale/ipn/ipnlocal"
	"github.com/metacubex/tailscale/ipn/localapi"
)

var stagingRoots sync.Map

type incomingDetail struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Size     int64  `json:"size"`
	Received int64  `json:"received"`
	Done     bool   `json:"done"`
}

// EnableStagingRoot opts a specific embedded node into Taildrop before Start.
// Other tsnet nodes created by subscription adapters must retain their behavior.
func EnableStagingRoot(root string) error {
	root, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	stagingRoots.Store(root, struct{}{})
	return nil
}

func init() {
	localapi.Register("file-incoming", func(h *localapi.Handler, w http.ResponseWriter, r *http.Request) {
		if !h.PermitRead || r.Method != "GET" {
			http.Error(w, "file access denied", http.StatusForbidden)
			return
		}
		ext, ok := ipnlocal.GetExt[*Extension](h.LocalBackend())
		if !ok {
			http.Error(w, "Taildrop unavailable", http.StatusServiceUnavailable)
			return
		}
		if mgr := ext.manager(); mgr != nil {
			files := []incomingDetail{}
			mgr.incomingFiles.All()(func(key incomingFileKey, f *incomingFile) bool {
				f.mu.Lock()
				files = append(files, incomingDetail{ID: string(key.id) + "/" + key.name, Name: key.name, Size: f.size, Received: f.copied, Done: f.done})
				f.mu.Unlock()
				return true
			})
			json.NewEncoder(w).Encode(files)
		} else {
			io.WriteString(w, "[]")
		}
	})
	localapi.Register("file-cancel/", func(h *localapi.Handler, w http.ResponseWriter, r *http.Request) {
		if !h.PermitWrite || r.Method != "POST" {
			http.Error(w, "file access denied", http.StatusForbidden)
			return
		}
		ext, ok := ipnlocal.GetExt[*Extension](h.LocalBackend())
		if !ok {
			http.Error(w, "Taildrop unavailable", http.StatusServiceUnavailable)
			return
		}
		id, err := url.PathUnescape(strings.TrimPrefix(r.URL.EscapedPath(), "/localapi/v0/file-cancel/"))
		if err != nil {
			http.Error(w, "invalid filename", http.StatusBadRequest)
			return
		}
		if err := ext.manager().cancelIncoming(id); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		io.WriteString(w, "{}")
	})
}

func (m *manager) cancelIncoming(id string) error {
	sender, name, valid := strings.Cut(id, "/")
	if !valid || validateBaseName(name) != nil {
		return ErrInvalidFileName
	}
	if m == nil {
		return nil
	}
	if f, ok := m.incomingFiles.Load(incomingFileKey{clientID(sender), name}); ok {
		f.mu.Lock()
		cancel := f.cancel
		f.mu.Unlock()
		if cancel != nil {
			cancel()
		}
	}
	return nil
}
