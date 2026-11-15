// icongen renders the application icon into every raster size the unix packages need.
//
// The source of truth is the same yumi.svg the app itself draws: a rounded square, two small
// tiles, and the pixel-map motif. The file is parsed just far enough for that vocabulary — rect
// and path, fills, the M/L/H/V/C/S/Z commands the icon actually uses — and rasterized with an
// even-odd scan over supersampled points, because importing an SVG engine for six shapes would
// be the only dependency this tool has and the only reason to maintain it.
//
// Usage: go run . <yumi.svg> <out-dir>
// Writes yumi-<size>.png for the Linux package and a yumi.iconset directory for macOS
// iconutil(1) to turn into an .icns.
package main

import (
	"bufio"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: icongen <yumi.svg> <out-dir>")
		os.Exit(2)
	}
	source, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	out := os.Args[2]
	if err := os.MkdirAll(out, 0o755); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := os.MkdirAll(filepath.Join(out, "yumi.iconset"), 0o755); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	shapes, err := parse(string(source))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	for _, size := range []int{16, 32, 64, 128, 256, 512, 1024} {
		img := render(shapes, size)
		name := filepath.Join(out, fmt.Sprintf("yumi-%d.png", size))
		if err := writePNG(name, img); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Println("wrote", name)
	}
	iconsetSizes := map[string]int{
		"icon_16x16.png": 16, "icon_16x16@2x.png": 32,
		"icon_32x32.png": 32, "icon_32x32@2x.png": 64,
		"icon_128x128.png": 128, "icon_128x128@2x.png": 256,
		"icon_256x256.png": 256, "icon_256x256@2x.png": 512,
		"icon_512x512.png": 512, "icon_512x512@2x.png": 1024,
	}
	for name, size := range iconsetSizes {
		img := render(shapes, size)
		if err := writePNG(filepath.Join(out, "yumi.iconset", name), img); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	fmt.Println("wrote", filepath.Join(out, "yumi.iconset"))
}

// A shape is what the icon's vocabulary needs: a rounded rectangle, or a free path with a fill.
type shape struct {
	kind   int // roundedRect or path
	// Rounded rectangle, in viewBox coordinates.
	x, y, w, h, rx float64
	// Flattened path outlines, one per M..Z subpath. The icon's motif touches itself at corners,
	// so the subpaths are filled separately and unioned rather than wound together.
	contours [][]point
	fill     color.RGBA
}

const (
	roundedRect = iota
	freePath
)

type point struct{ x, y float64 }

var (
	rectRe = regexp.MustCompile(`<rect ([^/]*)/>`)
	pathRe = regexp.MustCompile(`<path d="([^"]*)" fill="([^"]*)"`)
	attrRe = regexp.MustCompile(`([a-zA-Z-]+)="([^"]*)"`)
	number = regexp.MustCompile(`[-+]?[0-9]*\.?[0-9]+`)
)

func parse(svg string) ([]shape, error) {
	var shapes []shape
	// Document order is paint order, so both element kinds are collected in one pass over the
	// file rather than one kind at a time.
	tags := regexp.MustCompile(`<rect [^/]*/>|<path d="[^"]*" fill="[^"]*"`)
	for _, tag := range tags.FindAllString(svg, -1) {
		switch {
		case strings.HasPrefix(tag, "<rect"):
			attrs := map[string]string{}
			for _, a := range attrRe.FindAllStringSubmatch(tag, -1) {
				attrs[a[1]] = a[2]
			}
			atof := func(key string) float64 { v, _ := strconv.ParseFloat(attrs[key], 64); return v }
			fill, err := parseColor(attrs["fill"])
			if err != nil {
				return nil, err
			}
			shapes = append(shapes, shape{kind: roundedRect,
				x: atof("x"), y: atof("y"), w: atof("width"), h: atof("height"), rx: atof("rx"), fill: fill})
		default:
			m := pathRe.FindStringSubmatch(tag)
			if m == nil {
				continue
			}
			fill, err := parseColor(m[2])
			if err != nil {
				return nil, err
			}
			contours, err := flattenPath(m[1])
			if err != nil {
				return nil, err
			}
			shapes = append(shapes, shape{kind: freePath, contours: contours, fill: fill})
		}
	}
	if len(shapes) == 0 {
		return nil, fmt.Errorf("no shapes found")
	}
	return shapes, nil
}

func parseColor(hex string) (color.RGBA, error) {
	hex = strings.TrimPrefix(hex, "#")
	if len(hex) != 6 {
		return color.RGBA{}, fmt.Errorf("unsupported fill %q", hex)
	}
	v, err := strconv.ParseUint(hex, 16, 32)
	if err != nil {
		return color.RGBA{}, err
	}
	return color.RGBA{R: uint8(v >> 16), G: uint8(v >> 8), B: uint8(v), A: 255}, nil
}

/*
Flattens the path into line segments, one contour per M..Z subpath. The icon uses M, L, H, V, C,
S and Z with absolute coordinates only; each cubic is cut into enough pieces that a 1024-pixel
render sees straight lines where the curve is.
*/
func flattenPath(d string) ([][]point, error) {
	tokens := number.FindAllString(d, -1)
	values := make([]float64, 0, len(tokens))
	for _, t := range tokens {
		v, err := strconv.ParseFloat(t, 64)
		if err != nil {
			return nil, err
		}
		values = append(values, v)
	}
	var points []point
	var contours [][]point
	pen, start := point{}, point{}
	i, at := 0, 0
	next := func(n int) ([]float64, bool) {
		if at+n > len(values) {
			return nil, false
		}
		out := values[at : at+n]
		at += n
		return out, true
	}
	addLine := func(to point) {
		points = append(points, pen)
		pen = to
	}
	cubic := func(c1, c2, to point) {
		points = append(points, pen)
		const steps = 12
		for s := 1; s <= steps; s++ {
			t := float64(s) / steps
			u := 1 - t
			x := u*u*u*pen.x + 3*u*u*t*c1.x + 3*u*t*t*c2.x + t*t*t*to.x
			y := u*u*u*pen.y + 3*u*u*t*c1.y + 3*u*t*t*c2.y + t*t*t*to.y
			points = append(points, point{x, y})
		}
		pen = to
	}
	for i < len(d) {
		switch ch := d[i]; ch {
		case 'M':
			v, ok := next(2)
			if !ok {
				return nil, fmt.Errorf("bad M")
			}
			if len(points) > 0 {
				points = append(points, pen) // close the contour that came before
				contours = append(contours, points)
				points = nil
			}
			pen = point{v[0], v[1]}
			start = pen
			points = append(points, pen)
		case 'L':
			v, ok := next(2)
			if !ok {
				return nil, fmt.Errorf("bad L")
			}
			addLine(point{v[0], v[1]})
		case 'H':
			v, ok := next(1)
			if !ok {
				return nil, fmt.Errorf("bad H")
			}
			addLine(point{v[0], pen.y})
		case 'V':
			v, ok := next(1)
			if !ok {
				return nil, fmt.Errorf("bad V")
			}
			addLine(point{pen.x, v[0]})
		case 'C':
			v, ok := next(6)
			if !ok {
				return nil, fmt.Errorf("bad C")
			}
			cubic(point{v[0], v[1]}, point{v[2], v[3]}, point{v[4], v[5]})
		case 'S':
			v, ok := next(4)
			if !ok {
				return nil, fmt.Errorf("bad S")
			}
			// The icon has no S that needs a reflected first control; a straight one suffices
			// for the smooth runs it draws.
			cubic(pen, point{v[0], v[1]}, point{v[2], v[3]})
		case 'Z':
			addLine(start)
		}
		i++
	}
	if len(points) > 0 {
		points = append(points, pen)
		contours = append(contours, points)
	}
	return contours, nil
}

func render(shapes []shape, size int) image.Image {
	const ss = 8 // supersampling: 8x8 subpixels; the seams where subpaths touch live below this
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	scale := float64(size*ss) / 366.0
	for py := 0; py < size; py++ {
		for px := 0; px < size; px++ {
			var r, g, b int
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					x := (float64(px*ss+sx) + 0.5) / scale
					y := (float64(py*ss+sy) + 0.5) / scale
					for i := len(shapes) - 1; i >= 0; i-- {
						if covers(shapes[i], x, y) {
							c := shapes[i].fill
							r, g, b = r+int(c.R), g+int(c.G), b+int(c.B)
							break
						}
					}
				}
			}
			n := ss * ss
			img.SetRGBA(px, py, color.RGBA{R: uint8(r / n), G: uint8(g / n), B: uint8(b / n), A: 255})
		}
	}
	return img
}

func covers(s shape, x, y float64) bool {
	if s.kind == roundedRect {
		return inRoundedRect(s.x, s.y, s.w, s.h, s.rx, x, y)
	}
	for _, contour := range s.contours {
		if inPolygon(contour, x, y) {
			return true
		}
	}
	return false
}

func inRoundedRect(x, y, w, h, rx, px, py float64) bool {
	if px < x || px >= x+w || py < y || py >= y+h {
		return false
	}
	r := rx
	cx := x + r
	if px > x+w-r {
		cx = x + w - r
	}
	cy := y + r
	if py > y+h-r {
		cy = y + h - r
	}
	// A corner region is the only place the rounding bites; everywhere else the point is in.
	inCornerX := px < x+r || px >= x+w-r
	inCornerY := py < y+r || py >= y+h-r
	if inCornerX && inCornerY {
		dx, dy := px-cx, py-cy
		return dx*dx+dy*dy <= r*r
	}
	return true
}

// Even-odd rule over the flattened outline.
func inPolygon(points []point, x, y float64) bool {
	inside := false
	j := len(points) - 1
	for i := 0; i < len(points); i++ {
		a, b := points[i], points[j]
		if (a.y > y) != (b.y > y) {
			cross := a.x + (y-a.y)/(b.y-a.y)*(b.x-a.x)
			if x < cross {
				inside = !inside
			}
		}
		j = i
	}
	return inside
}

func writePNG(name string, img image.Image) error {
	f, err := os.Create(name)
	if err != nil {
		return err
	}
	defer f.Close()
	w := bufio.NewWriter(f)
	if err := png.Encode(w, img); err != nil {
		return err
	}
	return w.Flush()
}
