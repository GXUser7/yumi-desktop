package main

import (
	"context"
	"errors"

	"github.com/xtls/xray-core/core"
	xoutbound "github.com/xtls/xray-core/features/outbound"
)

func installLiveOutbound(instance *core.Instance) (*liveOutbound, error) {
	manager, ok := instance.GetFeature(xoutbound.ManagerType()).(xoutbound.Manager)
	if !ok {
		return nil, errors.New("outbound manager unavailable")
	}
	handler := manager.GetHandler("proxy-node")
	if handler == nil {
		return nil, errors.New("proxy outbound unavailable")
	}
	live := newLiveOutbound(handler)
	if err := manager.RemoveHandler(context.Background(), "proxy-node"); err != nil {
		return nil, err
	}
	if err := manager.AddHandler(context.Background(), live); err != nil {
		_ = handler.Close()
		return nil, err
	}
	return live, nil
}

func prepareLiveOutbound(instance *core.Instance, config *core.Config) (xoutbound.Handler, error) {
	if len(config.Outbound) == 0 || (config.Outbound[0].Tag != "proxy-node" && config.Outbound[0].Tag != "direct-forward") {
		return nil, errors.New("proxy outbound unavailable")
	}
	raw, err := core.CreateObject(instance, config.Outbound[0])
	if err != nil {
		return nil, err
	}
	handler, ok := raw.(xoutbound.Handler)
	if !ok {
		return nil, errors.New("invalid outbound handler")
	}
	return handler, nil
}

func switchingStatus(previous Status, nodeID string) Status {
	next := previous
	if previous.State != "connected" {
		next.State = "connecting"
	}
	next.NodeID = nodeID
	next.Error, next.Detail = "", ""
	return next
}

func liveConnectedStatus(previous, requested Status, now int64) Status {
	next := requested
	next.State = "connected"
	next.TunLUID = previous.TunLUID
	next.ConnectedAt = now
	if previous.State == "connected" {
		next.ConnectedAt = previous.ConnectedAt
		next.Upload, next.Download = previous.Upload, previous.Download
	}
	return next
}
