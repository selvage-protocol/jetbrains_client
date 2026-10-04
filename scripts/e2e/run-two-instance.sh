#!/usr/bin/env bash
#
# Runs `scripts/e2e/two_instance.py`: two real IntelliJ IDEA instances, each with the built plugin
# in its own sandbox and on its own Xvfb display, in one room on a real selvaged, with the
# TypeScript engine the other clients share as a third participant and as a host whose socket is
# cut. Run it inside `nix develop`, which provides Xvfb and the libraries an IDE window loads.
#
#   SELVAGE_SELVAGED=/path/to/selvaged scripts/e2e/run-two-instance.sh
#
# The IDE is the one the plugin is built and tested against, downloaded into ~/.gradle by the first
# build. Each instance is capped at 1.5 GB of heap. Sandboxes, logs and screenshots are kept under
# `.tmp/e2e/` until the next run.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

export TMPDIR="$repo_root/.tmp"
mkdir -p "$TMPDIR"

if [[ -z "${SELVAGE_SELVAGED:-}" ]]; then
  echo "SELVAGE_SELVAGED is not set: the end-to-end test spawns a real selvaged" >&2
  exit 2
fi

./gradlew --max-workers=4 --console=plain :plugin:prepareE2e
python3 scripts/e2e/two_instance.py plugin/build/e2e/kit.properties
