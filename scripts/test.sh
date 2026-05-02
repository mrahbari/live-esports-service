#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

docker run --rm \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -q test
