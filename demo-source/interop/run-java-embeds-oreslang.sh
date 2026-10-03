#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

if [ -n "${ORESLANG_CLASSPATH:-}" ]; then
  classpath="$ORESLANG_CLASSPATH"
else
  source_dir="${ORESLANG_SOURCE_DIR:-}"
  if [ -z "$source_dir" ]; then
    echo "set ORESLANG_SOURCE_DIR to a local oreslang-source.java checkout, or set ORESLANG_CLASSPATH" >&2
    exit 2
  fi

  source_dir="$(cd "$source_dir" && pwd)"
  (
    cd "$source_dir"
    mvn -q -DskipTests package dependency:build-classpath \
      -Dmdep.outputFile=target/interop.classpath
  )
  classpath="$source_dir/target/classes:$(cat "$source_dir/target/interop.classpath")"
fi

exec java -cp "$classpath" JavaEmbedsOreslang.java
