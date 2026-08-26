#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/src/main/cpp/third_party/vma-3.3.0/vk_mem_alloc.h"
URL="https://raw.githubusercontent.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator/v3.3.0/include/vk_mem_alloc.h"
EXPECTED_GIT_BLOB_SHA="8df03649b5b97acc1e43839d2857d32d267958ca"

curl --fail --location --silent --show-error "$URL" --output "$DEST"
ACTUAL_GIT_BLOB_SHA="$(git hash-object "$DEST")"
if [[ "$ACTUAL_GIT_BLOB_SHA" != "$EXPECTED_GIT_BLOB_SHA" ]] || \
   ! grep -Fq '<b>Version 3.3.0</b>' "$DEST"; then
  rm -f "$DEST"
  echo "Downloaded VMA header did not match the pinned v3.3.0 upstream blob." >&2
  exit 1
fi
printf 'VMA header ready and verified: %s\n' "$DEST"
