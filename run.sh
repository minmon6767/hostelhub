#!/usr/bin/env bash
# Compiles every .java file into out/ and starts the app.
#   ./run.sh            interactive menu
#   ./run.sh --report   print all reports and exit
set -e
mkdir -p out
javac -d out *.java
java -cp out Main "$@"
