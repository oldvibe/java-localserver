# Maven Wrapper (./mvnw) is now used for all build and run commands.

.PHONY: all compile package run clean test audit-test external-test complex-test re

all: package

re: clean all

compile:
	./mvnw compile

package:
	./mvnw package

run:
	./mvnw exec:java -Dexec.mainClass="com.localserver.Main" -Dexec.args="config.json"

clean:
	./mvnw clean

test:
	curl -v http://localhost:8080/
	curl -v http://localhost:8080/metrics

# --- Complex Tests (Unit Test Style) ---

setup-test-assets:
	@mkdir -p www/large-test/
	@mkdir -p www/test_dir/
	@if [ ! -f www/test_dir/file1.txt ]; then echo "test file 1" > www/test_dir/file1.txt; fi
	@if [ ! -f www/index.html ]; then echo "<h1>Servex Home</h1>" > www/index.html; fi
	@if [ ! -f www/large-test/largefile.bin ]; then head -c 50M </dev/urandom > www/large-test/largefile.bin; fi
	@if [ ! -f www/large-test/mediumfile.bin ]; then head -c 5M </dev/urandom > www/large-test/mediumfile.bin; fi
	@mkdir -p cgi-bin/
	@echo "print('Content-type: text/html')" > cgi-bin/test_cgi.py
	@echo "print('')" >> cgi-bin/test_cgi.py
	@echo "print('Hello from CGI!')" >> cgi-bin/test_cgi.py
	@chmod +x cgi-bin/test_cgi.py
	@mkdir -p error_pages/
	@if [ ! -f error_pages/404.html ]; then echo "<h1>404 Not Found</h1>" > error_pages/404.html; fi

external-test: setup-test-assets
	@{ \
	lsof -ti:8080,8081 | xargs kill -9 2>/dev/null || true; \
	java -jar target/java-localserver-1.0-SNAPSHOT.jar config.json > /tmp/external_test_server.log 2>&1 & \
	PID=$$!; \
	echo "Waiting for server to start (PID: $$PID)..."; \
	for i in {1..40}; do if curl -s http://localhost:8080/ok >/dev/null; then break; fi; sleep 0.25; done; \
	chmod +x scripts/external-test.sh; \
	HOST=127.0.0.1 ./scripts/external-test.sh; \
	RET=$$?; \
	kill -9 $$PID 2>/dev/null || true; \
	exit $$RET; \
	}

complex-test: setup-test-assets
	@{ \
	lsof -ti:8080,8081 | xargs kill -9 2>/dev/null || true; \
	java -jar target/java-localserver-1.0-SNAPSHOT.jar config.json > /tmp/complex_test_server.log 2>&1 & \
	PID=$$!; \
	echo "Waiting for server to start (PID: $$PID)..."; \
	for i in {1..40}; do if curl -s http://localhost:8080/ok >/dev/null; then break; fi; sleep 0.25; done; \
	FAILED=0; \
	echo "Starting Server Test Suite..."; \
	echo "--------------------------------------------------"; \
	\
	# Test 1: Virtual Hosting \
	echo -n "[TEST] Virtual Hosting (localhost)  : "; \
	if curl -s -H "Host: localhost" http://localhost:8080/ | grep -q "Servex Home"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 2: CGI Execution \
	echo -n "[TEST] CGI Script Execution        : "; \
	if curl -s http://localhost:8080/cgi-bin/test_cgi.py | grep -q "Hello from CGI"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 3: Directory Listing \
	echo -n "[TEST] Directory Listing           : "; \
	if curl -s http://localhost:8080/test_dir/ | grep -q "Directory listing"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 4: Large File Integrity (50MB) \
	echo -n "[TEST] Large File Transfer (50MB)  : "; \
	curl -s http://localhost:8080/large-test/largefile.bin -o /tmp/downloaded_large.bin; \
	if diff www/large-test/largefile.bin /tmp/downloaded_large.bin > /dev/null; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 5: Body Size Limit (413 Error) \
	echo -n "[TEST] Security: Body Size Limit   : "; \
	if curl -s -o /dev/null -w "%{http_code}" -X POST -F "file=@www/large-test/mediumfile.bin" http://localhost:8080/ | grep -q "413"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 6: Custom Error Page (404) \
	echo -n "[TEST] Custom Error Pages (404)    : "; \
	if curl -s http://localhost:8080/non-existent-page | grep -q "404 Not Found"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	# Test 7: Metrics API \
	echo -n "[TEST] Internal Metrics API        : "; \
	if curl -s http://localhost:8080/metrics | grep -q "total_requests"; then echo "SUCCESS"; else echo "FAILED"; FAILED=1; fi; \
	\
	echo "--------------------------------------------------"; \
	pkill -P $$PID || true; kill $$PID || true; \
	if [ $$FAILED -eq 0 ]; then \
		echo "✅ ALL TESTS PASSED SUCCESSFULLY"; \
		echo "--------------------------------------------------"; \
	else \
		echo "❌ SOME TESTS FAILED"; \
		echo "--------------------------------------------------"; \
		exit 1; \
	fi; \
	}

audit-test:
	@chmod +x scripts/audit-test.sh scripts/final-test.sh
	@./scripts/audit-test.sh
	@./scripts/final-test.sh

clean-test-assets:
	rm -rf www/large-test/
	rm -f /tmp/downloaded_large.bin
	rm -f cgi-bin/test_cgi.py

