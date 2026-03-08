#!/usr/bin/env bash

# --- Comprehensive Unified Test Suite for Java LocalServer ---
# Merged from: audit-test.sh, external-test.sh, final-test.sh, login-test.sh
# Features: Unit tests, Integration tests, Load testing (Siege), Security checks.

set -euo pipefail

# --- Configuration ---
PORT=8080
HOST="127.0.0.1"
BASE_URL="http://${HOST}:${PORT}"
JAR_PATH="target/java-localserver-1.0-SNAPSHOT.jar"
CONFIG="config.json"
AUDIT_CONFIG="audit.json"
SERVER_LOG="server_test.log"
TEST_BODY="test_body.txt"
FAILED=0

# --- Formatting Utilities ---
green()  { echo -e "\033[0;32m$1\033[0m"; }
red()    { echo -e "\033[0;31m$1\033[0m"; }
yellow() { echo -e "\033[0;33m$1\033[0m"; }

header() {
    echo "=================================================="
    echo "  $1"
    echo "=================================================="
}

pass() { echo -e "[PASS] $1"; }
fail() { echo -e "[FAIL] $1" >&2; FAILED=1; }

# --- Dependencies ---
require() {
    command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1" >&2; exit 1; }
}

require curl
require python3
require java

# --- Cleanup & Setup ---
cleanup() {
    echo "Stopping server if running..."
    lsof -ti:$PORT | xargs kill -9 2>/dev/null || true
    rm -rf www/large-test/
    rm -rf www/test_dir/
    rm -rf www/uploads/
    rm -f "$TEST_BODY"
    rm -f cgi-bin/test_cgi.py
    rm -f cgi-bin/echo.py
    rm -f cgi-bin/env.py
}

setup_assets() {
    echo "Setting up test assets..."
    mkdir -p www/large-test/
    mkdir -p www/test_dir/
    mkdir -p www/uploads/
    mkdir -p cgi-bin/
    
    echo "test file 1" > www/test_dir/file1.txt
    echo "<h1>Servex Home</h1>" > www/index.html
    echo "ok" > www/ok
    
    # Create random binary files for testing
    head -c 10M </dev/urandom > www/large-test/largefile.bin
    head -c 1M </dev/urandom > www/large-test/mediumfile.bin
    
    # CGI: Simple Hello
    cat <<EOF > cgi-bin/test_cgi.py
#!/usr/bin/env python3
print('Content-type: text/html')
print('')
print('Hello from CGI!')
EOF
    
    # CGI: Echo
    cat << 'EOF' > cgi-bin/echo.py
#!/usr/bin/env python3
import sys
import os
sys.stdout.write('Content-type: text/plain\n\n')
sys.stdout.write('CGI ECHO\n')
if os.environ.get('REQUEST_METHOD') == 'POST':
    sys.stdout.write(sys.stdin.read())
EOF

    # CGI: Env
    cat << 'EOF' > cgi-bin/env.py
#!/usr/bin/env python3
print("Content-type: text/plain\r\n\r\n", end="")
import os
for k, v in os.environ.items():
    print(f"{k}={v}")
EOF

    chmod +x cgi-bin/*.py
}

# --- Build ---
build() {
    header "BUILDING PROJECT"
    ./mvnw clean package -DskipTests
}

# --- Server Management ---
start_server() {
    local cfg=${1:-$CONFIG}
    echo "Starting server with $cfg..."
    java -jar "$JAR_PATH" "$cfg" >> "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    echo "Server started (PID: $SERVER_PID). Waiting for ready state..."
    
    local ready=0
    for i in {1..40}; do
        if curl -sS --noproxy '*' "$BASE_URL/ok" >/dev/null 2>&1; then
            ready=1
            break
        fi
        sleep 0.25
    done
    
    if [ $ready -eq 0 ]; then
        red "Server failed to start. Check $SERVER_LOG"
        exit 1
    fi
}

stop_server() {
    if [[ -n "${SERVER_PID:-}" ]]; then
        kill -9 "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}

# --- Test Sections ---

run_basic_tests() {
    header "RUNNING BASIC INTEGRATION TESTS"
    
    # GET index
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE_URL/")
    [[ "$code" == "200" ]] && pass "GET / returns 200" || fail "GET / expected 200, got $code"

    # CGI script
    if curl -s "$BASE_URL/cgi-bin/test_cgi.py" | grep -q "Hello from CGI"; then
        pass "CGI Execution (test_cgi.py)"
    else
        fail "CGI Execution failed or output mismatch"
    fi

    # Directory Listing
    if curl -s "$BASE_URL/test_dir/" | grep -q "Directory listing"; then
        pass "Directory Listing (/test_dir/)"
    else
        fail "Directory Listing failed"
    fi

    # File integrity
    curl -s "$BASE_URL/test_dir/file1.txt" -o "$TEST_BODY"
    if diff www/test_dir/file1.txt "$TEST_BODY" > /dev/null; then
        pass "File Integrity (file1.txt)"
    else
        fail "File Integrity check failed"
    fi
}

run_audit_tests() {
    header "RUNNING AUDIT COMPLIANCE TESTS"
    
    # POST upload
    code=$(curl -sS -o /dev/null -w "%{http_code}" --noproxy '*' -X POST -F "file=@.mvn/wrapper/maven-wrapper.properties" "$BASE_URL/uploads")
    [[ "$code" == "201" ]] && pass "POST /uploads returns 201" || fail "POST /uploads expected 201, got $code"
    
    if [ -f "www/uploads/maven-wrapper.properties" ]; then
        pass "Uploaded file exists on disk"
    else
        fail "Uploaded file not found in www/uploads/"
    fi

    # DELETE
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' -X DELETE "$BASE_URL/uploads/maven-wrapper.properties")
    [[ "$code" == "204" ]] && pass "DELETE /uploads/... returns 204" || fail "DELETE expected 204, got $code"

    # Redirection
    location=$(curl -sS -I --noproxy '*' "$BASE_URL/temp" | grep -i '^Location:' | awk '{print $2}' | tr -d '\r\n')
    [[ "$location" == "https://example.com" ]] && pass "Redirection /temp -> example.com" || fail "Redirect mismatch: '$location'"

    # Body Size Limit (Requires audit.json with 1MB limit)
    echo "Testing body size limit (using audit configuration)..."
    stop_server
    start_server "$AUDIT_CONFIG"
    
    LARGE_FILE="/tmp/large_test_body.bin"
    python3 -c "print('x' * (1024 * 1024 + 100))" > "$LARGE_FILE"
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' -X POST "$BASE_URL/uploads" --data-binary "@$LARGE_FILE")
    rm -f "$LARGE_FILE"
    [[ "$code" == "413" ]] && pass "Body size limit check (413)" || fail "Expected 413 for large body, got $code"
    
    # Restore default server for remaining tests
    stop_server
    start_server
}

run_external_security_tests() {
    header "RUNNING EXTERNAL & SECURITY TESTS"

    # Malformed Host Header
    echo -n "[TEST] Malformed Header (No colon)  : "
    python3 - <<'PY' "$HOST" "$PORT"
import socket, sys
s = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=2)
s.sendall(b"GET / HTTP/1.1\r\nHost localhost\r\nConnection: close\r\n\r\n")
res = s.recv(4096); s.close()
if b"400" in res or b"Bad Request" in res: sys.exit(0)
else: sys.exit(1)
PY
    if [ $? -eq 0 ]; then pass "SUCCESS"; else fail "FAILED (Expected 400)"; fi

    # Path Traversal
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' --path-as-is "$BASE_URL/test_dir/../index.html")
    [[ "$code" == "403" ]] && pass "Path Traversal Protection (403)" || fail "Traversal expected 403, got $code"

    # HTTP/1.1 Missing Host Header
    echo -n "[TEST] Missing Host Header (HTTP/1.1): "
    python3 - <<'PY' "$HOST" "$PORT"
import socket, sys
s = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=2)
s.sendall(b"GET /ok HTTP/1.1\r\nConnection: close\r\n\r\n")
res = s.recv(4096); s.close()
if b"400" in res: sys.exit(0)
else: sys.exit(1)
PY
    if [ $? -eq 0 ]; then pass "SUCCESS"; else fail "FAILED (Expected 400)"; fi
}

run_session_login_tests() {
    header "RUNNING SESSION & LOGIN TESTS"
    
    COOKIE_FILE=$(mktemp)
    
    # 1. Access protected
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE_URL/protected.html")
    [[ "$code" == "302" ]] && pass "Unauthorized access redirected" || fail "Expected 302 for /protected.html, got $code"

    # 2. Login
    code=$(curl -s -c "$COOKIE_FILE" -o /dev/null -w "%{http_code}" --noproxy '*' -X POST -d "username=admin&password=admin" "$BASE_URL/login")
    [[ "$code" == "302" ]] && pass "Login successful (302)" || fail "Login failed, got $code"

    # 3. Access protected with cookie
    code=$(curl -s -b "$COOKIE_FILE" -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE_URL/protected.html")
    [[ "$code" == "200" ]] && pass "Authorized access allowed" || fail "Expected 200 with cookie, got $code"

    # 4. Metrics API Check
    if curl -s "$BASE_URL/metrics" | grep -q "Requests:"; then
        pass "Metrics API returns valid data"
    else
        fail "Metrics API failed"
    fi

    # 5. Logout
    curl -s -b "$COOKIE_FILE" -o /dev/null --noproxy '*' "$BASE_URL/logout"
    code=$(curl -s -b "$COOKIE_FILE" -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE_URL/protected.html")
    [[ "$code" == "302" ]] && pass "Logout works (re-redirected)" || fail "Logout failed, got $code"

    rm -f "$COOKIE_FILE"
}

run_pipelining_tests() {
    header "RUNNING HTTP KEEP-ALIVE TESTS"
    
    echo -n "[TEST] Sequential requests (Keep-Alive): "
    python3 - <<'PY' "$HOST" "$PORT"
import socket, sys, time
try:
    s = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=5)
    
    # Request 1
    s.sendall(b"GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n")
    res1 = b""
    while b"\r\n\r\n" not in res1:
        data = s.recv(4096)
        if not data: break
        res1 += data
    
    # Request 2 on same socket
    s.sendall(b"GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
    res2 = b""
    while True:
        data = s.recv(4096)
        if not data: break
        res2 += data
    s.close()
    
    if b"200 OK" in res1 and b"200 OK" in res2:
        sys.exit(0)
    else:
        sys.stderr.write(f"Failed: Res1 contains 200: {b'200 OK' in res1}, Res2 contains 200: {b'200 OK' in res2}\n")
        sys.exit(1)
except Exception as e:
    sys.stderr.write(f"Keep-Alive error: {e}\n")
    sys.exit(1)
PY
    if [ $? -eq 0 ]; then pass "SUCCESS"; else fail "FAILED"; fi
}

run_advanced_integration_tests() {
    header "RUNNING ADVANCED INTEGRATION TESTS"

    # Test Chunked Upload
    echo -n "[TEST] Chunked Transfer Upload      : "
    echo "chunked-data-content" > chunked_sample.txt
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' -H "Transfer-Encoding: chunked" -X POST --data-binary @chunked_sample.txt "$BASE_URL/uploads")
    if [[ "$code" == "201" ]]; then pass "SUCCESS"; else fail "FAILED (got $code)"; fi
    rm -f chunked_sample.txt

    # Test Large Headers
    echo -n "[TEST] Large Headers check          : "
    # Create a 10KB header
    LARGE_HDR=$(python3 -c "print('X' * 10000)")
    code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' -H "X-Large: $LARGE_HDR" "$BASE_URL/ok")
    # MAX_REQUEST_SIZE is around 80KB, so 10KB should pass. 
    # If we sent 100KB it should 413.
    [[ "$code" == "200" ]] && pass "SUCCESS (Accepted 10KB)" || fail "FAILED (got $code)"

    # Test 405 with Allow header
    echo -n "[TEST] 405 Method Not Allowed + Allow: "
    allow_hdr=$(curl -sS -I --noproxy '*' -X DELETE "$BASE_URL/ok" | grep -i '^Allow:' | awk '{print $2}' | tr -d '\r\n')
    # /ok route allows GET and HEAD (in audit.json) or just GET (in config.json)
    if [[ "$allow_hdr" == *"GET"* ]]; then
        pass "SUCCESS (Allow: $allow_hdr)"
    else
        fail "FAILED (Allow: '$allow_hdr')"
    fi
}

run_load_test() {
    header "RUNNING LOAD TEST (SIEGE)"
    if ! command -v siege &> /dev/null; then
        yellow "Siege is not installed. Skipping load test."
        return
    fi
    echo "Executing: siege -c 50 -t 10s $BASE_URL/"
    siege -c 50 -t 10s "$BASE_URL/"
}

# --- Execution ---

trap cleanup EXIT

cleanup
setup_assets
build
start_server

run_basic_tests
run_audit_tests
run_external_security_tests
run_session_login_tests
run_pipelining_tests
run_advanced_integration_tests
run_load_test

header "FINAL TEST SUMMARY"
if [ $FAILED -eq 0 ]; then
    green "✅ ALL TESTS PASSED SUCCESSFULLY"
else
    red "❌ SOME TESTS FAILED"
    exit 1
fi
