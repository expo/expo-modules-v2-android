#!/usr/bin/env bash

# Compares a locally built jar with the one Maven Central holds at the same coordinates, entry by
# entry (the zip timestamps differ, the contents of a reproducible build do not). Prints
# `unpublished` when Central has no such jar and `published` when it holds the same one. Exits with
# 3 when Central holds a different jar under these coordinates, and with 1 when it cannot tell.

set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <jar> <path in the repository, e.g. io/github/expo/a/1.0/a-1.0.jar>" >&2
  exit 2
fi

jar=$1
path=$2
repository=${MAVEN_CENTRAL_REPOSITORY_URL:-https://repo1.maven.org/maven2}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

status=$(curl --silent --show-error --location \
  --output "$work/central.jar" --write-out '%{http_code}' \
  "$repository/$path")
case $status in
  404)
    echo unpublished
    exit 0
    ;;
  200) ;;
  *)
    echo "GET $repository/$path failed with HTTP $status" >&2
    exit 1
    ;;
esac

mkdir "$work/local" "$work/central"
unzip -q "$jar" -d "$work/local"
unzip -q "$work/central.jar" -d "$work/central"

if ! differences=$(diff -rq "$work/local" "$work/central"); then
  echo "$path is already on Maven Central, with different contents than $jar:" >&2
  echo "${differences//$work\//}" >&2
  exit 3
fi

echo published
