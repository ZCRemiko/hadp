#!/bin/bash
set -e

echo "=========================================="
echo "  HBase Standalone 模式启动中..."
echo "  HBase 版本: $HBASE_VERSION"
echo "=========================================="

# ---- 1. 等待基础设施就绪 ----
echo "[1/3] 等待 ZooKeeper 和 HDFS 就绪..."
sleep 15

# ---- 2. 检查 HDFS 可访问性 ----
echo "[2/3] 检查 HDFS 连接..."
if curl -s --max-time 5 http://hadoop:9870 > /dev/null 2>&1; then
    echo "HDFS 已就绪 (hadoop:9870)"
else
    echo "警告: HDFS 不可达，将使用本地文件系统"
    sed -i 's|hdfs://hadoop:9000/hbase|file:///tmp/hbase-data|g' $HBASE_HOME/conf/hbase-site.xml
fi

# ---- 3. 启动 HBase ----
echo "[3/3] 启动 HBase..."
$HBASE_HOME/bin/start-hbase.sh

echo "等待 HBase Master 就绪..."
for i in $(seq 1 60); do
    if curl -s --max-time 2 http://localhost:16010 > /dev/null 2>&1; then
        echo "HBase Master 已就绪"
        break
    fi
    sleep 2
    if [ $i -eq 60 ]; then
        echo "警告: HBase Web UI 未响应"
    fi
done

echo ""
echo "=========================================="
echo "  HBase 启动完成!"
echo "  HBase Master : http://localhost:16010"
echo "=========================================="

tail -f /dev/null
