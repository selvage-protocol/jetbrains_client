#!/usr/bin/env bash
#
# The specification's peer corpus against this client: builds the subject launcher and hands it
# to `runner/run_peer.py`. Extra arguments go to the runner (`--mutation-census`, `--only 151`).
#
#   scripts/run-peer-vectors.sh
#   SELVAGE_SPECIFICATION=/path/to/specification scripts/run-peer-vectors.sh --mutation-census
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

export TMPDIR="$repo_root/.tmp"
mkdir -p "$TMPDIR"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -XX:-UsePerfData"

specification=${SELVAGE_SPECIFICATION:-$repo_root/../../../specification}
if [[ ! -f "$specification/runner/run_peer.py" ]]; then
  printf 'no specification at %s: set SELVAGE_SPECIFICATION to a checkout of it\n' "$specification" >&2
  exit 2
fi

./gradlew --max-workers=4 --console=plain -q :engine:installDist
subject="$repo_root/engine/build/install/engine/bin/engine"

timeout 900 python3 "$specification/runner/run_peer.py" --subject "$subject" "$@"
