#!/usr/bin/env bash
set -euo pipefail
mkdir -p /runtime/lib
cp -R src resources /runtime/
classpath=/app/src:/app/resources
IFS=: read -r -a dependencies <<< "$(clojure -Spath)"
index=0
for dependency in "${dependencies[@]}"; do
  case "$dependency" in src|resources) continue ;; esac
  # Clojure tolerates absent optional source directories in dependency paths.
  [[ -e "$dependency" ]] || continue
  if [[ -d "$dependency" ]]; then
    target="lib/dependency-$index"
    cp -R "$dependency" "/runtime/$target"
  else
    target="lib/dependency-$index.jar"
    cp "$dependency" "/runtime/$target"
  fi
  classpath="$classpath:/app/$target"
  index=$((index + 1))
done
printf '%s\n' "$classpath" > /runtime/classpath
