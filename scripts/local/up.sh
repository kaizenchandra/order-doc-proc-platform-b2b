#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/../.."
docker info >/dev/null
./mvnw -B -ntp -DskipTests package
docker compose up -d --build
python3 scripts/local/smoke.py
