#!/usr/bin/env bash
# Compiles everything and runs the 33-test suite.
set -e
mkdir -p out build/test-data
javac -d out *.java
java -cp out TestRunner
