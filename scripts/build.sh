#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Use the robust clean script first
"${ROOT_DIR}/scripts/clean.sh"

# Ensure .m2 exists locally so docker doesn't create it as root
mkdir -p "${HOME}/.m2"

echo "==> Building project..."
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "${ROOT_DIR}:/workspace" \
  -v "${HOME}/.m2:/var/maven/.m2" \
  -e MAVEN_CONFIG=/var/maven/.m2 \
  -w /workspace \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -q package -DskipTests -Duser.home=/var/maven
