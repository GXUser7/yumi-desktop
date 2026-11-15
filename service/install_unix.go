//go:build unix

package main

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"syscall"
)

func installedServicePath() string {
	if isDarwin() {
		return "/Library/PrivilegedHelperTools/app.yumi.desktop.tunnel"
	}
	return "/usr/local/libexec/yumi-desktop/yumi-service"
}

// A root daemon must not keep executing a binary from a user-writable .app bundle. Copy the
// authorized binary into a protected location, and register that stable path with the manager.
func stageServiceBinary(source, destination string) error {
	directory := filepath.Dir(destination)
	if err := os.MkdirAll(directory, 0o755); err != nil {
		return err
	}
	info, err := os.Lstat(directory)
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errors.New("service directory must not be a symlink")
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !ok || owner.Uid != uint32(os.Geteuid()) || info.Mode().Perm()&0o022 != 0 {
		return errors.New("service directory is writable by another account")
	}
	if source == destination {
		return os.Chmod(destination, 0o755)
	}
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.CreateTemp(directory, ".yumi-service-*")
	if err != nil {
		return err
	}
	defer os.Remove(output.Name())
	_, err = io.Copy(output, input)
	if err == nil {
		err = output.Chmod(0o755)
	}
	if err == nil {
		err = output.Sync()
	}
	closeErr := output.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(output.Name(), destination)
}
