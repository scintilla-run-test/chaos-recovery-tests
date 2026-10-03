#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
tmp_root="${TMPDIR:-/tmp}"
build_dir="$(mktemp -d "${tmp_root%/}/oreslang-cli-demo.XXXXXX")"

cleanup() {
  rm -rf "$build_dir"
}
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

compiler="${ORESLANG_COMPILER:-oreslang-compiler}"
cat math.ores messages.ores main.ores > "$build_dir/main.ores"

"$compiler" --platform=server "$build_dir/main.ores"
