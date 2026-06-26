#!/bin/bash
set -e
export JAVA_HOME=/opt/java/openjdk
echo "=== HBase Master starting ==="
sleep 20
$HBASE_HOME/bin/hbase-daemon.sh start master
echo "HBase Master started"
tail -f /dev/null
