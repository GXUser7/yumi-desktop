//go:build linux

package main

import (
	"net"

	"golang.org/x/sys/unix"
)

func bindBootstrapSocket(fd uintptr, _ string, iface *net.Interface) error {
	if iface == nil || iface.Name == "" {
		return nil
	}
	return unix.SetsockoptString(int(fd), unix.SOL_SOCKET, unix.SO_BINDTODEVICE, iface.Name)
}
