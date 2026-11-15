//go:build darwin

package main

import (
	"net"

	"golang.org/x/sys/unix"
)

// Who is on the other end of this socket, as the kernel swears it: LOCAL_PEERCRED hands over the
// credential structure the socket was created with, which the connecting process cannot forge.
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
		cred, err := unix.GetsockoptXucred(int(fd), unix.SOL_LOCAL, unix.LOCAL_PEERCRED)
		if err != nil || cred == nil {
			return
		}
		uid, answer = int(cred.Uid), true
	}); err != nil {
		return 0, false
	}
	return uid, answer
}
