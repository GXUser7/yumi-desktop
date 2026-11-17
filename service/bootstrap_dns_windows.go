//go:build windows

package main

import (
	"encoding/binary"
	"net"
	"strings"
	"syscall"
	"unsafe"
)

const ipUnicastIf = 31

func bindBootstrapSocket(fd uintptr, network string, iface *net.Interface) error {
	if iface == nil || iface.Index <= 0 {
		return nil
	}
	if strings.HasSuffix(network, "6") {
		return syscall.SetsockoptInt(syscall.Handle(fd), syscall.IPPROTO_IPV6, ipUnicastIf, iface.Index)
	}
	var raw [4]byte
	binary.BigEndian.PutUint32(raw[:], uint32(iface.Index))
	idx := *(*uint32)(unsafe.Pointer(&raw[0]))
	return syscall.SetsockoptInt(syscall.Handle(fd), syscall.IPPROTO_IP, ipUnicastIf, int(idx))
}
