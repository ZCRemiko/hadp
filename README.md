# HADP — Hadoop Analytics Data Platform

分布式用户行为日志分析平台，基于 Hadoop + HBase + Spring Boot。

---

## 架构

10 容器真分布式集群，通过 Docker Compose 编排：

| 容器 | 角色 | 数量 | 端口 |
|------|------|------|------|
| namenode | HDFS NameNode | 1 | 9870, 9000 |
| datanode1 / datanode2 | HDFS DataNode | 2 | — |
| resourcemanager | YARN ResourceManager | 1 | 8088 |
| nodemanager1 / nodemanager2 | YARN NodeManager | 2 | — |
| zookeeper | 分布式协调 | 1 | 2181 |
| hbase-master | HBase Master | 1 | 16010 |
| regionserver1 / regionserver2 | HBase RegionServer | 2 | 16020/16030 |

数据链路：Collector (8080) → HDFS → MapReduce → HBase → Query API (8081)

---

## 技术栈

Hadoop 3.2.1 (HDFS / YARN / MapReduce) · HBase 2.4.17 · ZooKeeper 3.7 · Spring Boot 2.7.18 · Java 8 · Maven · Docker Compose

---

## 模块

| 模块 | 说明 |
|------|------|
| hadp-common | 数据模型 (LogEvent / DailyStats / PageStats / HourlyStats)、HBase 连接管理 |
| hadp-collector | Spring Boot 服务，接收日志 → 写入 HDFS (端口 8080) |
| hadp-analytics | 5 个 MapReduce Job，聚合计算 → 写入 HBase |
| hadp-api | Spring Boot 服务，查询 HBase → 返回 JSON (端口 8081) |

---

## MapReduce 任务

| Job | 指标 | HBase 表 | RowKey |
|------|------|----------|--------|
| DailyStatsJob | 每日 PV / UV | daily_stats | `yyyyMMdd` |
| PageStatsJob | 页面 PV | page_stats | `yyyyMMdd_url` |
| HourlyStatsJob | 小时 PV / UV | hourly_stats | `yyyyMMddHH` |
| RetentionAnalysisJob | 用户留存 | user_retention | `baseDate_activeDate` |
| FunnelAnalysisJob | 漏斗转化 | funnel_stats | `date_step` |

漏斗步骤：`/home` → `/products` → `/cart` → `/checkout`

---

## 环境要求

- JDK 1.8
- Maven 3.6+
- Docker Desktop 20.10+
- 8 GB+ 可用内存

Docker Engine 配置中 `registry-mirrors` 保留为空 `[]`。

---

## Build & Run

### 1. 启动 Docker 集群

```powershell
cd D:\hadp
docker compose up -d --build
```

首次构建约 15-25 分钟（下载基础镜像 + Hadoop/HBase 二进制包）。验证：

```powershell
docker compose ps                   # 全部 10 容器应 Up / healthy
docker exec namenode hdfs dfsadmin -report | grep Live   # Live datanodes (2):
docker exec resourcemanager yarn node -list               # Total Nodes:2
```

### 2. 编译 Java 项目

```powershell
$env:JAVA_HOME = "D:\JDK8"; $env:Path = "D:\JDK8\bin;$env:Path"
mvn clean package -DskipTests
```

### 3. 建 HBase 表

```powershell
echo "create 'daily_stats','stats'; create 'page_stats','stats'; create 'hourly_stats','stats'; create 'user_retention','stats'; create 'funnel_stats','stats'; list" | docker exec -i hbase-master hbase shell -n
```

### 4. 生成测试数据

```powershell
docker exec namenode bash -c "
for day in \$(seq 0 7); do
  DS=\$(date -d \"-\$day days\" +%Y/%m/%d)
  hdfs dfs -mkdir -p /user/hadp/logs/\$DS
  for i in \$(seq 1 50); do
    echo \"{\\\"userId\\\":\\\"user_\$((RANDOM%20+1))\\\",\\\"eventType\\\":\\\"page_view\\\",\\\"pageUrl\\\":\\\"/home\\\",\\\"timestamp\\\":\$((\$(date +%s%3N)-day*86400000))}\"
  done | hdfs dfs -put -f - /user/hadp/logs/\$DS/events.json
done
"
```

### 5. 运行 MapReduce

```powershell
docker cp hadp-analytics\target\hadp-analytics-1.0.0.jar namenode:/opt/hadp-analytics.jar
docker exec namenode hadoop jar /opt/hadp-analytics.jar -input /user/hadp/logs -output /user/hadp/output/run -zk zookeeper
```

### 6. 启动 Collector / Query API

```powershell
# Collector (端口 8080)
$env:JAVA_HOME = "D:\JDK8"; java -jar hadp-collector\target\hadp-collector-1.0.0.jar

# Query API (端口 8081)
$env:JAVA_HOME = "D:\JDK8"; java -jar hadp-api\target\hadp-api-1.0.0.jar
```

---

## API

### Collector (8080)

```
GET  /api/logs/health
POST /api/logs/event
POST /api/logs/batch
```

| 字段 | 类型 | 必填 |
|------|------|------|
| userId | string | 是 |
| eventType | string | 否 |
| pageUrl | string | 否 |
| timestamp | long | 是 |
| duration | long | 否 |

### Query API (8081)

```
GET /api/stats/health
GET /api/stats/daily?date=2026-06-01
GET /api/stats/daily/range?start=2026-06-01&end=2026-06-07
GET /api/stats/pages/top?date=2026-06-01&limit=10
GET /api/stats/pages/detail?date=2026-06-01&url=/home
GET /api/stats/hourly?date=2026-06-01
GET /api/stats/retention?date=2026-06-01
GET /api/stats/funnel?date=2026-06-01
POST /api/admin/init
```

---

## HBase 表

| 表 | RowKey | 列族 | 主要列 |
|------|--------|------|------|
| daily_stats | `yyyyMMdd` | stats | pv, uv |
| page_stats | `yyyyMMdd_url` | stats | pv, date, url |
| hourly_stats | `yyyyMMddHH` | stats | pv, uv |
| user_retention | `baseDate_activeDate` | stats | users |
| funnel_stats | `date_step` | stats | c, url, step |

RowKey 按字典序排列，因此同一天/同一基准日的记录在物理存储上连续，范围 Scan 高效。

---

## 常用命令

```powershell
# Docker
docker compose down -v              # 清空全部数据
docker compose logs -f namenode     # 查看日志

# HDFS
docker exec namenode hdfs dfs -ls -R /user/hadp/logs
docker exec namenode hdfs dfsadmin -report

# HBase
docker exec hbase-master bash -c "echo \"scan 'daily_stats', {LIMIT=>5}\" | hbase shell -n"

# YARN
docker exec resourcemanager yarn application -list
```

---

## 项目结构

```
hadp/
├── docker-compose.yml
├── pom.xml
├── README.md
├── hadp-common/src/main/java/com/hadp/common/
│   ├── model/LogEvent.java, DailyStats.java, PageStats.java, HourlyStats.java
│   └── util/HBaseConnectionManager.java
├── hadp-collector/src/main/java/com/hadp/collector/
│   ├── CollectorApplication.java
│   ├── controller/LogController.java
│   └── service/LogService.java
├── hadp-analytics/src/main/java/com/hadp/analytics/
│   ├── DailyStatsJob.java, PageStatsJob.java, HourlyStatsJob.java
│   ├── RetentionAnalysisJob.java, FunnelAnalysisJob.java
│   └── AnalyticsRunner.java
├── hadp-api/src/main/java/com/hadp/api/
│   ├── ApiApplication.java
│   ├── controller/StatsController.java
│   └── service/StatsService.java
├── docker/
│   ├── hadoop.Dockerfile, hbase.Dockerfile
│   ├── hadoop-base.Dockerfile, hbase-base.Dockerfile
│   └── scripts/entrypoint-*.sh
└── scripts/
    ├── generate-sample-data.ps1
    ├── init-hbase.sh
    └── run-analytics.sh
```

---

## 说明

- 伪分布式版本（单容器）见 `docker/hadoop.Dockerfile` 和 `docker/hbase.Dockerfile`
- 真分布式版本见 `docker/hadoop-base.Dockerfile` 和 `docker/hbase-base.Dockerfile`
- Hadoop/HBase 二进制包需预先下载到 `docker/` 目录，文件名需匹配 Dockerfile 中的 COPY 指令
- 宿主机运行 Spring Boot 服务时，需确保 Docker 容器端口映射可访问（或被 hosts 文件正确配置）
