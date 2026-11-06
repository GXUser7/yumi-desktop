package main

import (
    "context"
    "crypto/sha256"
    "encoding/hex"
    "errors"
    "fmt"
    "io"
    "net/http"
    "os"
    "path/filepath"
    "strings"
)

/*
The routing databases: geoip.dat and geosite.dat, which is where "Russian sites go direct" gets its
idea of what a Russian site is.

The service fetches them itself, from addresses fixed here, into a directory only it and
administrators can write. The app only ever says "update"; it never names a URL or a path. That keeps
the privilege boundary where it was — the app is the owner's, the service is SYSTEM's, and a
command that made SYSTEM fetch or read something the caller chose would be a way across it.

The same community databases Yumi on the phone uses, from the same releases.
*/
type geoAsset struct {
    File string
    URL  string
}

var geoAssets = []geoAsset{
    {"geoip.dat", "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"},
    // The domain list is published as dlc.dat and the core looks for geosite.dat.
    {"geosite.dat", "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"},
}

const (
    // Far below either real database and far above any error page handed back in place of one.
    // The point is to reject the implausible, not to pin a size that drifts with every release.
    geoMinimumBytes = 100_000
    // A ceiling, so that a misbehaving mirror cannot fill the system drive.
    geoMaximumBytes = 128 << 20
)

type geoInfo struct {
    Ready     bool
    Bytes     int64
    UpdatedAt int64
}

/*
What is on disk. Size is checked as well as existence, because the failure worth catching is a
half-written file: the core resolves every geosite:/geoip: reference while parsing and refuses the
whole configuration when one cannot be read — not the rule, the tunnel.
*/
func inspectGeo(dir string) geoInfo {
    info := geoInfo{Ready: dir != ""}
    for _, asset := range geoAssets {
        stat, err := os.Stat(filepath.Join(dir, asset.File))
        if dir == "" || err != nil || !stat.Mode().IsRegular() || stat.Size() < geoMinimumBytes {
            info.Ready = false
            continue
        }
        info.Bytes += stat.Size()
        if modified := stat.ModTime().UnixMilli(); modified > info.UpdatedAt {
            info.UpdatedAt = modified
        }
    }
    return info
}

// Fetches every database, each verified and moved into place on its own.
func downloadGeo(ctx context.Context, client *http.Client, dir string, assets []geoAsset) error {
    if err := os.MkdirAll(dir, 0o755); err != nil {
        return err
    }
    for _, asset := range assets {
        if err := downloadGeoAsset(ctx, client, dir, asset); err != nil {
            return fmt.Errorf("%s: %w", asset.File, err)
        }
    }
    return nil
}

/*
One database, and three things true before it replaces the one the core reads: the transfer
finished (a cut stream ends a copy as normally as a complete one), the bytes are the published ones
where the release publishes a checksum, and it lands by rename so the core never meets half a file.
*/
func downloadGeoAsset(ctx context.Context, client *http.Client, dir string, asset geoAsset) error {
    expected := fetchChecksum(ctx, client, asset.URL+".sha256sum")
    target := filepath.Join(dir, asset.File)
    part := target + ".part"
    _ = os.Remove(part)
    defer os.Remove(part)

    body, length, err := get(ctx, client, asset.URL)
    if err != nil {
        return err
    }
    defer body.Close()

    file, err := os.OpenFile(part, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
    if err != nil {
        return err
    }
    digest := sha256.New()
    written, copyErr := io.Copy(io.MultiWriter(file, digest), io.LimitReader(body, geoMaximumBytes+1))
    syncErr := file.Sync()
    closeErr := file.Close()
    switch {
    case copyErr != nil:
        return copyErr
    case syncErr != nil:
        return syncErr
    case closeErr != nil:
        return closeErr
    case written > geoMaximumBytes:
        return errors.New("larger than any real database")
    case written < geoMinimumBytes:
        return fmt.Errorf("only %d bytes arrived", written)
    case length > 0 && written != length:
        return fmt.Errorf("%d bytes of an announced %d", written, length)
    }
    if expected != "" && !strings.EqualFold(hex.EncodeToString(digest.Sum(nil)), expected) {
        return errors.New("checksum mismatch")
    }
    return os.Rename(part, target)
}

// The published checksum, or "" when the release has none or it cannot be read.
func fetchChecksum(ctx context.Context, client *http.Client, url string) string {
    body, _, err := get(ctx, client, url)
    if err != nil {
        return ""
    }
    defer body.Close()
    text, err := io.ReadAll(io.LimitReader(body, 4096))
    if err != nil {
        return ""
    }
    // `<hex>  <filename>`, which is what sha256sum writes.
    fields := strings.Fields(string(text))
    if len(fields) == 0 || len(fields[0]) != 64 {
        return ""
    }
    if _, err := hex.DecodeString(fields[0]); err != nil {
        return ""
    }
    return fields[0]
}

func get(ctx context.Context, client *http.Client, url string) (io.ReadCloser, int64, error) {
    request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
    if err != nil {
        return nil, 0, err
    }
    request.Header.Set("User-Agent", "Yumi-Desktop")
    response, err := client.Do(request)
    if err != nil {
        return nil, 0, err
    }
    if response.StatusCode < 200 || response.StatusCode > 299 {
        response.Body.Close()
        return nil, 0, fmt.Errorf("HTTP %d", response.StatusCode)
    }
    return response.Body, response.ContentLength, nil
}
