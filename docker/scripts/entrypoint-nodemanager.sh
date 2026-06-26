#!/bin/bash
set -e
export JAVA_HOME=/opt/java/openjdk
echo "=== NodeManager starting ==="
service ssh start
for i in $(seq 1 30); do
  if curl -s http://resourcemanager:8088 > /dev/null 2>&1; then break; fi
  sleep 2
done
yarn --daemon start nodemanager
echo "NodeManager started"
tail -f /dev/null
