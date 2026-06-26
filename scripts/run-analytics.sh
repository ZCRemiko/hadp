#!/bin/bash
# MapReduce 离线分析任务执行脚本
# 在 hadoop 容器中运行
#
# 用法: docker exec -it hadoop bash /scripts/run-analytics.sh <日期>
# 示例: docker exec -it hadoop bash /scripts/run-analytics.sh 2024-05-28
#
# 注意: 需要先将 hadp-analytics JAR 复制到容器中

DATE=${1:-$(date +%Y-%m-%d)}
DATE_SHORT=$(echo $DATE | sed 's/-//g')

INPUT_PATH="/user/hadp/logs/${DATE//-//}"
OUTPUT_PATH="/user/hadp/output/${DATE_SHORT}"
ZK_QUORUM="zookeeper"

echo "=========================================="
echo "  HADP MapReduce 离线分析"
echo "  日期: $DATE"
echo "  输入: $INPUT_PATH"
echo "  输出: $OUTPUT_PATH"
echo "=========================================="

# 检查输入路径是否存在
echo "检查输入数据..."
hdfs dfs -test -d $INPUT_PATH
if [ $? -ne 0 ]; then
    echo "错误: 输入路径不存在 - $INPUT_PATH"
    echo "请先通过 Collector API 上报数据"
    exit 1
fi

echo "输入数据大小:"
hdfs dfs -du -s -h $INPUT_PATH

# 检查 JAR 文件
JAR_PATH="/opt/hadp-analytics.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "错误: JAR 文件不存在 - $JAR_PATH"
    echo "请先在宿主机构建项目，然后复制 JAR 到容器:"
    echo "  cd D:\\hadp"
    echo "  mvn clean package -pl hadp-analytics -DskipTests"
    echo "  docker cp hadp-analytics/target/hadp-analytics-1.0.0.jar hadoop:/opt/hadp-analytics.jar"
    exit 1
fi

# 删除旧的输出目录（Hadoop 要求输出目录不存在）
hdfs dfs -rm -r -f $OUTPUT_PATH

# 运行 MapReduce 任务
echo ""
echo "开始执行 MapReduce 分析..."
echo ""

hadoop jar $JAR_PATH com.hadp.analytics.AnalyticsRunner \
    -input $INPUT_PATH \
    -output $OUTPUT_PATH \
    -zk $ZK_QUORUM

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "  分析完成!"
    echo "=========================================="
    echo ""
    echo "查看 HDFS 输出结果:"
    echo "  hdfs dfs -ls $OUTPUT_PATH"
    echo "  hdfs dfs -cat $OUTPUT_PATH/daily/part-r-* | head -20"
    echo ""
    echo "查询 HBase 结果:"
    echo "  echo \"scan 'daily_stats'\" | hbase shell -n"
    echo ""
    echo "API 查询:"
    echo "  curl http://localhost:8081/api/stats/daily?date=$DATE"
else
    echo "分析任务失败！请查看上方的错误日志"
    exit 1
fi
