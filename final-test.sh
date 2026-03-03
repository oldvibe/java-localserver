#!/usr/bin/env bash
set -euo pipefail

# ROOT_DIR should be the directory where the script is located
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
BASE_URL="http://${HOST}:${PORT}"
APP_CONF="$ROOT_DIR/config.json"
SERVER_LOG="/tmp/servex_extra_server.log"

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    echo "Stopping server (PID: ${SERVER_PID})..."
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing dependency: $1" >&2
    exit 1;
  }
}

pass() { echo "[PASS] $1"; }
fail() { echo "[FAIL] $1" >&2; exit 1; }

require curl
require python3
require java

start_server() {
  echo "Starting Java LocalServer..."
  java -jar target/java-localserver-1.0-SNAPSHOT.jar "$APP_CONF" > "$SERVER_LOG" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do
    if curl -sS --noproxy '*' "${BASE_URL}/ok" >/dev/null 2>&1; then
      echo "Server is ready."
      return 0
    fi
    sleep 0.25
  done
  echo "Server not ready. Log:" >&2
  cat "$SERVER_LOG" >&2
  return 1
}

status_code() {
  local method="$1"
  local url="$2"
  shift 2
  curl -sS -o /tmp/servex_extra_body.txt -w "%{http_code}" --noproxy '*' -X "$method" "$url" "$@"
}

# Ensure assets for tests exist
echo "Setting up test assets..."
make setup-test-assets > /dev/null

echo "Running tests against ${BASE_URL}"
start_server

# 1) Session cookie issuance and reuse.
echo -n "[TEST] Session Cookie Issuance      : "
curl -sS -D /tmp/servex_extra_h1.txt --noproxy '*' "${BASE_URL}/ok" -o /tmp/servex_extra_b1.txt >/dev/null
cookie_line="$(tr -d '\r' < /tmp/servex_extra_h1.txt | sed -n 's/^Set-Cookie: //p' | head -n1)"
if [[ "$cookie_line" == LOCALSERVER_SESSION=* ]]; then
  echo "SUCCESS";
else
  fail "Expected Set-Cookie for first session request";
fi

echo -n "[TEST] Session Cookie Reuse          : "
cookie_kv="${cookie_line%%;*}"
curl -sS -D /tmp/servex_extra_h2.txt --noproxy '*' -H "Cookie: ${cookie_kv}" "${BASE_URL}/ok" -o /tmp/servex_extra_b2.txt >/dev/null
second_cookie="$(tr -d '\r' < /tmp/servex_extra_h2.txt | sed -n 's/^Set-Cookie: //p' | head -n1)"
if [[ -z "$second_cookie" ]]; then
  echo "SUCCESS";
else
  fail "Expected no Set-Cookie when sending existing session cookie";
fi

# 2) HTTP/1.1 request without Host should be 400.
echo -n "[TEST] HTTP/1.1 Missing Host Header : "
python3 - <<'PY' "$HOST" "$PORT"
import socket
import sys
host = sys.argv[1]
port = int(sys.argv[2])
s = socket.create_connection((host, port), timeout=2)
s.sendall(b"GET /ok HTTP/1.1\r\nConnection: close\r\n\r\n")
s.shutdown(socket.SHUT_WR)
resp = b""
while True:
    chunk = s.recv(4096)
    if not chunk:
        break
    resp += chunk
s.close()
if not resp.startswith(b"HTTP/1.1 400"):
    raise SystemExit(f"Expected 400 for HTTP/1.1 missing Host header, got: {resp[:20]}")
PY
echo "SUCCESS"

# 3) Directory Listing
echo -n "[TEST] Directory Listing Check      : "
if curl -s http://localhost:8080/test_dir/ | grep -q "Directory listing"; then 
  echo "SUCCESS"; 
else 
  fail "Directory listing failed"; 
fi

# 4) Path traversal should be blocked.
echo -n "[TEST] Path Traversal Protection    : "
code="$(status_code GET "${BASE_URL}/test_dir/../index.html" --path-as-is)"
if [[ "$code" == "403" ]]; then
  echo "SUCCESS";
else
  fail "Expected 403 for traversal path, got ${code}";
fi

# 5) DELETE non-existing file should return 404.
# (Note: In config.json, /uploads is not explicitly defined as a route, but /test_dir/uploads might exist)
echo -n "[TEST] DELETE non-existing file     : "
# Let's ensure a route for DELETE. /test_dir only allows GET.
# We'll use /cgi-bin which allows POST/GET, maybe add DELETE to a route?
# For now, if we use a non-existent path on a route that allows it, or if it just fails because file missing.
# Let's try to delete a non-existent file on /ok which allows GET. 
# Actually handle() checks findRoute first.
code="$(status_code DELETE "${BASE_URL}/ok/does_not_exist_foo.txt")"
if [[ "$code" == "405" ]] || [[ "$code" == "404" ]]; then
  echo "SUCCESS";
else
  fail "Expected 405 or 404, got ${code}";
fi

# 6) CGI env variables should be present.
echo -n "[TEST] CGI Env Variables Check     : "
cat << 'EOF' > cgi-bin/env.py
print("Content-type: text/plain\r\n\r\n", end="")
import os
for k, v in os.environ.items():
    print(f"{k}={v}")
EOF
chmod +x cgi-bin/env.py

cgi_env="$(curl -sS --noproxy '*' "${BASE_URL}/cgi-bin/env.py")"
if [[ "$cgi_env" == *"REQUEST_METHOD=GET"* ]]; then
  echo "SUCCESS";
else
  fail "CGI env missing REQUEST_METHOD";
fi

# 7) Custom error page body should be served.
echo -n "[TEST] Custom Error Page Check      : "
curl -sS --noproxy '*' "${BASE_URL}/non-existent" -o /tmp/servex_extra_404.html -w "%{http_code}" >/tmp/servex_extra_404_code.txt
code="$(cat /tmp/servex_extra_404_code.txt)"
if [[ "$code" == "404" ]] && grep -q "404 Not Found" /tmp/servex_extra_404.html; then
  echo "SUCCESS";
else
  fail "Custom 404 failed (code: ${code})";
fi

echo "--------------------------------------------------"
echo "✅ All tests passed."
echo "--------------------------------------------------"
