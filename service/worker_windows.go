//go:build windows

package main

import (
    "bufio"
    "bytes"
    "encoding/json"
    "io"
    "os"
    "time"

    "github.com/xtls/xray-core/core"
    "github.com/xtls/xray-core/features/stats"
    "github.com/xtls/xray-core/infra/conf/serial"
    _ "github.com/xtls/xray-core/main/distro/all"

    // Two handlers the distro bundle does not register, and the reason the tunnel could never
    // start.
    //
    // `infra/conf` knows how to *parse* a tun inbound and a Hysteria outbound — it imports both
    // packages for their config types — but `main/distro/all` imports neither for its side
    // effects, so neither handler is in the registry. The failure that makes is thoroughly
    // misleading: the config loads without complaint, `core.New` succeeds, and it is only
    // `Start()` that fails, because the inbound manager builds its handlers there rather than at
    // construction. Every connection attempt came back as `tunnel_start_failed` with no adapter
    // ever created and nothing anywhere saying why.
    _ "github.com/xtls/xray-core/proxy/hysteria"
    _ "github.com/xtls/xray-core/proxy/tun"
    "golang.org/x/sys/windows"
    "golang.zx2c4.com/wintun"
    "golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

func worker() {
    input := bufio.NewReaderSize(os.Stdin,maxFrame+1)
    line, err := input.ReadBytes('\n')
    if err != nil || len(line)>maxFrame { return }
    var initial struct { Config json.RawMessage `json:"config"`; Status Status `json:"status"` }
    if json.Unmarshal(line,&initial)!=nil { return }
    encoder:=json.NewEncoder(os.Stdout)
    fail:=func(code string,cause error) {
        initial.Status.State="failed"; initial.Status.Error=code; initial.Status.Detail=detailOf(cause)
        _=encoder.Encode(initial.Status)
    }
    config,err:=serial.LoadJSONConfig(bytes.NewReader(initial.Config))
    if err!=nil { fail("invalid_configuration",err);return }
    // The worker owns only Yumi's adapter. It never rewrites physical-adapter DNS or default routes.
    defer cleanAdapter()
    instance,err:=core.New(config)
    if err!=nil { fail("core_initialization_failed",err);return }
    defer func(){cleanAdapter();instance.Close()}()
    if err=instance.Start();err!=nil { fail("tunnel_start_failed",err);return }
    initial.Status.State="connected";initial.Status.ConnectedAt=time.Now().UnixMilli()
    // The adapter the core just made, for the supervisor's kill switch to let through. Opened
    // rather than created, so closing the handle leaves the adapter where it is.
    if adapter,err:=wintun.OpenAdapter("Yumi");err==nil { initial.Status.TunLUID=adapter.LUID();_=adapter.Close() }
    manager,_:=instance.GetFeature(stats.ManagerType()).(stats.Manager)
    stopped:=make(chan struct{})
    go func(){ _,_=io.Copy(io.Discard,input);close(stopped) }()
    ticker:=time.NewTicker(time.Second);defer ticker.Stop()
    for {
        if manager!=nil {
            if c:=manager.GetCounter("outbound>>>proxy-node>>>traffic>>>uplink");c!=nil { initial.Status.Upload=c.Value() }
            if c:=manager.GetCounter("outbound>>>proxy-node>>>traffic>>>downlink");c!=nil { initial.Status.Download=c.Value() }
        }
        if encoder.Encode(initial.Status)!=nil { return }
        select { case <-stopped:return;case <-ticker.C: }
    }
}

func cleanAdapter() {
    adapter,err:=wintun.OpenAdapter("Yumi");if err!=nil{return};defer adapter.Close()
    luid:=winipcfg.LUID(adapter.LUID())
    _=luid.FlushRoutes(windows.AF_INET);_=luid.FlushRoutes(windows.AF_INET6)
    _=luid.FlushDNS(windows.AF_INET);_=luid.FlushDNS(windows.AF_INET6)
    _=luid.FlushIPAddresses(windows.AF_INET);_=luid.FlushIPAddresses(windows.AF_INET6)
}
