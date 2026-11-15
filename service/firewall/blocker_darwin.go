//go:build darwin

package firewall

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var platformPF = newPlatformPF()

func newPlatformPF() *pfController {
	boot, _ := exec.Command("sysctl", "-n", "kern.boottime").Output()
	id := regexp.MustCompile(`sec\s*=\s*([0-9]+)`).FindStringSubmatch(string(boot))
	bootID := fmt.Sprintf("unknown-%d", time.Now().UnixNano())
	if len(id) == 2 {
		bootID = id[1]
	}
	return &pfController{run: runPF, path: pfStatePath(), bootID: bootID}
}

func pfStatePath() string {
	dir := os.Getenv("YUMI_STATE_DIR")
	if dir == "" {
		dir = "/Library/Application Support/Yumi Desktop"
	}
	return filepath.Join(dir, "pf-state.json")
}
func runPF(input string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "pfctl", args...)
	cmd.Stdin = strings.NewReader(input)
	cmd.Env = append(os.Environ(), "LC_ALL=C")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("pfctl: %s: %w", strings.TrimSpace(string(out)), err)
	}
	return string(out), nil
}
func Enable(ref TunRef, permitLAN bool) (Firewall, error) { return platformPF.enable(ref, permitLAN) }
func CleanupLeftovers() error                             { return platformPF.recover() }
