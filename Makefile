# Makefile for Java LocalServer
# Focused on build and compilation tasks.
# Test commands have been migrated to test.sh

.PHONY: all compile package clean run test re

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
	@chmod +x test.sh
	./test.sh

