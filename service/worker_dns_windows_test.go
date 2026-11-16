//go:build windows

package main

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"testing"
	"time"

	"github.com/GXUser7/yumi-desktop/service/firewall"
)

type testDNSGuard struct{ closed int }

func (g *testDNSGuard) Close() { g.closed++ }

func TestDNSProtectionCoversFirstConnectResumeAndWorkerExit(t *testing.T) {
	var guards []*testDNSGuard
	flushes := 0
	p := tunnelDNSProtection{open: func() (firewall.Firewall, error) {
		guard := &testDNSGuard{}
		guards = append(guards, guard)
		return guard, nil
	}, flush: func() { flushes++ }}
	for range 2 {
		if err := p.enable(); err != nil {
			t.Fatal(err)
		}
		if err := p.enable(); err != nil {
			t.Fatal(err)
		}
		p.close()
		p.close()
	}
	if len(guards) != 2 || guards[0].closed != 1 || guards[1].closed != 1 || flushes != 4 {
		t.Fatal("protection or cache invalidation was lost across reconnect", guards, flushes)
	}
}

func TestDNSProtectionFailureDoesNotClaimSuccessAndCanRetry(t *testing.T) {
	fail := true
	guard := &testDNSGuard{}
	flushes := 0
	p := tunnelDNSProtection{open: func() (firewall.Firewall, error) {
		if fail {
			return nil, errors.New("WFP unavailable")
		}
		return guard, nil
	}, flush: func() { flushes++ }}
	if err := p.enable(); err == nil || p.guard != nil || flushes != 0 {
		t.Fatal("a failed DNS guard was accepted")
	}
	p.close()
	fail = false
	if err := p.enable(); err != nil {
		t.Fatal(err)
	}
	p.close()
	if guard.closed != 1 || flushes != 2 {
		t.Fatal("retry leaked its filter session")
	}
}

// Explicit opt-in: temporarily installs the real dynamic WFP session, without changing the
// service, adapter or settings. Child resolvers are a different executable than the exempt worker.
func TestLiveWindowsDNSGuardBlocksOtherResolversAndRestoresOnClose(t *testing.T) {
	if os.Getenv("YUMI_VERIFY_DNS_GUARD") != "1" {
		t.Skip("requires an elevated shell and an existing working Yumi tunnel")
	}
	query := func(server string, tcp bool) error {
		ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
		defer cancel()
		command := "$ErrorActionPreference='Stop'; try { Resolve-DnsName -Name example.com -Type A -Server '" + server + "' -DnsOnly -NoHostsFile -QuickTimeout"
		if tcp {
			command += " -TcpOnly"
		}
		command += " | Out-Null; exit 0 } catch { exit 42 }"
		child := exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
		child.SysProcAttr = childProcAttr()
		err := child.Run()
		if ctx.Err() != nil {
			t.Fatal("DNS helper exceeded its deadline")
		}
		return err
	}
	for _, tcp := range []bool{false, true} {
		if query("1.1.1.1", tcp) != nil || query(tunDNSV4, tcp) != nil {
			t.Fatal("baseline DNS must work before testing WFP")
		}
	}
	protection := newTunnelDNSProtection()
	if err := protection.enable(); err != nil {
		t.Fatal(err)
	}
	defer protection.close()
	for _, tcp := range []bool{false, true} {
		if err := query(tunDNSV4, tcp); err != nil {
			t.Fatal("the tunnel DNS was blocked")
		}
		if err := query("1.1.1.1", tcp); err == nil {
			t.Fatal("a child process bypassed the selected tunnel resolver")
		}
	}
	protection.close()
	for _, tcp := range []bool{false, true} {
		if err := query("1.1.1.1", tcp); err != nil {
			t.Fatal("closing protection did not remove DNS restrictions")
		}
	}
}
