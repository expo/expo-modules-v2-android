#!/usr/bin/env bash

set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <repository dir> [<deployment name>]" >&2
  exit 2
fi

repository=$1
name=${2:-expo-modules-v2}
portal=${CENTRAL_PORTAL_URL:-https://central.sonatype.com}
: "${MAVEN_CENTRAL_USERNAME:?is not set}" "${MAVEN_CENTRAL_PASSWORD:?is not set}"

token=$(printf '%s:%s' "$MAVEN_CENTRAL_USERNAME" "$MAVEN_CENTRAL_PASSWORD" | base64 | tr -d '\n')

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

portal_post() {
  local path=$1
  shift
  local status
  status=$(curl --silent --show-error --request POST \
    --header "Authorization: Bearer $token" \
    --output "$work/response" --write-out '%{http_code}' \
    "$@" "$portal/api/v1/publisher/$path")
  if [[ $status != 2?? ]]; then
    echo "POST /api/v1/publisher/$path failed with HTTP $status: $(cat "$work/response")" >&2
    return 1
  fi
  cat "$work/response"
}

(
  cd "$repository"
  find . -type f \
    ! -name '*maven-metadata*' \
    ! -name '*.sha256' ! -name '*.sha512' \
    ! -name '*.asc.md5' ! -name '*.asc.sha1' \
    | sed 's|^\./||' | sort
) > "$work/files"

if [[ ! -s $work/files ]]; then
  echo "No files to publish in $repository" >&2
  exit 1
fi

(cd "$repository" && zip -q "$work/bundle.zip" -@ < "$work/files")
echo "Bundle: $(wc -l < "$work/files" | tr -d ' ') files, $(du -h "$work/bundle.zip" | cut -f1)"

deployment=$(portal_post "upload?publishingType=AUTOMATIC&name=$(jq -rn --arg name "$name" '$name | @uri')" \
  --form "bundle=@$work/bundle.zip")
echo "Uploaded deployment $deployment"
