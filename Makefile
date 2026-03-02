# Maven Wrapper (./mvnw) is now used for all build and run commands.

.PHONY: all compile package run clean test

all: compile

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
