#!/bin/bash
set -e
export JAVA_HOME=/opt/java/openjdk
echo "=== NameNode starting ==="
service ssh start
if [ ! -d /opt/hadoop/data/name/current ]; then
  echo "Formatting NameNode..."
  hdfs namenode -format -force -nonInteractive
fi
hdfs --daemon start namenode
for i in $(seq 1 30); do
  if curl -s http://localhost:9870 > /dev/null 2>&1; then break; fi
  sleep 2
done
sleep 10
hdfs dfs -mkdir -p /hbase /user/hadp/logs 2>/dev/null || true
hdfs dfs -chmod 777 /hbase 2>/dev/null || true
echo "NameNode ready"
tail -f /dev/null
