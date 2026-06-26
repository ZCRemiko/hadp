#!/bin/bash
# ================================================================
# Hadoop 浼垎甯冨紡瀹瑰櫒鍚姩鑴氭湰
# 浣跨敤鐩存帴鍚姩鏂瑰紡锛岄伩鍏?SSH 鐜鍙橀噺浼犻€掗棶棰?# ================================================================

set -e

# 璁剧疆 JAVA_HOME (eclipse-temurin:8-jdk 鐨勯粯璁よ矾寰?
export JAVA_HOME="${JAVA_HOME:-/opt/java/openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"

# 灏?JAVA_HOME 鍐欏叆绯荤粺鐜锛岀‘淇濇墍鏈夊瓙杩涚▼鍙
echo "export JAVA_HOME=$JAVA_HOME" >> /etc/profile
echo "export PATH=$JAVA_HOME/bin:\$PATH" >> /etc/profile

echo "=========================================="
echo "  Hadoop 浼垎甯冨紡闆嗙兢鍚姩涓?.."
echo "  Hadoop 鐗堟湰: $HADOOP_VERSION"
echo "  JAVA_HOME: $JAVA_HOME"
echo "=========================================="

# ---- 1. 鏍煎紡鍖?NameNode (浠呴娆? ----
echo "[1/5] 妫€鏌?NameNode..."
if [ ! -d /opt/hadoop/data/name/current ]; then
    echo "棣栨鍚姩锛屾牸寮忓寲 NameNode..."
    hdfs namenode -format -force -nonInteractive
    echo "NameNode 鏍煎紡鍖栧畬鎴?
else
    echo "NameNode 宸叉牸寮忓寲锛岃烦杩?
fi

# ---- 2. 鍚姩 NameNode ----
echo "[2/5] 鍚姩 NameNode..."
hdfs --daemon start namenode
sleep 3

# 绛夊緟 NameNode 灏辩华
for i in $(seq 1 30); do
    if curl -s http://localhost:9870 > /dev/null 2>&1; then
        echo "NameNode 宸插氨缁?(http://localhost:9870)"
        break
    fi
    echo "绛夊緟 NameNode... ($i/30)"
    sleep 2
done

# ---- 3. 鍚姩 DataNode ----
echo "[3/5] 鍚姩 DataNode..."
hdfs --daemon start datanode
sleep 2

# ---- 4. 绛夊緟 HDFS 閫€鍑哄畨鍏ㄦā寮?----
echo "[4/5] 绛夊緟 HDFS 閫€鍑哄畨鍏ㄦā寮?.."
hdfs dfsadmin -safemode wait 2>/dev/null || true

# 鍒涘缓蹇呰鐩綍
hdfs dfs -mkdir -p /hbase /user/hadp/logs 2>/dev/null || true
hdfs dfs -chmod 777 /hbase 2>/dev/null || true

# ---- 5. 鍚姩 YARN ----
echo "[5/5] 鍚姩 YARN (ResourceManager + NodeManager)..."
yarn --daemon start resourcemanager
sleep 2
yarn --daemon start nodemanager
sleep 2

# 绛夊緟 ResourceManager 灏辩华
for i in $(seq 1 15); do
    if curl -s http://localhost:8088 > /dev/null 2>&1; then
        echo "ResourceManager 宸插氨缁?(http://localhost:8088)"
        break
    fi
    sleep 2
done

echo ""
echo "=========================================="
echo "  Hadoop 闆嗙兢鍚姩瀹屾垚!"
echo "  NameNode       : http://localhost:9870"
echo "  ResourceManager: http://localhost:8088"
echo "=========================================="

# 淇濇寔瀹瑰櫒杩愯
tail -f /dev/null
