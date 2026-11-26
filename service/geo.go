package main

import (
	"bufio"
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
finished (a cut stream ends a copy as normally as a complete one), the bytes match a verified
published checksum (fail-closed if missing or malformed), and it lands by atomic rename from an
unpredictable temporary file so the core never meets half a file.
*/
func downloadGeoAsset(ctx context.Context, client *http.Client, dir string, asset geoAsset) error {
	expected, err := fetchChecksum(ctx, client, asset.URL+".sha256sum")
	if err != nil {
		return fmt.Errorf("checksum: %w", err)
	}
	target := filepath.Join(dir, asset.File)

	body, length, err := get(ctx, client, asset.URL)
	if err != nil {
		return err
	}
	defer body.Close()

	file, err := os.CreateTemp(dir, ".geo-*")
	if err != nil {
		return err
	}
	part := file.Name()
	defer os.Remove(part)

	digest := sha256.New()
	written, copyErr := io.Copy(io.MultiWriter(file, digest), io.LimitReader(body, geoMaximumBytes+1))
	chmodErr := file.Chmod(0o644)
	syncErr := file.Sync()
	closeErr := file.Close()
	switch {
	case copyErr != nil:
		return copyErr
	case chmodErr != nil:
		return chmodErr
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
	if !strings.EqualFold(hex.EncodeToString(digest.Sum(nil)), expected) {
		return errors.New("checksum mismatch")
	}
	return os.Rename(part, target)
}

// The published checksum; fails closed when missing, unreadable, or malformed.
func fetchChecksum(ctx context.Context, client *http.Client, url string) (string, error) {
	body, _, err := get(ctx, client, url)
	if err != nil {
		return "", err
	}
	defer body.Close()
	text, err := io.ReadAll(io.LimitReader(body, 4096))
	if err != nil {
		return "", err
	}
	// `<hex>  <filename>`, which is what sha256sum writes.
	fields := strings.Fields(string(text))
	if len(fields) == 0 || len(fields[0]) != 64 {
		return "", errors.New("invalid checksum format")
	}
	if _, err := hex.DecodeString(fields[0]); err != nil {
		return "", errors.New("invalid checksum hex")
	}
	return fields[0], nil
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

type geoCategories struct {
	geoip   map[string]struct{}
	geosite map[string]struct{}
}

func loadGeoCategories(dir string) geoCategories {
	out := geoCategories{
		geoip:   map[string]struct{}{},
		geosite: map[string]struct{}{},
	}
	if dir == "" {
		return out
	}
	out.geoip = readGeoCodesFile(filepath.Join(dir, "geoip.dat"))
	out.geosite = readGeoCodesFile(filepath.Join(dir, "geosite.dat"))
	return out
}

func (g geoCategories) hasAny() bool {
	return len(g.geoip) > 0 && len(g.geosite) > 0
}

func (g geoCategories) knows(entry string) bool {
	trimmed := strings.TrimSpace(entry)
	lower := strings.ToLower(trimmed)
	if strings.HasPrefix(lower, "ext:") {
		return false
	}
	var prefix string
	var codes map[string]struct{}
	switch {
	case strings.HasPrefix(lower, "geosite:"):
		prefix = "geosite:"
		codes = g.geosite
	case strings.HasPrefix(lower, "geoip:"):
		prefix = "geoip:"
		codes = g.geoip
	default:
		return true
	}
	if len(codes) == 0 {
		return false
	}
	code := trimmed[len(prefix):]
	code = strings.TrimPrefix(code, "!")
	if at := strings.IndexByte(code, '@'); at >= 0 {
		code = code[:at]
	}
	code = strings.ToUpper(strings.TrimSpace(code))
	if code == "" {
		return false
	}
	_, ok := codes[code]
	return ok
}

func readGeoCodesFile(path string) map[string]struct{} {
	codes := map[string]struct{}{}
	f, err := os.Open(path)
	if err != nil {
		return codes
	}
	defer f.Close()
	r := &protoGeoReader{r: bufio.NewReaderSize(f, 64*1024)}
	for {
		tag, ok, err := r.varintOrEOF()
		if !ok || err != nil {
			break
		}
		field := tag >> 3
		wire := tag & 7
		if field == 1 && wire == 2 {
			length, err := r.varint()
			if err != nil || length > 128<<20 {
				break
			}
			if code, err := r.readEntryCode(int64(length)); err == nil && code != "" {
				codes[strings.ToUpper(code)] = struct{}{}
			}
		} else if err := r.skipValue(wire); err != nil {
			break
		}
	}
	return codes
}

type protoGeoReader struct {
	r   *bufio.Reader
	pos int64
}

func (p *protoGeoReader) byte() (byte, error) {
	b, err := p.r.ReadByte()
	if err == nil {
		p.pos++
	}
	return b, err
}

func (p *protoGeoReader) varintOrEOF() (uint64, bool, error) {
	first, err := p.byte()
	if err != nil {
		return 0, false, nil
	}
	val, err := p.varintFrom(first)
	return val, true, err
}

func (p *protoGeoReader) varint() (uint64, error) {
	first, err := p.byte()
	if err != nil {
		return 0, err
	}
	return p.varintFrom(first)
}

func (p *protoGeoReader) varintFrom(first byte) (uint64, error) {
	res := uint64(first & 0x7F)
	cur := first
	shift := uint(7)
	for cur&0x80 != 0 {
		if shift > 63 {
			return 0, errors.New("varint overflow")
		}
		b, err := p.byte()
		if err != nil {
			return 0, err
		}
		res |= uint64(b&0x7F) << shift
		cur = b
		shift += 7
	}
	return res, nil
}

func (p *protoGeoReader) skip(n int64) error {
	if n < 0 {
		return errors.New("negative skip")
	}
	for n > 0 {
		d, err := p.r.Discard(int(min(n, 64*1024)))
		p.pos += int64(d)
		n -= int64(d)
		if err != nil {
			return err
		}
	}
	return nil
}

func (p *protoGeoReader) skipValue(wire uint64) error {
	switch wire {
	case 0:
		_, err := p.varint()
		return err
	case 1:
		return p.skip(8)
	case 2:
		l, err := p.varint()
		if err != nil {
			return err
		}
		return p.skip(int64(l))
	case 5:
		return p.skip(4)
	default:
		return fmt.Errorf("wire %d", wire)
	}
}

func (p *protoGeoReader) readEntryCode(length int64) (string, error) {
	end := p.pos + length
	var code string
	for p.pos < end {
		tag, err := p.varint()
		if err != nil {
			return "", err
		}
		field := tag >> 3
		wire := tag & 7
		if field == 1 && wire == 2 && code == "" {
			l, err := p.varint()
			if err != nil || l > 4096 || p.pos+int64(l) > end {
				return "", errors.New("invalid entry code length")
			}
			buf := make([]byte, l)
			if _, err := io.ReadFull(p.r, buf); err != nil {
				return "", err
			}
			p.pos += int64(l)
			code = string(buf)
		} else {
			if err := p.skipValue(wire); err != nil {
				return "", err
			}
		}
	}
	if p.pos < end {
		if err := p.skip(end - p.pos); err != nil {
			return "", err
		}
	}
	return code, nil
}
