package firewall

/*
TunRef names the tunnel adapter for the kill switch, in whatever currency the platform speaks.

Windows hands over a wintun LUID; Linux and macOS hand over the interface name the core created.
Each platform's Enable reads its own half and ignores the other, so the supervisor can carry one
identity through code that does not care where it runs. The zero value is meaningful on both: it
matches no interface, and the tunnel's own traffic still flows because it leaves from this
executable.
*/
type TunRef struct {
	LUID uint64
	Name string
}

/*
Firewall is one installed kill switch. Close removes everything it put in place, is safe to call
more than once, and must be safe to call on a nil value — the supervisor holds it behind an
interface and tears it down from more paths than it can count.
*/
type Firewall interface {
	Close()
}
