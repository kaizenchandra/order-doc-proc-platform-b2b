#!/usr/bin/env sh
# Check the deployment image against the running local dependencies, without modifying the regular service container.
set -eu
cd "$(dirname "$0")/../.."
mkdir -p target
runtime_env=$(mktemp "$PWD/target/runtime-env.XXXXXX")
runtime_name="order-runtime-check-$$"
cleanup() {
  docker rm -f "$runtime_name" >/dev/null 2>&1 || true
  rm -f "$runtime_env"
}
trap cleanup EXIT HUP INT TERM
docker compose config --format json | python3 -c '
import json, sys
config = json.load(sys.stdin)
for key, value in config["services"]["order-service"]["environment"].items():
    print(f"{key}={value}")
' > "$runtime_env"
docker build -f services/order-service/Dockerfile -t order-service:runtime-check .
docker run -d --name "$runtime_name" --network order-platform-local_local \
  --read-only --tmpfs /tmp:rw,nosuid,size=128m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true --memory 1g --cpus 1 \
  --env-file "$runtime_env" -p 127.0.0.1:18080:8080 order-service:runtime-check >/dev/null
python3 - <<'PY'
import json
import sys
from urllib.error import URLError
from urllib.request import urlopen
sys.path.insert(0, 'scripts/local')
import smoke

def ready():
    try:
        with urlopen('http://localhost:18080/readyz', timeout=5) as response:
            return json.load(response) == {'status': 'UP'}
    except (OSError, URLError):
        return False

smoke.wait_for(ready, 'restricted runtime readiness', timeout=180)
smoke.API = 'http://localhost:18080/api/v1/orders'
smoke.main()
print('PASS: non-root deployment image with read-only root filesystem and bounded writable /tmp')
PY
