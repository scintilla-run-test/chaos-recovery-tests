#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"
tmp_root="${TMPDIR:-/tmp}"
build_dir="$(mktemp -d "${tmp_root%/}/oreslang-java-interop.XXXXXX")"

cleanup() {
  rm -rf "$build_dir"
}
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

compiler="${ORESLANG_COMPILER:-oreslang-compiler}"
"$compiler" --platform=server OreslangEmbedsJava.ores > "$build_dir/HelloFromGeneratedJava.java"

grep -q 'public final class HelloFromGeneratedJava' "$build_dir/HelloFromGeneratedJava.java"
java "$build_dir/HelloFromGeneratedJava.java"
