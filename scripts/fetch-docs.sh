#!/usr/bin/env bash
# Downloads the Spring Boot reference documentation (AsciiDoc sources, Apache-2.0) at a fixed tag,
# so ingestion and the retrieval eval are reproducible.
set -euo pipefail

TAG="${1:-v4.1.1}"
TARGET="$(cd "$(dirname "$0")/.." && pwd)/data/spring-boot-docs"
DOCS_PATH="documentation/spring-boot-docs/src/docs/antora"

rm -rf "$TARGET"
git clone --quiet --depth 1 --branch "$TAG" --filter=blob:none --sparse \
  https://github.com/spring-projects/spring-boot.git "$TARGET"
git -C "$TARGET" sparse-checkout set "$DOCS_PATH"

echo "Spring Boot $TAG docs: $(find "$TARGET/$DOCS_PATH" -name '*.adoc' | wc -l) AsciiDoc files in $TARGET/$DOCS_PATH"
