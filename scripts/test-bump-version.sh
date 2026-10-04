#!/usr/bin/env bash
#
# The guard around `scripts/bump-version.sh`: run on a scratch copy of the tracked tree, a bump
# writes the build's version and the client identity and no other file, `--dry-run` writes
# nothing, the version is the last line of stdout, and a word that is not a bump is refused with
# the tree unchanged. A release that moved one copy and not the other would ship a plugin naming
# two versions, and only a dispatch would find it.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "${TMPDIR:?TMPDIR names the scratch directory}"
scratch=$(mktemp -d "$TMPDIR/test-bump-version.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

build=plugin/build.gradle.kts
service=plugin/src/main/kotlin/dev/dontblameme/selvage/intellij/session/SelvageService.kt

# A fresh copy of the working tree (what git tracks or would track), committed, so `git status`
# names whatever a run wrote. An entry ending in `/` is another repository checked out inside this
# one, as CI's sibling checkouts under `.ci/` are, and is not part of the tree.
fresh() {
  rm -rf "$scratch/tree"
  mkdir -p "$scratch/tree"
  git -C "$repo_root" ls-files -z --cached --others --exclude-standard | grep -z -v '/$' |
    (cd "$repo_root" && xargs -0 cp --parents -t "$scratch/tree")
  git -C "$scratch/tree" init -q
  git -C "$scratch/tree" add -A
  git -C "$scratch/tree" -c user.name=test -c user.email=test@invalid -c commit.gpgsign=false \
    commit -q -m base
  sed -i 's/^version = "[^"]*"$/version = "1.9.4"/' "$scratch/tree/$build"
  sed -i 's|^        const val CLIENT = "selvage-jetbrains/[^"]*"$|        const val CLIENT = "selvage-jetbrains/1.9.4"|' \
    "$scratch/tree/$service"
  git -C "$scratch/tree" -c user.name=test -c user.email=test@invalid -c commit.gpgsign=false \
    commit -q -a -m seed
}

written() {
  git -C "$scratch/tree" status --porcelain | sed 's/^...//' | sort | tr '\n' ' '
}

expect_bump() {
  local word=$1 want=$2 out
  fresh
  out=$("$scratch/tree/scripts/bump-version.sh" "$word")
  [ "$(printf '%s\n' "$out" | tail -n1)" = "$want" ] || fail "$word: last line is not $want: $out"
  [ "$(written)" = "$build $service " ] || fail "$word wrote: $(written)"
  grep -qx "version = \"$want\"" "$scratch/tree/$build" || fail "$word: $build does not carry $want"
  grep -qx "        const val CLIENT = \"selvage-jetbrains/$want\"" "$scratch/tree/$service" ||
    fail "$word: $service does not carry $want"
  printf 'ok: %s 1.9.4 -> %s, writing %s\n' "$word" "$want" "$(written)"
}

expect_bump patch 1.9.5
expect_bump minor 1.10.0
expect_bump major 2.0.0

fresh
out=$("$scratch/tree/scripts/bump-version.sh" patch --dry-run)
[ "$(printf '%s\n' "$out" | tail -n1)" = 1.9.5 ] || fail "--dry-run: last line is not 1.9.5: $out"
[ -z "$(written)" ] || fail "--dry-run wrote: $(written)"
printf 'ok: --dry-run prints 1.9.5 and writes nothing\n'

for bad in 1.9.5 Patch ''; do
  fresh
  if "$scratch/tree/scripts/bump-version.sh" "$bad" >/dev/null 2>&1; then
    fail "the word '$bad' was accepted"
  fi
  [ -z "$(written)" ] || fail "the refused word '$bad' wrote: $(written)"
done
printf 'ok: words that are not a bump are refused with the tree unchanged\n'

# A half-bumped tree, the client identity moved by hand and the build not, is repaired: the build
# is written, and the file already at the target is left alone and not named.
fresh
sed -i 's|selvage-jetbrains/1.9.4"$|selvage-jetbrains/1.9.5"|' "$scratch/tree/$service"
git -C "$scratch/tree" -c user.name=test -c user.email=test@invalid -c commit.gpgsign=false \
  commit -q -a -m half
out=$("$scratch/tree/scripts/bump-version.sh" patch)
[ "$(written)" = "$build " ] || fail "the half-bumped tree wrote: $(written)"
grep -qx 'version = "1.9.5"' "$scratch/tree/$build" || fail "the half-bumped tree's build was not moved"
case "$out" in *"$service"*) fail "the file already at 1.9.5 was named: $out" ;; esac
printf 'ok: a half-bumped tree is repaired, writing only the file that was behind\n'
