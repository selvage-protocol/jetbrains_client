#!/usr/bin/env bash
set -euo pipefail

current=${1:?current version is required}
target=${2:?target version is required}
current_tag=$(git ls-remote --refs --tags origin "refs/tags/v${current}")
current_tag=$(printf '%s\n' "$current_tag" | awk -v ref="refs/tags/v${current}" '$2 == ref')
target_tag=$(git ls-remote --refs --tags origin "refs/tags/v${target}")
target_tag=$(printf '%s\n' "$target_tag" | awk -v ref="refs/tags/v${target}" '$2 == ref')
if [ -z "$current_tag" ]; then
  printf 'refusing: the remote no longer has the current release tag v%s\n' "$current" >&2
  exit 1
fi
if [ -n "$target_tag" ]; then
  printf 'refusing: target tag v%s already exists on the remote\n' "$target" >&2
  exit 1
fi
