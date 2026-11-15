//go:build linux

package main

import (
	"net"

	"golang.org/x/sys/unix"
)

// Who is on the other end of this socket, as the kernel swears it: SO_PEERCRED is filled in by
// the kernel at connect time and cannot be forged by the connecting process.
func peerUID(conn net.Conn) (int, bool) {
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return 0, false
	}
	var (
		uid    int
		answer bool
	)
	raw, err := unixConn.SyscallConn()
	if err != nil {
		return 0, false
	}
	if err := raw.Control(func(fd uintptr) {
		ucred, err := unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
		if err != nil {
			return
		}
		uid, answer = int(ucred.Uid), true
	}); err != nil {
		return 0, false
	}
	return uid, answer
}
