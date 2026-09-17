@echo off
if not exist out mkdir out
javac -d out *.java
java -cp out TestRunner
