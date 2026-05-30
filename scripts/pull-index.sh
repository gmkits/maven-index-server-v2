#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/pull-index.sh --repo OWNER/REPO [--base-dir /data/maven-index] [--reload-url http://localhost:8080/admin/reload]

Requires:
  gh, curl, tar, zstd, sha256sum

Authentication:
  For private repositories, run `gh auth login` or set GH_TOKEN before invoking this script.
USAGE
}

REPO=""
BASE_DIR="/data/maven-index"
RELOAD_URL="http://localhost:8080/admin/reload"
KEEP_RELEASES=5

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      REPO="${2:?--repo requires OWNER/REPO}"
      shift 2
      ;;
    --base-dir)
      BASE_DIR="${2:?--base-dir requires a path}"
      shift 2
      ;;
    --reload-url)
      RELOAD_URL="${2:?--reload-url requires a URL}"
      shift 2
      ;;
    --keep-releases)
      KEEP_RELEASES="${2:?--keep-releases requires a number}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$REPO" ]]; then
  echo "--repo OWNER/REPO is required" >&2
  usage >&2
  exit 2
fi

for cmd in gh curl tar zstd sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Missing required command: $cmd" >&2
    exit 127
  }
done

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

tag="$(
  gh release list \
    --repo "$REPO" \
    --limit 100 \
    --json tagName \
    --jq '[.[] | select(.tagName | startswith("index-"))][0].tagName'
)"

if [[ -z "$tag" || "$tag" == "null" ]]; then
  echo "No index-* release found in $REPO" >&2
  exit 1
fi

echo "Downloading $tag from $REPO..."
gh release download \
  "$tag" \
  --repo "$REPO" \
  --pattern 'maven-index-*.tar.zst' \
  --pattern 'SHA256SUMS' \
  --dir "$work_dir" \
  --clobber

(
  cd "$work_dir"
  sha256sum -c SHA256SUMS
)

package="$(find "$work_dir" -maxdepth 1 -type f -name 'maven-index-*.tar.zst' | sort | tail -n 1)"
if [[ -z "$package" ]]; then
  echo "No maven-index-*.tar.zst asset was downloaded" >&2
  exit 1
fi

release_id="$(basename "$package" .tar.zst)"
releases_dir="$BASE_DIR/releases"
target_dir="$releases_dir/$release_id"
staging_dir="$releases_dir/.${release_id}.staging"

mkdir -p "$releases_dir"
rm -rf "$staging_dir"
mkdir -p "$staging_dir"

echo "Extracting $release_id..."
tar --zstd -xf "$package" -C "$staging_dir"

(
  cd "$staging_dir"
  sha256sum -c SHA256SUMS
  test -s maven-index.db
  test -s metadata.json
  grep -Eq '"sourceSha1"[[:space:]]*:[[:space:]]*"[0-9a-f]{40}"' metadata.json
  grep -Eq '"sourceSha256"[[:space:]]*:[[:space:]]*"[0-9a-f]{64}"' metadata.json
  grep -Eq '"configHash"[[:space:]]*:[[:space:]]*"[0-9a-f]{64}"' metadata.json
)

rm -rf "$target_dir"
mv "$staging_dir" "$target_dir"

current_tmp="$BASE_DIR/current.new"
ln -sfn "$target_dir" "$current_tmp"
mv -Tf "$current_tmp" "$BASE_DIR/current"

echo "Reloading service..."
curl -fsS -X POST "$RELOAD_URL" >/dev/null

echo "Pruning old local releases..."
find "$releases_dir" -mindepth 1 -maxdepth 1 -type d -name 'maven-index-*' \
  | sort -r \
  | tail -n +"$((KEEP_RELEASES + 1))" \
  | xargs -r rm -rf

echo "Activated $target_dir"
