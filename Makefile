# Variables
JAVAC = javac
JAVA = java
SRC_DIR = src
BIN_DIR = bin
MAIN_CLASS = src.Main
CONFIG = config.json

# Find all Java files
SRCS = $(shell find $(SRC_DIR) -name "*.java")

# Default target
all: compile

# Compile everything
compile:
	@mkdir -p $(BIN_DIR)
	$(JAVAC) -d $(BIN_DIR) $(SRCS)
	@echo "Compilation successful."

# Run the server
run: compile
	$(JAVA) -cp $(BIN_DIR) $(MAIN_CLASS) $(CONFIG)

# Clean build artifacts
clean:
	rm -rf $(BIN_DIR)
	@echo "Cleaned up."

# Quick test with curl
test:
	curl -v http://localhost:8080/
	curl -v http://localhost:8080/metrics

.PHONY: all compile run clean test
