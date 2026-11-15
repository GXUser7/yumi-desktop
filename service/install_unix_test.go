//go:build unix

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestServiceCopyIsAtomicExecutableAndRefusesWritableOrSymlinkDirectories(t *testing.T) {
	directory := t.TempDir()
	source := filepath.Join(directory, "source")
	if err := os.WriteFile(source, []byte("authorized binary"), 0o600); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(directory, "protected", "helper")
	if err := stageServiceBinary(source, destination); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(destination)
	if err != nil || string(data) != "authorized binary" {
		t.Fatal("copied the wrong binary")
	}
	info, _ := os.Stat(destination)
	if info.Mode().Perm() != 0o755 {
		t.Fatal("helper is not protected and executable")
	}
	if err := stageServiceBinary(destination, destination); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Dir(destination), 0o777); err != nil {
		t.Fatal(err)
	}
	if err := stageServiceBinary(source, destination); err == nil {
		t.Fatal("accepted a user-writable service directory")
	}
	if err := os.Chmod(filepath.Dir(destination), 0o755); err != nil {
		t.Fatal(err)
	}
	alias := filepath.Join(directory, "alias")
	if err := os.Symlink(filepath.Dir(destination), alias); err != nil {
		t.Fatal(err)
	}
	if err := stageServiceBinary(source, filepath.Join(alias, "helper")); err == nil {
		t.Fatal("accepted a redirected helper directory")
	}
}
