#!/usr/bin/env bash
#
# Bump the plugin's release version in every file that carries it. `release.yml` calls this; the
# caller commits, tags and pushes.
#
#   scripts/bump-version.sh <major|minor|patch> [--dry-run]
#
# Two files carry it, and a release that moves one and not the other ships a plugin that names
# itself one version to the IDE and another to the server:
#
#   plugin/build.gradle.kts   `version`, which the build writes into the descriptor's `<version>`
#                             and the zip's name (`selvage-<version>.zip`), and which `release.yml`
#                             asserts the tag name against
#   SelvageService.kt         `CLIENT`, the identity the plugin sends in `session.hello`
#                             (`selvage-jetbrains/<version>`)
#
# Nothing else carries it. The engine has no version of its own, the README names the
# zip as `selvage-<version>.zip`, and the end-to-end driver's descriptor
# (`plugin/src/e2e/resources/META-INF/plugin.xml`) carries the driver's own version, which is never
# published. The change notes in `plugin/src/main/resources/META-INF/plugin.xml` are prose written
# for the version they describe, so this does not write them: `release.yml` refuses a version whose
# notes are not there. `scripts/test-bump-version.sh` runs this on a copy of the checkout and
# asserts that a bump writes these two files and no others.
#
# The word is applied to the version `plugin/build.gradle.kts` carries: `patch` moves the last
# component (0.1.0 -> 0.1.1), `minor` the middle one and zeros the last (0.1.0 -> 0.2.0), and
# `major` the first and zeros the rest (0.1.0 -> 1.0.0). The resulting version is printed as the
# last line of stdout and shares that line with nothing else, so the caller can name the tag and
# the Release from it. `--dry-run` prints the same version and writes nothing. Anything that is not
# one of the three words is refused with the tree unchanged.
#
# Each spot is found by the shape of the line that carries it rather than by the version it holds,
# and every spot is located before the first is written, so a file whose shape has moved refuses
# with the tree as it was rather than leaving a half-bumped one behind. A spot that already carries
# the version asked for is left alone, so a tree bumped by hand in one file and not the other is
# repaired rather than reported as done, and every file written (or, under `--dry-run`, that would
# be) is named.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

build=plugin/build.gradle.kts
service=plugin/src/main/kotlin/dev/dontblameme/selvage/intellij/session/SelvageService.kt

usage() {
  printf 'usage: %s <major|minor|patch> [--dry-run]\n' "${0##*/}" >&2
  exit 2
}

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  usage
fi

word=$1
dry_run=false
if [ "$#" -eq 2 ]; then
  if [ "$2" != '--dry-run' ]; then
    usage
  fi
  dry_run=true
fi

case "$word" in
  major | minor | patch) ;;
  *)
    printf 'refusing: %q is not a bump; name major, minor or patch\n' "$word" >&2
    exit 1
    ;;
esac

# locate <file> <ere>: the number of the one line the pattern names, refusing when it names none
# or more than one.
locate() {
  local file=$1 pattern=$2 hits
  hits=$(grep -c -E -- "$pattern" "$file" || true)
  if [ "$hits" != 1 ]; then
    printf 'refusing: %s has %s line(s) matching %s, want one; nothing written\n' "$file" "$hits" "$pattern" >&2
    exit 1
  fi
  grep -n -E -- "$pattern" "$file" | cut -d: -f1
}

build_line=$(locate "$build" '^version = "[^"]*"$')
service_line=$(locate "$service" '^        const val CLIENT = "selvage-jetbrains/[^"]*"$')

# The build's own version, which the word is applied to: three plain decimal components and
# nothing else. A tree that carries anything else is refused rather than bumped from it.
current=$(sed -n "${build_line}s/^version = \"\\([^\"]*\\)\"\$/\\1/p" "$build")
if ! [[ $current =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  printf 'refusing: %s carries %q, not X.Y.Z\n' "$build" "$current" >&2
  exit 1
fi

major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[3]}

# The shell's arithmetic is signed 64-bit, so a component of 19 digits or more can wrap when it is
# incremented. Only the component the word moves can overflow; the ones below it are reset.
too_large() {
  printf 'refusing: %s carries a component too large to bump\n' "$current" >&2
  exit 1
}
case "$word" in
  major)
    [ "${#major}" -le 18 ] || too_large
    major=$((major + 1))
    minor=0
    patch=0
    ;;
  minor)
    [ "${#minor}" -le 18 ] || too_large
    minor=$((minor + 1))
    patch=0
    ;;
  patch)
    [ "${#patch}" -le 18 ] || too_large
    patch=$((patch + 1))
    ;;
esac
new="$major.$minor.$patch"

# What each spot becomes. The whole line is replaced, addressed by its number.
files=("$build" "$service")
lines=("$build_line" "$service_line")
targets=(
  "$(printf 'version = "%s"' "$new")"
  "$(printf '        const val CLIENT = "selvage-jetbrains/%s"' "$new")"
)

changed=()
for i in "${!files[@]}"; do
  file=${files[$i]}
  if [ "$(sed -n "${lines[$i]}p" "$file")" = "${targets[$i]}" ]; then
    continue
  fi
  if [ "$dry_run" = false ]; then
    sed -i "${lines[$i]}s|.*|${targets[$i]}|" "$file"
  fi
  changed+=("$file")
done

if [ "$dry_run" = true ]; then
  printf 'would set %s -> %s in:\n' "$current" "$new"
else
  printf 'set %s -> %s in:\n' "$current" "$new"
fi
printf '  %s\n' "${changed[@]}"
printf '%s\n' "$new"
