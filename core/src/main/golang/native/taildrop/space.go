//go:build unix

package taildrop

import (
	"fmt"
	"golang.org/x/sys/unix"
	"os"
)

const diskReserve = 64 << 20

func checkFreeSpace(dir string, writing int) error {
	var stat unix.Statfs_t
	if err := unix.Statfs(dir, &stat); err != nil {
		return err
	}
	if uint64(stat.Bavail)*uint64(stat.Bsize) < diskReserve+uint64(writing) {
		return fmt.Errorf("Taildrop: insufficient storage (keep 64 MiB free)")
	}
	return nil
}

type spaceWriter struct {
	*os.File
	dir string
}

func (w spaceWriter) Write(p []byte) (int, error) {
	if err := checkFreeSpace(w.dir, len(p)); err != nil {
		return 0, err
	}
	return w.File.Write(p)
}
