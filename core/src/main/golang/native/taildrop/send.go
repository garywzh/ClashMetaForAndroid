// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package taildrop

import (
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/metacubex/tailscale/envknob"
	"github.com/metacubex/tailscale/ipn"
	"github.com/metacubex/tailscale/tstime"
	"github.com/metacubex/tailscale/version/distro"
)

type incomingFileKey struct {
	id   clientID
	name string // e.g., "foo.jpeg"
}

type incomingFile struct {
	clock  tstime.DefaultClock
	cancel func()

	started        time.Time
	size           int64     // or -1 if unknown; never 0
	w              io.Writer // underlying writer
	sendFileNotify func()    // called when done
	partialPath    string    // non-empty in direct mode
	finalPath      string    // not used in direct mode

	mu         sync.Mutex
	copied     int64
	done       bool
	lastNotify time.Time
}

func (f *incomingFile) Write(p []byte) (n int, err error) {
	n, err = f.w.Write(p)

	var needNotify bool
	defer func() {
		if needNotify {
			f.sendFileNotify()
		}
	}()
	if n > 0 {
		f.mu.Lock()
		defer f.mu.Unlock()
		f.copied += int64(n)
		now := f.clock.Now()
		if f.lastNotify.IsZero() || now.Sub(f.lastNotify) > time.Second {
			f.lastNotify = now
			needNotify = true
		}
	}
	return n, err
}

// PutFile stores a file into [manager.Dir] from a given client id.
// The baseName must be a base filename without any slashes.
// The length is the expected length of content to read from r,
// it may be negative to indicate that it is unknown.
// It returns the length of the entire file.
//
// If there is a failure reading from r, then the partial file is not deleted
// for some period of time. The [manager.PartialFiles] and [manager.HashPartialFile]
// methods may be used to list all partial files and to compute the hash for a
// specific partial file. This allows the client to determine whether to resume
// a partial file. While resuming, PutFile may be called again with a non-zero
// offset to specify where to resume receiving data at.
func (m *manager) PutFile(id clientID, baseName string, r io.Reader, offset, length int64) (fileLength int64, err error) {

	switch {
	case m == nil || m.opts.fileOps == nil:
		return 0, ErrNoTaildrop
	case !envknob.CanTaildrop():
		return 0, ErrNoTaildrop
	case distro.Get() == distro.Unraid && !m.opts.DirectFileMode:
		return 0, ErrNotAccessible
	}

	declaredSize := length
	if offset < 0 {
		return 0, fmt.Errorf("invalid negative offset")
	}
	if length >= 0 {
		declaredSize += offset
		if declaredSize < length {
			return 0, fmt.Errorf("invalid transfer size")
		}
	}

	if err := validateBaseName(baseName); err != nil {
		return 0, err
	}
	if m.receiveSlots != nil {
		select {
		case m.receiveSlots <- struct{}{}:
			defer func() { <-m.receiveSlots }()
		default:
			return 0, fmt.Errorf("Taildrop: too many incoming transfers (maximum 4)")
		}
	}

	// Claim the transfer before touching its partial file. A duplicate request
	// must not truncate or close the writer belonging to an active upload.
	inFileKey := incomingFileKey{id, baseName}
	inFile, loaded := m.incomingFiles.LoadOrInit(inFileKey, func() *incomingFile {
		inFile := &incomingFile{
			clock:          m.opts.Clock,
			started:        m.opts.Clock.Now(),
			size:           declaredSize,
			sendFileNotify: m.opts.SendFileNotify,
		}
		if closer, ok := r.(io.Closer); ok {
			inFile.cancel = func() { closer.Close() }
		}
		return inFile
	})

	if loaded {
		return 0, ErrFileExists
	}
	defer m.incomingFiles.Delete(inFileKey)
	partialName := baseName + id.partialSuffix()
	m.deleter.Remove(partialName)
	wc, partialPath, err := m.opts.fileOps.OpenWriter(partialName, offset, 0o600)
	if err != nil {
		return 0, m.redactAndLogError("Create", err)
	}
	defer func() {
		wc.Close()
		if err != nil {
			m.deleter.Insert(partialName)
		}
	}()
	inFile.mu.Lock()
	inFile.w = wc
	if m.opts.DirectFileMode {
		inFile.partialPath = partialPath
	}
	inFile.copied = offset
	inFile.mu.Unlock()

	// Record that we have started to receive at least one file.
	// This is used by the deleter upon a cold-start to scan the directory
	// for any files that need to be deleted.
	if st := m.opts.State; st != nil {
		if b, _ := st.ReadState(ipn.TaildropReceivedKey); len(b) == 0 {
			if werr := st.WriteState(ipn.TaildropReceivedKey, []byte{1}); werr != nil {
				m.opts.Logf("WriteState error: %v", werr) // non-fatal error
			}
		}
	}

	// Copy the contents of the file to the writer.
	copyLength, err := io.Copy(inFile, r)
	if err != nil {
		return 0, m.redactAndLogError("Copy", err)
	}
	if length >= 0 && copyLength != length {
		return 0, m.redactAndLogError("Copy", fmt.Errorf("copied %d bytes; expected %d", copyLength, length))
	}
	if err := wc.Close(); err != nil {
		return 0, m.redactAndLogError("Close", err)
	}

	fileLength = offset + copyLength

	// 6) Finalize (rename/move) the partial into place via FileOps.Rename
	finalPath, err := m.opts.fileOps.Rename(partialPath, baseName)
	if err != nil {
		return 0, m.redactAndLogError("Rename", err)
	}
	inFile.mu.Lock()
	inFile.finalPath = finalPath
	inFile.done = true
	inFile.mu.Unlock()

	m.totalReceived.Add(1)
	m.opts.SendFileNotify()
	return fileLength, nil
}

func (m *manager) redactAndLogError(stage string, err error) error {
	err = redactError(err)
	m.opts.Logf("put %s error: %v", stage, err)
	return err
}
