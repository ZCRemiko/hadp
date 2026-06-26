#!/bin/bash
# HBase 表初始化脚本
# 在 HBase 容器中运行: docker exec -it hbase bash /scripts/init-hbase.sh
#
# 创建项目所需的 HBase 表:
#   daily_stats  - 每日 PV/UV 统计
#   page_stats   - 页面统计
#   hourly_stats - 小时级统计

echo "=========================================="
echo "  HADP HBase 表初始化"
echo "=========================================="

# 检查 HBase 是否就绪
echo "检查 HBase 状态..."
echo "status" | hbase shell -n 2>/dev/null
if [ $? -ne 0 ]; then
    echo "HBase 未就绪，请等待 HBase 完全启动后重试"
    exit 1
fi

# 创建表: create '表名', '列族名'
echo "创建表 daily_stats..."
echo "create 'daily_stats', 'stats'" | hbase shell -n 2>/dev/null

echo "创建表 page_stats..."
echo "create 'page_stats', 'stats'" | hbase shell -n 2>/dev/null

echo "创建表 hourly_stats..."
echo "create 'hourly_stats', 'stats'" | hbase shell -n 2>/dev/null

echo ""
echo "表创建完成！查看表列表:"
echo "list" | hbase shell -n

echo ""
echo "HBase Master Web UI: http://localhost:16010"
