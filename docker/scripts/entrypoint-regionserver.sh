#!/bin/bash
set -e
export JAVA_HOME=/opt/java/openjdk
echo "=== HBase RegionServer starting ==="
sleep 25
$HBASE_HOME/bin/hbase-daemon.sh start regionserver
echo "RegionServer started"
tail -f /dev/null
