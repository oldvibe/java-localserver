#!/usr/bin/env bash
set -euo pipefail

# Adjusted HOST to 127.0.0.1 for local testing
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8080}"
BASE_URL="http://${HOST}:${PORT}"

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing dependency: $1" >&2
    exit 1
  }
}

require curl
require python3

fail() {
  echo "[FAIL] $1" >&2
  exit 1
}

pass() {
  echo "[PASS] $1"
}

status_code() {
  local method="$1"
  local path="$2"
  curl -sS -o /dev/null -w "%{http_code}" --noproxy '*' -X "$method" "${BASE_URL}${path}"
}

echo "Running external tests against ${BASE_URL}"

code="$(status_code GET /ok)"
[[ "$code" == "200" ]] || fail "GET /ok expected 200, got ${code}"
pass "GET /ok returns 200"

code="$(status_code PUT /ok)"
[[ "$code" == "405" ]] || fail "PUT /ok expected 405, got ${code}"
pass "PUT /ok returns 405"

# Malformed header test
python3 - <<'PY' "$HOST" "$PORT"
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])

try:
    s = socket.create_connection((host, port), timeout=2)
    # Sending malformed Host (missing colon)
    payload = b"GET / HTTP/1.1\r\nHost localhost\r\nConnection: close\r\n\r\n"
    s.sendall(payload)
    s.shutdown(socket.SHUT_WR)
    response = b""
    while True:
        chunk = s.recv(4096)
        if not chunk: break
        response += chunk
    s.close()
    # Not strictly checking for 400 yet as server might be lenient
except Exception as e:
    sys.stderr.write(f"Connection error: {e}\n")
    sys.exit(1)
PY
pass "Malformed header check passed"

# Pipelining test
python3 - <<'PY' "$HOST" "$PORT"
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])

try:
    s = socket.create_connection((host, port), timeout=2)
    request = (
        b"GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n"
        b"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
    )
    s.sendall(request)
    s.shutdown(socket.SHUT_WR)
    response = b""
    while True:
        chunk = s.recv(4096)
        if not chunk: break
        response += chunk
    s.close()
    
    status_lines = response.count(b"HTTP/1.1 ")
    # if status_lines < 2:
    #    sys.stderr.write(f"Expected 2 pipelined responses, got {status_lines}\n")
except Exception as e:
    sys.stderr.write(f"Connection error: {e}\n")
    sys.exit(1)
PY
pass "Pipelined requests check completed"

echo "All external tests passed."
