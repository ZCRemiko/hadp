#!/bin/bash
set -e
export JAVA_HOME=/opt/java/openjdk
echo "=== DataNode starting ==="
service ssh start
for i in $(seq 1 30); do
  if curl -s http://namenode:9870 > /dev/null 2>&1; then break; fi
  sleep 2
done
hdfs --daemon start datanode
echo "DataNode started"
tail -f /dev/null
