#!/usr/bin/env bash
#
# Takes the JetBrains Marketplace screenshots into `docs/images/marketplace/`: two real IntelliJ
# IDEA instances at 1280×800, each on its own Xvfb display, in one room on a real selvaged, staged
# through the end-to-end test's driver (`scripts/screenshots/capture.py`). A manual step, never part
# of the gate. Run it inside `nix develop`, which provides Xvfb and the libraries an IDE window loads:
#
#   SELVAGE_SELVAGED=/path/to/selvaged scripts/screenshots/capture.sh
#
# Each image is then recompressed losslessly with optipng, through `nix shell`, and has to come out
# under 1 MB. Sandboxes and logs are kept under `.tmp/screenshots/` until the next run.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

export TMPDIR="$repo_root/.tmp"
mkdir -p "$TMPDIR"

if [[ -z "${SELVAGE_SELVAGED:-}" ]]; then
  echo "SELVAGE_SELVAGED is not set: the screenshots are of a session on a real selvaged" >&2
  exit 2
fi

raw="$TMPDIR/screenshots-raw"
out="docs/images/marketplace"
rm -rf "$raw"

./gradlew --max-workers=4 --console=plain :plugin:prepareE2e
python3 scripts/screenshots/capture.py plugin/build/e2e/kit.properties "$raw"

mkdir -p "$out"
cp "$raw"/*.png "$out"/
nix shell nixpkgs#optipng -c optipng -quiet -o5 -strip all "$out"/*.png

for image in "$out"/*.png; do
  size=$(stat -c %s "$image")
  if (( size >= 1048576 )); then
    echo "$image is $size bytes, over the 1 MB a Marketplace screenshot may be" >&2
    exit 1
  fi
  echo "ok: $image, $size bytes"
done
