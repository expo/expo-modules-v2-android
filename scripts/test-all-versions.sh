#!/usr/bin/env bash
# Runs the Kotlin-dependent checks against every supported Kotlin release (the same tasks the CI
# matrix runs, see .github/workflows/ci.yml) and prints one line per release.
#
# Usage:
#   scripts/test-all-versions.sh              # every release in gradle/kotlin-versions.txt
#   scripts/test-all-versions.sh 2.3.20 2.4.0 # a subset
#
# Failing builds keep their Gradle output in build/test-all-versions/<version>.log.

set -uo pipefail
cd "$(dirname "$0")/.."

if [[ $# -gt 0 ]]; then
  versions=("$@")
else
  read -r -a versions <<< "$(tr '\n' ' ' < gradle/kotlin-versions.txt)"
fi

log_dir=build/test-all-versions
mkdir -p "$log_dir"

failed=()
for version in "${versions[@]}"; do
  printf 'Kotlin %-8s ' "$version"
  # -PskipDesktopNative: these checks cover the JVM side only; :test-app:test exercises the native
  # side against the catalog's Kotlin.
  if ./gradlew :compiler-plugin:test :gradle-plugin:build :api:test \
      -PkotlinVersion="$version" -PskipHermesEnvBuild -PskipDesktopNative --continue \
      > "$log_dir/$version.log" 2>&1; then
    echo "PASS"
    rm -f "$log_dir/$version.log"
  else
    echo "FAIL  ($log_dir/$version.log)"
    failed+=("$version")
  fi
done

echo
if [[ ${#failed[@]} -eq 0 ]]; then
  echo "All ${#versions[@]} Kotlin releases passed."
else
  echo "Failed: ${failed[*]}"
  exit 1
fi
