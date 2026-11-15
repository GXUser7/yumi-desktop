#!/usr/bin/env bash
#
# The unix twin of tools/build.ps1: tests, the tunnel service, the application, the packages —
# and the same four-file version gate, because a release whose pieces disagree about its version
# is worse than no release. Linux builds .deb and .rpm; macOS builds .dmg and .pkg (the updater
# installs the pkg, the dmg is what one person hands another). Windows is build.ps1's business.
#
# Every push builds this way, tests included; a tag publishes what this produced.

set -euo pipefail
cd "$(dirname "$0")/.."

: "${JAVA_HOME:?JAVA_HOME must point at a JDK 21}"
export GOTOOLCHAIN=local
GO=${GO:-go}
GRADLE=${GRADLE:-./gradlew}
OS=$(uname)

# ── Version gate ─────────────────────────────────────────────────────────────────────────────
VERSION=$(sed -n 's/.*version = "\(.*\)".*/\1/p' build.gradle.kts | head -1)
[ -n "$VERSION" ] || { echo "version not found in build.gradle.kts"; exit 1; }
grep -q "packageVersion = \"$VERSION\"" desktop/build.gradle.kts || { echo "desktop/build.gradle.kts disagrees with $VERSION"; exit 1; }
grep -q "#define AppVersion \"$VERSION\"" installer/Yumi.iss || { echo "installer/Yumi.iss disagrees with $VERSION"; exit 1; }
grep -q "AppVersion = \"$VERSION\"" desktop/src/main/kotlin/com/yumi/desktop/BuildInfo.kt || { echo "BuildInfo.kt disagrees with $VERSION"; exit 1; }
echo "Version gate: $VERSION"

# ── Tests ────────────────────────────────────────────────────────────────────────────────────
echo "== JVM tests"
if [ "$OS" = "Linux" ]; then
    # TrayTest builds real AWT windows and a headless runner has none until Xvfb lends one;
    # everything else renders offscreen and runs the same either way.
    xvfb-run -a $GRADLE :logic:test :platform:test :desktop:test --rerun-tasks --console=plain
else
    $GRADLE :logic:test :platform:test :desktop:test --rerun-tasks --console=plain
fi

echo "== Service tests and build"
# The pinned-core test reads the request matrix :logic:test just wrote.
$GO -C service test ./... -count=1
$GO -C service build -trimpath -ldflags '-s -w' -o ../runtime/yumi-service .

# ── Icons ────────────────────────────────────────────────────────────────────────────────────
echo "== Icons"
if [ "$OS" = "Darwin" ]; then
    # The iconset is committed; the .icns is macOS' own conversion of it and is not portable.
    [ -f desktop/icon/yumi.icns ] || iconutil -c icns desktop/icon/yumi.iconset -o desktop/icon/yumi.icns
    ls -l desktop/icon/yumi.icns
fi

# ── Licenses ─────────────────────────────────────────────────────────────────────────────────
echo "== Licenses"
pwsh -NoProfile -File tools/collect-licenses.ps1 -GoExe "$(command -v $GO)"

# ── Packages ─────────────────────────────────────────────────────────────────────────────────
echo "== Packages"
# jpackage writes its complaint to a log on this machine; show it before dying.
$GRADLE :desktop:packageDistributionForCurrentOS --console=plain || {
    cat desktop/build/compose/logs/*/jpackage*err.txt 2>/dev/null
    exit 1
}

# ── Dist ─────────────────────────────────────────────────────────────────────────────────────
echo "== Dist"
mkdir -p dist
BINARIES=desktop/build/compose/binaries/main
if [ "$OS" = "Linux" ]; then
    cp "$BINARIES/deb/yumi_${VERSION}-1_amd64.deb"      "dist/Yumi-Desktop-$VERSION-linux-amd64.deb"
    cp "$BINARIES/rpm/Yumi-$VERSION-1.x86_64.rpm"       "dist/Yumi-Desktop-$VERSION-linux-amd64.rpm"
else
    cp "$BINARIES/dmg/Yumi-$VERSION.dmg"                "dist/Yumi-Desktop-$VERSION-macos-arm64.dmg"
    cp "$BINARIES/pkg/Yumi-$VERSION.pkg"                "dist/Yumi-Desktop-$VERSION-macos-arm64.pkg"
fi
cd dist
for f in *; do
    [ -f "$f.sha256" ] || sha256sum "$f" > "$f.sha256"
    cat "$f.sha256"
done
