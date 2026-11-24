#!/bin/bash
# Type-checks the mod against Minecraft 26.3 + Fabric API sources (no Maven needed).
# Errors inside library sources (missing fastutil/guava/etc.) are ignored; only our files count.
REF=/home/claude/ref
SP="$REF/mcsrc:$REF/dfu/src/main/java:$REF/slf4j/slf4j-api/src/main/java:$REF/floader/src/main/java"
for d in $REF/fabric/*/src/main/java $REF/fabric/*/src/client/java; do SP="$SP:$d"; done
rm -rf /tmp/tc && mkdir -p /tmp/tc
javac -proc:none -implicit:none -d /tmp/tc -Xmaxerrs 100000 -XDshould-stop.ifError=FLOW -XDshould-stop.ifNoError=FLOW \
  -sourcepath "$SP" $(find src/main/java -name '*.java') > /tmp/tc/log.txt 2>&1
grep -A3 "^src/main" /tmp/tc/log.txt | grep -v "^--$" | head -${1:-80}
echo "our errors: $(grep -c '^src/main.*error' /tmp/tc/log.txt)   total lines: $(wc -l < /tmp/tc/log.txt)"
