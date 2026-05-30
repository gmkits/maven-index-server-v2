#!/usr/bin/env bash
set -euo pipefail

# Release management script for maven-index-server
# Usage: ./release.sh <db-file> [base-dir]

DB_FILE="${1:?Usage: $0 <db-file> [base-dir]}"
BASE_DIR="${2:-/data/maven-index}"
RELEASE_NAME="$(date +%Y%m%d-%H%M%S)"
RELEASE_DIR="${BASE_DIR}/releases/${RELEASE_NAME}"

echo "=== Maven Index Release ==="
echo "Source:  ${DB_FILE}"
echo "Target:  ${RELEASE_DIR}"

# Validate database
echo "Validating..."
ARTIFACT_COUNT=$(sqlite3 "${DB_FILE}" "SELECT count(*) FROM artifacts" 2>/dev/null)
if [ -z "${ARTIFACT_COUNT}" ] || [ "${ARTIFACT_COUNT}" -eq 0 ]; then
    echo "ERROR: Invalid database or empty artifacts table"
    exit 1
fi
echo "Artifacts: ${ARTIFACT_COUNT}"

# Copy database + metadata
echo "Copying..."
mkdir -p "${RELEASE_DIR}"
cp "${DB_FILE}" "${RELEASE_DIR}/maven-index.db"

# Generate metadata if not present
if [ -f "${BASE_DIR}/current/metadata.json" ]; then
    cp "${BASE_DIR}/current/metadata.json" "${RELEASE_DIR}/metadata.json"
else
    echo "{\"artifactCount\":${ARTIFACT_COUNT},\"releasedAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" > "${RELEASE_DIR}/metadata.json"
fi

# Atomic symlink switch
CURRENT_LINK="${BASE_DIR}/current"
TEMP_LINK="${CURRENT_LINK}.new"

echo "Switching symlink..."
ln -sfn "${RELEASE_DIR}" "${TEMP_LINK}"
mv -f "${TEMP_LINK}" "${CURRENT_LINK}"

echo "Done! Released ${RELEASE_NAME} (${ARTIFACT_COUNT} artifacts)"
echo ""
echo "To reload in Go server:"
echo "  curl -X POST http://localhost:8080/admin/reload"
echo ""
echo "Directory structure:"
ls -la "${BASE_DIR}/current"
