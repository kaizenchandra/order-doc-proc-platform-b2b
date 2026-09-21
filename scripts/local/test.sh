#!/usr/bin/env sh
# Layered verification; no data reset and no cloud credentials.
set -eu
cd "$(dirname "$0")/../.."
./mvnw -B -ntp verify
docker compose up -d --build
python3 scripts/local/smoke.py
python3 scripts/local/regression.py
