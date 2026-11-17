package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type commandRunner func(string, ...string) (string, error)

const cleanUnixPath = "/usr/sbin:/usr/bin:/sbin:/bin"

func resolveSystemCommand(name string) (string, error) {
	if runtime.GOOS == "windows" || filepath.IsAbs(name) {
		return name, nil
	}
	for _, dir := range []string{"/usr/sbin", "/usr/bin", "/sbin", "/bin"} {
		candidate := filepath.Join(dir, name)
		info, err := os.Stat(candidate)
		if err == nil && !info.IsDir() && info.Mode().Perm()&0o111 != 0 {
			return candidate, nil
		}
	}
	return "", fmt.Errorf("%s: %w", name, exec.ErrNotFound)
}

func runSystemCommand(name string, args ...string) (string, error) {
	resolved, err := resolveSystemCommand(name)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, resolved, args...)
	cmd.SysProcAttr = childProcAttr()
	if runtime.GOOS == "windows" {
		cmd.Env = append(os.Environ(), "LC_ALL=C", "LANG=C")
	} else {
		cmd.Env = []string{"PATH=" + cleanUnixPath, "LC_ALL=C", "LANG=C"}
	}
	out, err := cmd.CombinedOutput()
	text := strings.TrimSpace(string(out))
	if err != nil {
		return text, fmt.Errorf("%s: %s: %w", name, text, err)
	}
	return text, nil
}
