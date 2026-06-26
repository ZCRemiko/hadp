# HADP - Hadoop Analytics Data Platform

分布式用户行为日志分析平台 — 基于 Hadoop + HBase + Spring Boot

---

## 目录

- [项目概述](#项目概述)
- [系统架构](#系统架构)
- [5 大分析任务](#5-大分析任务)
- [环境要求](#环境要求)
- [快速启动](#快速启动)
- [详细操作指南](#详细操作指南)
- [API 文档](#api-文档)
- [HBase 表设计](#hbase-表设计)
- [核心概念解释](#核心概念解释)
- [常见问题](#常见问题)
- [项目扩展建议](#项目扩展建议)

---

## 项目概述

这是一个完整的 **分布式大数据处理平台**，模拟互联网公司的用户行为日志分析流程：

```
用户操作 → 日志上报 → HDFS存储 → MapReduce分析 → HBase存储 → API查询
```

### 涵盖的技术栈

| 技术 | 作用 | 版本 |
|------|------|------|
| **Hadoop HDFS** | 分布式文件系统，存储原始日志 | 3.2.1 |
| **Hadoop YARN** | 集群资源管理，调度计算任务 | 3.2.1 |
| **Hadoop MapReduce** | 分布式计算框架，离线分析日志 | 3.2.1 |
| **Apache HBase** | 分布式列式数据库，存储聚合结果 | 2.4.x |
| **Apache ZooKeeper** | 分布式协调服务，管理 HBase 集群 | 3.7 |
| **Spring Boot** | Java Web 框架，提供 REST API | 2.7.18 |
| **Docker Compose** | 容器编排，一键启动 Hadoop 集群 | - |

### 四大模块

| 模块 | 端口 | 职责 |
|------|------|------|
| **hadp-common** | - | 公共数据模型（LogEvent 等）、HBase 工具类 |
| **hadp-collector** | 8080 | 接收日志上报，写入 HDFS |
| **hadp-analytics** | - | MapReduce 离线分析（5 个 Job: PV/UV/页面/留存/漏斗） |
| **hadp-api** | 8081 | 查询 HBase，提供 REST API 返回分析结果 |

---

## 系统架构

```
                        ┌─────────────────────────────────────────────────────────┐
                        │                  Docker 真分布式集群 (10 容器)             │
                        │                                                         │
                        │   ┌──────────────┐    ┌──────────────┐                  │
                        │   │  NameNode    │    │   ZooKeeper  │                  │
                        │   │  Port:9870   │    │   Port:2181  │                  │
                        │   └──────┬───────┘    └──────┬───────┘                  │
                        │          │                   │                          │
                        │   ┌──────┴──────┐    ┌───────┴────────┐                │
                        │   │  DataNode 1 │    │  HBase Master   │                │
                        │   │  DataNode 2 │    │  Port:16010     │                │
                        │   │  (副本=2)    │    └───────┬────────┘                │
                        │   └─────────────┘            │                          │
                        │                    ┌─────────┴────────┐                │
                        │   ┌────────────┐   │ RegionServer 1   │                │
                        │   │ ResourceMgr│   │ RegionServer 2   │                │
                        │   │ Port:8088  │   │ Port:16030/16031 │                │
                        │   └─────┬──────┘   └──────────────────┘                │
                        │         │                                               │
                        │   ┌─────┴──────┐                                        │
                        │   │ NodeMgr 1  │                                        │
                        │   │ NodeMgr 2  │                                        │
                        │   └────────────┘                                        │
                        └─────────────────────────────────────────────────────────┘
                                  ▲                                     ▲
                                  │  HTTP POST                          │  HTTP GET
                                  │  (日志上报)                          │  (数据查询)
                                  │                                     │
                            ┌─────┴──────────┐                   ┌──────┴──────────┐
                            │  Collector     │                   │  Query API      │
                            │  Port: 8080    │                   │  Port: 8081     │
                            │  (宿主机运行)   │                   │  (宿主机运行)    │
                            └────────────────┘                   └─────────────────┘
```

**10 个容器的完整分布式架构**：

| 容器 | 角色 | 数量 | 说明 |
|------|------|------|------|
| namenode | HDFS 主节点 | 1 | 管理文件元数据 |
| datanode1/2 | HDFS 存储节点 | 2 | 存储数据块，副本因子=2 |
| resourcemanager | YARN 资源调度 | 1 | 分配集群计算资源 |
| nodemanager1/2 | YARN 计算节点 | 2 | 执行 Map/Reduce 任务 |
| zookeeper | 分布式协调 | 1 | HBase 依赖 |
| hbase-master | HBase 集群管理 | 1 | Region 分配 |
| regionserver1/2 | HBase 数据读写 | 2 | 处理读写请求 |

### 数据流

```
第1步: 采集     Collector API (8080) 接收日志 → 写入 HDFS
第2步: 分析     MapReduce 读取 HDFS → 聚合计算 → 写入 HBase
第3步: 查询     Query API (8081) 读 HBase → 返回 JSON
```

---

## 5 大分析任务

| # | Job 类 | 统计内容 | HBase 表 | RowKey 设计 |
|---|--------|---------|----------|------------|
| 1 | DailyStatsJob | 每日 PV + UV | daily_stats | `yyyyMMdd` |
| 2 | PageStatsJob | 每页面 PV | page_stats | `yyyyMMdd_url` |
| 3 | HourlyStatsJob | 每小时 PV + UV | hourly_stats | `yyyyMMddHH` |
| 4 | RetentionAnalysisJob | 用户 N 日留存 | user_retention | `baseDate_activeDate` |
| 5 | FunnelAnalysisJob | 漏斗转化率 | funnel_stats | `date_step` |

**漏斗步骤**: /home → /products → /cart → /checkout
**留存分析**: 计算基准日活跃用户在 N 天后仍活跃的比例

---

---

## 环境要求

| 软件 | 最低版本 | 说明 |
|------|----------|------|
| Docker Desktop | 20.10+ | 运行 Hadoop / HBase / ZooKeeper 容器 |
| JDK | 1.8 | Java 开发环境 |
| Maven | 3.6+ | 项目构建工具 |
| VS Code | 任意版本 | 代码编辑器（已配置好设置） |

### 环境检查

```powershell
# 检查 Java 版本（应输出 1.8.x，必须是 JDK 不是 JRE）
java -version

# 检查 Maven 版本
mvn --version

# 检查 Docker 是否正常运行
docker ps
```

> **重要**：构建项目需要 **JDK**（不是 JRE），因为 Hadoop/HBase 依赖 `tools.jar`。
> 本机已验证的路径为 `D:\JDK8`（Java 1.8.0_341）。
>
> 构建命令：
> ```powershell
> $env:JAVA_HOME = "D:\JDK8"
> $env:Path = "D:\JDK8\bin;$env:Path"
> mvn clean package -DskipTests
> ```

---

## 快速启动

### 第1步：启动 Docker 真分布式集群

> **首次使用前**：
> 1. 在 Docker Desktop 设置中删除所有 `registry-mirrors`（保留空 `[]`），Apply & Restart
> 2. 建议将 Docker hostname 加入 Windows hosts 文件（可选，方便 Collector 直连 HDFS）：
>    `127.0.0.1 hadoop zookeeper hbase`

```powershell
cd D:\hadp

# 构建并启动所有容器（首次约 15-25 分钟，需下载基础镜像约 200MB）
docker compose up -d --build

# 查看容器状态
docker compose ps
```

容器启动后，访问以下 Web UI 确认服务正常：

| 服务 | URL | 说明 |
|------|-----|------|
| NameNode | http://localhost:9870 | HDFS 管理界面，可浏览文件 |
| ResourceManager | http://localhost:8088 | YARN 集群管理，可查看任务 |
| HBase Master | http://localhost:16010 | HBase 集群状态 |

### 第2步：构建 Java 项目

```powershell
# 编译所有模块
mvn clean package -DskipTests
```

构建成功输出类似：
```
[INFO] hadp-parent ................................ SUCCESS
[INFO] hadp-common ................................ SUCCESS
[INFO] hadp-collector ............................. SUCCESS
[INFO] hadp-analytics ............................. SUCCESS
[INFO] hadp-api ................................... SUCCESS
```

### 第3步：启动 Collector 服务（端口 8080）

```powershell
# 启动日志采集服务
java -jar hadp-collector\target\hadp-collector-1.0.0.jar
```

看到以下日志表示启动成功：
```
Started CollectorApplication in 5.123 seconds
HDFS 连接成功: hdfs://hadoop:9000
```

### 第4步：生成测试数据

**方式一：通过 Collector API（需要启动 Collector）**

```powershell
# 启动 Collector（新 PowerShell 窗口）
$env:JAVA_HOME = "D:\JDK8"; java -jar hadp-collector\target\hadp-collector-1.0.0.jar

# 生成并发送 100 条模拟日志
powershell -ExecutionPolicy Bypass -File ".\scripts\generate-sample-data.ps1" -count 100
```

**方式二：直接在 HDFS 容器内生成（无需 Collector）**

```powershell
docker exec hadoop bash -c "
for i in \$(seq 1 100); do
  printf '{\"userId\":\"user_%03d\",\"eventType\":\"page_view\",\"pageUrl\":\"/home\",\"timestamp\":%d,\"duration\":5000}\n' \
    \$((RANDOM % 10 + 1)) \$(date +%s%3N)
done > /tmp/events.json
hdfs dfs -mkdir -p /user/hadp/logs/$(date +%Y/%m/%d)
hdfs dfs -put -f /tmp/events.json /user/hadp/logs/$(date +%Y/%m/%d)/events_test.json
"
```

### 第5步：运行 MapReduce 分析

```powershell
# JAR 已构建好，直接复制到 hadoop 容器并运行
docker cp hadp-analytics\target\hadp-analytics-1.0.0.jar hadoop:/opt/hadp-analytics.jar

# 运行分析（-input 指定 HDFS 日志目录）
docker exec hadoop hadoop jar /opt/hadp-analytics.jar -input /user/hadp/logs -output /user/hadp/output/result -zk zookeeper
```

成功后输出：
```
[1/3] 每日统计任务完成
[2/3] 页面统计任务完成
[3/3] 小时级统计任务完成
所有分析任务完成！
```

### 第6步：启动 Query API 服务（端口 8081）

打开**新的 PowerShell 窗口**：

```powershell
$env:JAVA_HOME = "D:\JDK8"; $env:Path = "D:\JDK8\bin;$env:Path"
java -jar hadp-api\target\hadp-api-1.0.0.jar
```

看到 `HBase 连接成功` 和 `HBase 表初始化完成` 表示启动成功。

### 第7步：查询分析结果

打开**新的 PowerShell 窗口**：

```powershell
# 查询今天的统计数据
curl http://localhost:8081/api/stats/daily?date=2026-05-28
```

响应示例：
```json
{
  "date": "20260528",
  "pv": 42,
  "uv": 10,
  "avgDuration": 0,
  "totalEvents": 0
}
```

```powershell
# 查询小时级趋势
curl http://localhost:8081/api/stats/hourly?date=2026-05-28

# 查询热门页面 Top 10
curl "http://localhost:8081/api/stats/pages/top?date=2026-06-01&limit=10"

# 查询用户留存
curl "http://localhost:8081/api/stats/retention?date=2026-06-01"

# 查询漏斗转化率
curl "http://localhost:8081/api/stats/funnel?date=2026-06-01"
```

---

## 详细操作指南

### 1. Docker 集群管理

```powershell
# 构建 + 启动
docker compose up -d --build

# 查看日志（排查问题用）
docker compose logs -f hadoop     # Hadoop 日志
docker compose logs -f hbase      # HBase 日志

# 停止所有服务
docker compose down

# 停止并删除所有数据（重新开始）
docker compose down -v
```

### 2. 各模块单独构建和运行

```powershell
# ---- hadp-common ----
mvn clean install -pl hadp-common -DskipTests

# ---- hadp-collector (8080) ----
mvn clean package -pl hadp-collector -DskipTests
java -jar hadp-collector\target\hadp-collector-1.0.0.jar

# ---- hadp-analytics ----
mvn clean package -pl hadp-analytics -DskipTests
docker cp hadp-analytics\target\hadp-analytics-1.0.0.jar hadoop:/opt/hadp-analytics.jar
docker exec hadoop hadoop jar /opt/hadp-analytics.jar -input /user/hadp/logs -output /user/hadp/output/result -zk zookeeper

# ---- hadp-api (8081) ----
mvn clean package -pl hadp-api -DskipTests
java -jar hadp-api\target\hadp-api-1.0.0.jar
```

### 3. 使用 VS Code 调试

1. 用 VS Code 打开 `D:\hadp` 目录
2. 安装 **Extension Pack for Java** 和 **Spring Boot Extension Pack** 插件
3. 打开 `hadp-collector/src/main/java/com/hadp/collector/CollectorApplication.java`
4. 点击 `main` 方法上方的 `Run | Debug` 启动调试

### 4. HDFS 命令行操作

```powershell
# 进入 Hadoop 容器
docker exec -it hadoop bash

# 列出根目录
hdfs dfs -ls /

# 查看日志文件
hdfs dfs -ls -R /user/hadp/logs/

# 查看日志内容
hdfs dfs -cat /user/hadp/logs/2026/05/28/events_*.json | head -5

# 查看 MapReduce 输出
hdfs dfs -cat /user/hadp/output/result/daily/part-r-* | head -20
```

### 5. HBase Shell 操作

```powershell
# 进入 HBase 容器
docker exec -it hbase bash

# 启动 HBase Shell
hbase shell

# 在 Shell 中执行:
hbase(main):001:0> list                                    # 列出所有表
hbase(main):002:0> scan 'daily_stats'                      # 扫描表数据
hbase(main):003:0> get 'daily_stats', '20260528'           # 按 RowKey 查询
hbase(main):004:0> count 'daily_stats'                     # 统计行数
hbase(main):005:0> scan 'page_stats', {LIMIT => 10}        # 扫描前10条
hbase(main):006:0> scan 'hourly_stats'                     # 扫描小时统计
```

---

## API 文档

### Collector API (端口 8080)

#### 健康检查
```
GET /api/logs/health
```

#### 上报单条日志
```
POST /api/logs/event
Content-Type: application/json

{
  "userId": "user_001",
  "eventType": "page_view",
  "pageUrl": "/home",
  "referrer": "https://www.google.com",
  "ipAddress": "192.168.1.100",
  "userAgent": "Mozilla/5.0...",
  "timestamp": 1716883200000,
  "duration": 5000
}
```

**字段说明：**
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| userId | string | 是 | 用户唯一标识 |
| eventType | string | 否 | 事件类型：page_view/click/search/purchase |
| pageUrl | string | 否 | 页面路径 |
| referrer | string | 否 | 来源页面URL |
| ipAddress | string | 否 | 客户端IP |
| userAgent | string | 否 | 浏览器标识 |
| timestamp | long | 是 | 事件时间戳（毫秒） |
| duration | long | 否 | 页面停留时长（毫秒） |

#### 批量上报日志
```
POST /api/logs/batch
Content-Type: application/json

[
  {"userId":"user_001","eventType":"page_view","pageUrl":"/home","timestamp":1716883200000},
  {"userId":"user_002","eventType":"click","pageUrl":"/products","timestamp":1716883201000}
]
```

### Query API (端口 8081)

#### 每日统计
```
GET /api/stats/daily?date=2026-05-28
```

响应：
```json
{
  "date": "20260528",
  "pv": 15000,
  "uv": 3200,
  "avgDuration": 45000,
  "totalEvents": 18000
}
```

#### 日期范围统计
```
GET /api/stats/daily/range?start=2026-05-01&end=2026-05-28
```

#### 热门页面 Top N
```
GET /api/stats/pages/top?date=2026-05-28&limit=10
```

#### 指定页面详情
```
GET /api/stats/pages/detail?date=2026-05-28&url=/home
```

#### 小时级趋势
```
GET /api/stats/hourly?date=2026-05-28
```

响应：
```json
{
  "date": "2026-05-28T00:00:00.000+00:00",
  "count": 24,
  "items": [
    {"dateHour":"2026052800","pv":500,"uv":200},
    {"dateHour":"2026052801","pv":300,"uv":150},
    ...
  ]
}
```

#### 用户留存概览
```
GET /api/stats/retention?date=2026-06-01
```

响应：
```json
{
  "date": "2026-06-01T00:00:00.000+00:00",
  "items": [
    {"rowKey":"20260601_20260601","users":100},
    {"rowKey":"20260601_20260602","users":60},
    {"rowKey":"20260601_20260603","users":35}
  ]
}
```

#### 漏斗转化率
```
GET /api/stats/funnel?date=2026-06-01
```

响应：
```json
{
  "date": "2026-06-01T00:00:00.000+00:00",
  "steps": [
    {"step":0, "url":"/home",     "users":1000, "rate":100},
    {"step":1, "url":"/products", "users":500,  "rate":50},
    {"step":2, "url":"/cart",     "users":200,  "rate":40},
    {"step":3, "url":"/checkout", "users":80,   "rate":40}
  ]
}
```

#### 管理 - 初始化 HBase 表
```
POST /api/admin/init
```

---

## HBase 表设计

### daily_states - 每日统计

| 项目 | 说明 |
|------|------|
| RowKey | `yyyyMMdd` (如 `20260528`) |
| 列族: stats | |
| &nbsp;&nbsp; stats:pv | 日页面浏览量 (Long) |
| &nbsp;&nbsp; stats:uv | 日独立访客数 (Long) |
| &nbsp;&nbsp; stats:avgDuration | 平均停留时长 (Long) |
| &nbsp;&nbsp; stats:totalEvents | 总事件数 (Long) |

### page_stats - 页面统计

| 项目 | 说明 |
|------|------|
| RowKey | `yyyyMMdd_pageUrl` (如 `20260528_/home`) |
| 列族: stats | |
| &nbsp;&nbsp; stats:date | 日期 |
| &nbsp;&nbsp; stats:url | 页面URL |
| &nbsp;&nbsp; stats:pv | 页面浏览量 (Long) |

### hourly_stats - 小时级统计

| 项目 | 说明 |
|------|------|
| RowKey | `yyyyMMddHH` (如 `2026052816`) |
| 列族: stats | |
| &nbsp;&nbsp; stats:pv | 小时页面浏览量 (Long) |
| &nbsp;&nbsp; stats:uv | 小时独立访客数 (Long) |

### user_retention - 用户留存

| 项目 | 说明 |
|------|------|
| RowKey | `baseDate_activeDate` (如 `20260601_20260602`) |
| 列族: stats | |
| &nbsp;&nbsp; stats:users | 基准日活跃且在 activeDate 仍活跃的用户数 |

### funnel_stats - 漏斗转化

| 项目 | 说明 |
|------|------|
| RowKey | `date_step` (如 `20260601_0` 表示第 0 步) |
| 列族: stats | |
| &nbsp;&nbsp; stats:c | 到达该步骤的用户数 |
| &nbsp;&nbsp; stats:url | 该步骤的页面 URL |
| &nbsp;&nbsp; stats:step | 步骤编号 (0-3) |

### RowKey 设计原则

HBase 的 RowKey 按**字典序**排序存储。我们的设计充分利用了这一特性：

- **daily_stats**: `yyyyMMdd` — 字典序 = 时间序，方便按日期范围扫描
- **page_stats**: `yyyyMMdd_url` — 同一天的页面数据物理上存储在一起，查询高效
- **hourly_stats**: `yyyyMMddHH` — 按小时顺序排列，便于趋势分析
- **funnel_stats**: `date_step` — 同一天的漏斗数据连续存储，方便一次 Scan 取全部步骤
- **user_retention**: `baseDate_activeDate` — 按基准日扫描时，该日所有留存数据连续

---

## 核心概念解释

### Hadoop HDFS（分布式文件系统）

```
文件 → 切分成 Block（默认128MB）→ 分散存储到多台 DataNode
       → 每个 Block 有3个副本 → 机器宕机也不丢数据

NameNode：记录"哪个文件在哪些 DataNode 上"（元数据）
DataNode：实际存储数据块
```

**本项目中**：Collector 将日志以 JSON Lines 格式写入 HDFS，按天分目录。
`/user/hadp/logs/2026/05/28/events_20260528_160000.json`

### Hadoop MapReduce（分布式计算）

MapReduce 的核心思想是**分而治之**：

```
Map 阶段（映射）→ 各节点并行处理本地数据 → 输出中间结果
     |
Shuffle 阶段（洗牌）→ 按 key 分组、排序、网络传输
     |
Reduce 阶段（归约）→ 对每组数据执行聚合计算 → 输出最终结果
```

**本项目中**：
- Map 阶段：解析 JSON 日志，提取（日期→1）和（日期→用户ID）
- Reduce 阶段：统计 PV（累加1）和 UV（去重计数）

### Apache HBase（分布式列式数据库）

HBase 建立在 HDFS 之上，具有以下特点：

- **面向列**：数据按列族存储，适合稀疏数据
- **高扩展**：自动分片（Region Splitting），支持水平扩展
- **强一致性**：同一行的读写保证强一致
- **海量存储**：单表可存数十亿行、数百万列

**核心组件**：
```
HMaster      — 负责 Region 分配、负载均衡、表管理
RegionServer — 负责数据读写、Region 管理
ZooKeeper    — 集群状态协调、Master 选举
```

### 关键技术对比

| 概念 | HDFS | HBase |
|------|------|-------|
| 用途 | 文件存储 | 结构化数据存储 |
| 写入模式 | 追加写 | 随机读写 |
| 数据组织 | 文件/目录 | 表/行/列族/列 |
| 查询方式 | 遍历文件 | RowKey查询 / Scan扫描 |
| 类比 | 分布式硬盘 | 分布式数据库 |

---

## 常见问题

### Q1: Docker 构建/启动失败
```
A: 1. 确保 Docker Desktop 已启动并正常
   2. 检查 Docker Engine 配置中的 registry-mirrors 是否为空（[]）
   3. 尝试 docker compose down -v 清理旧数据后重试
   4. 构建 Hadoop 镜像需下载 ~350MB，如网络慢请耐心等待
   5. 查看详细错误: docker compose logs hadoop
```

### Q2: Maven 构建失败，提示找不到依赖
```
A: 先执行 mvn clean install -pl hadp-common -DskipTests
   确保 hadp-common 安装到本地 Maven 仓库后再构建其他模块。
```

### Q3: Collector 启动报 "HDFS 连接失败"
```
A: 1. 确认 Docker 集群已启动: docker compose ps
   2. 确认 NameNode 已就绪: 访问 http://localhost:9870
   3. Collector 默认连接 hdfs://hadoop:9000，需确保 Docker 容器可解析
      或将应用配置改为 --hadoop.hdfs.uri=hdfs://localhost:9000 启动
```

### Q4: MapReduce 任务报 "Connection refused" (HBase)
```
A: 检查 ZooKeeper 和 HBase 容器是否正常运行:
   docker compose logs hbase
   确保 HBase Web UI (http://localhost:16010) 可以访问
```

### Q5: API 返回 "未找到统计数据"
```
A: 1. 先确认 HBase 表已创建:
      echo -e "create 'daily_stats','stats'\ncreate 'page_stats','stats'\ncreate 'hourly_stats','stats'" | docker exec -i hbase hbase shell -n
   2. 确认 MapReduce 任务已成功执行
   3. 检查 HBase Shell 中是否有数据: docker exec -i hbase hbase shell -n <<< "scan 'daily_stats'"
```

### Q6: 从宿主机连接 Docker 内的 HDFS 失败
```
A: 宿主机可通过 localhost:9000 端口映射访问 HDFS，但 HDFS NameNode
   返回的 DataNode 地址是 Docker 内部地址 (hadoop:9000)。
   解决方案：在 C:\Windows\System32\drivers\etc\hosts 中添加：
     127.0.0.1 hadoop zookeeper hbase
   或直接在 hadoop 容器内执行 HDFS 操作。
```
   3. 检查 HBase Shell 中是否有数据: docker exec -it hbase hbase shell
```

### Q7: 如何清空数据重新开始？
```powershell
# 清空 HDFS 数据
docker exec hadoop hdfs dfs -rm -r -f /user/hadp

# 清空 HBase 表数据（保留表结构）
docker exec hbase bash -c "echo \"truncate 'daily_stats'\" | hbase shell -n"
docker exec hbase bash -c "echo \"truncate 'page_stats'\" | hbase shell -n"
docker exec hbase bash -c "echo \"truncate 'hourly_stats'\" | hbase shell -n"
```

---

## 项目扩展建议

掌握基础后，可以尝试以下扩展：

### 难度 ★☆☆ — 入门
- [ ] 添加更多事件类型（如 `share`、`comment`）
- [ ] 为页面统计添加 `avgDuration`（平均停留时长）计算
- [ ] 添加 `POST /api/stats/daily/compare` 对比两天数据

### 难度 ★★☆ — 进阶
- [ ] 引入 **Apache Kafka** 作为日志缓冲层（削峰填谷）
- [ ] 使用 **Hive** 代替 MapReduce（SQL 方式做离线分析）
- [ ] ~~实现 **用户留存分析**~~ → 已实现（RetentionAnalysisJob）
- [ ] 添加 **IP 地址库** 做地域分析
- [ ] 增加漏斗 Steps 可配置化（目前硬编码 4 步）

### 难度 ★★★ — 高级
- [ ] 使用 **Spark** 代替 MapReduce（更快的内存计算）
- [ ] 引入 **Redis** 做热数据缓存（减少 HBase 查询压力）
- [ ] 使用 **Spring Cloud** 微服务化（服务发现、负载均衡）
- [ ] 添加 **ECharts/Grafana** 数据可视化前端
- [ ] 实现 **实时计算**（使用 Spark Streaming 或 Flink）

---

## 项目结构

```
hadp/
├── docker-compose.yml              # Docker 集群编排（10 容器真分布式）
├── pom.xml                         # Maven 父 POM（Java 8 / Spring Boot 2.7.18）
├── README.md                       # 本文档
│
├── hadp-common/                    # 公共模块
│   ├── pom.xml
│   └── src/main/java/com/hadp/common/
│       ├── model/
│       │   ├── LogEvent.java       # 日志事件模型
│       │   ├── DailyStats.java     # 每日统计模型
│       │   ├── PageStats.java      # 页面统计模型
│       │   └── HourlyStats.java    # 小时统计模型
│       └── util/
│           └── HBaseConnectionManager.java  # HBase连接管理
│
├── hadp-collector/                 # 日志采集模块
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hadp/collector/
│       │   ├── CollectorApplication.java  # 启动类
│       │   ├── controller/LogController.java  # REST接口
│       │   └── service/LogService.java        # HDFS写入
│       └── resources/application.yml
│
├── hadp-analytics/                 # MapReduce分析模块（5个Job）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hadp/analytics/
│       │   ├── DailyStatsJob.java       # 每日PV/UV统计
│       │   ├── PageStatsJob.java        # 页面统计
│       │   ├── HourlyStatsJob.java      # 小时级统计
│       │   ├── RetentionAnalysisJob.java # 用户留存分析
│       │   ├── FunnelAnalysisJob.java   # 漏斗转化分析
│       │   └── AnalyticsRunner.java     # 任务调度主类（5任务编排）
│       └── resources/log4j.properties
│
├── hadp-api/                       # 查询API模块（8个端点）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hadp/api/
│       │   ├── ApiApplication.java       # 启动类
│       │   ├── controller/StatsController.java  # REST接口
│       │   └── service/StatsService.java        # HBase查询
│       └── resources/application.yml
│
├── docker/                          # Docker 自定义镜像
│   ├── hadoop-base.Dockerfile       # Hadoop 3.2.1 真分布式基础镜像
│   ├── hbase-base.Dockerfile        # HBase 2.4.17 真分布式基础镜像
│   ├── scripts/
│   │   ├── entrypoint-namenode.sh
│   │   ├── entrypoint-datanode.sh
│   │   ├── entrypoint-resourcemanager.sh
│   │   ├── entrypoint-nodemanager.sh
│   │   ├── entrypoint-hmaster.sh
│   │   └── entrypoint-regionserver.sh
│   ├── hadoop-3.2.1.tar.gz          # 预下载 Hadoop 二进制 (~342 MB)
│   └── hbase-2.4.17-bin.tar.gz     # 预下载 HBase 二进制 (~271 MB)
│
├── scripts/                        # 辅助脚本
│   ├── generate-sample-data.ps1    # 模拟日志数据生成器
│   ├── init-hbase.sh               # HBase 表初始化
│   └── run-analytics.sh            # MapReduce 任务执行
│
└── .vscode/                        # VS Code 配置
    └── settings.json
```
