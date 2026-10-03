#!/usr/bin/env bash
#
# The specification's peer corpus against this client: builds the subject launcher and hands it
# to `runner/run_peer.py`. Extra arguments go to the runner; `--self-test` runs the runner's own
# tests instead, which need nothing the corpus does not.
#
#   scripts/run-peer-vectors.sh
#   scripts/run-peer-vectors.sh --mutation-census
#   scripts/run-peer-vectors.sh --vector 155
#   scripts/run-peer-vectors.sh --self-test
#
# The specification is SELVAGE_SPECIFICATION, or else the nearest `specification` checkout beside
# this one or one of its ancestors.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

export TMPDIR="$repo_root/.tmp"
mkdir -p "$TMPDIR"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR -XX:-UsePerfData"

specification=${SELVAGE_SPECIFICATION:-}
if [[ -z "$specification" ]]; then
  dir=$repo_root
  while [[ "$dir" != / ]]; do
    if [[ -f "$dir/specification/runner/run_peer.py" ]]; then
      specification=$dir/specification
      break
    fi
    dir=$(dirname -- "$dir")
  done
fi
if [[ -z "$specification" || ! -f "$specification/runner/run_peer.py" ]]; then
  printf 'no specification found%s: set SELVAGE_SPECIFICATION to a checkout of it\n' \
    "${specification:+ at $specification}" >&2
  exit 2
fi

if [[ "${1:-}" == --self-test ]]; then
  cd "$specification"
  exec timeout 600 python3 -m unittest discover -s runner -p 'test_*.py'
fi

./gradlew --max-workers=4 --console=plain -q :engine:installDist
subject="$repo_root/engine/build/install/engine/bin/engine"

timeout 900 python3 "$specification/runner/run_peer.py" --subject "$subject" "$@"
