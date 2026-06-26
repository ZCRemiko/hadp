# HADP — 从零构建指南

---

本文档面向**零基础学生**，按项目实际构建顺序，逐步解释每一步做了什么、为什么这么做、技术选型的考量。

---

## 目录

1. [项目概览：我们要做什么](#1-项目概览我们要做什么)
2. [环境准备](#2-环境准备)
3. [Maven 多模块工程搭建](#3-maven-多模块工程搭建)
4. [Docker：为什么要用容器，为什么用分布式](#4-docker为什么要用容器为什么用分布式)
5. [HDFS：分布式文件系统](#5-hdfs分布式文件系统)
6. [Collector：日志采集服务](#6-collector日志采集服务)
7. [MapReduce：离线分析引擎](#7-mapreduce离线分析引擎)
8. [HBase：分布式列式数据库](#8-hbase分布式列式数据库)
9. [Query API：对外提供数据](#9-query-api对外提供数据)
10. [扩展新功能：留存分析与漏斗分析](#10-扩展新功能留存分析与漏斗分析)
11. [伪分布式 → 真分布式](#11-伪分布式--真分布式)
12. [常见错误与排查](#12-常见错误与排查)

---

## 1. 项目概览：我们要做什么

### 1.1 一句话描述

搭建一个**分布式日志分析平台**：用户行为日志 → 存储 → 离线计算 → 查询。

### 1.2 数据怎么流动

```
POST /api/logs/event  ──→  Collector (Spring Boot)  ──→  HDFS (分布式文件系统)
                                                              │
                                                   MapReduce 读取 ↓
                                                              │
                              HBase (分布式数据库)  ←──  聚合计算结果
                                     │
                              GET /api/stats/daily  ←──  Query API (Spring Boot)
```

### 1.3 为什么分这么多层

| 层 | 职责 | 为什么不用一个程序全干 |
|------|------|------|
| Collector | 收日志 | 解耦：采集和计算拆开，采集挂了不影响分析 |
| HDFS | 存日志 | 海量数据单机存不下，需要分散存储 |
| MapReduce | 算指标 | 数据量大到单机算不动，需要并行计算 |
| HBase | 存结果 | 结果需要快速查询，按 RowKey 定位比扫文件快 |
| Query API | 对外服务 | 前端/其他系统通过 HTTP 拿数据，不应直连数据库 |

---

## 2. 环境准备

### 2.1 你需要装什么

| 软件 | 版本 | 作用 |
|------|------|------|
| Java JDK | 1.8 | 编译和运行 Java 代码 |
| Maven | 3.6+ | 项目构建和依赖管理 |
| Docker Desktop | 20.10+ | 运行 Hadoop/HBase/ZooKeeper 容器 |
| VS Code | - | 代码编辑器 |

### 2.2 为什么选 Java 8

Hadoop 3.2.x 和 HBase 2.4.x 的官方编译版本对应 Java 8。用更高版本会导致编译错误（如 `jdk.tools` 找不到）或运行时异常。

### 2.3 验证安装

```powershell
java -version          # 应输出 1.8.x
mvn --version          # 应输出 3.6+
docker ps              # 应能看到运行中的容器（或空列表）
```

---

## 3. Maven 多模块工程搭建

### 3.1 项目结构

```
hadp/
├── pom.xml                 # 父 POM：统一管理所有子模块的依赖版本
├── hadp-common/            # 公共模块：数据模型 + 工具类
├── hadp-collector/         # 日志采集：收 HTTP 请求，写 HDFS
├── hadp-analytics/         # 离线分析：MapReduce 任务
└── hadp-api/               # 查询接口：读 HBase，返回 JSON
```

### 3.2 为什么用多模块

如果写一个巨大的项目，代码耦合在一起，改一行可能影响全局。拆成 4 个模块的好处：

- **hadp-common** 被其他 3 个模块依赖，公共代码只写一次
- **hadp-collector** 和 **hadp-api** 可以独立部署、独立扩缩
- **hadp-analytics** 是批处理任务，打包成独立 JAR 提交到 YARN 集群
- 改 Collector 不需要重新编译 API，改 API 不需要碰 MapReduce

### 3.3 父 POM 的关键配置

```xml
<!-- 统一版本管理：子模块不需要写版本号 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>2.7.18</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**为什么这样做**：如果每个子模块各自声明 Spring/Hadoop/HBase 的版本号，容易出现版本冲突。父 POM 集中管理，一个地方改全局生效。

### 3.4 Hadoop 依赖注意点

```xml
<!-- Hadoop 在 analytics 模块中 scope=provided -->
<dependency>
    <groupId>org.apache.hadoop</groupId>
    <artifactId>hadoop-common</artifactId>
    <scope>provided</scope>
</dependency>
```

**`scope=provided` 的含义**：编译时需要，但不打包进 JAR。因为 Hadoop 集群上已经有这些 JAR 了，打包进去会冲突。但 HBase 的 JAR 需要打包（集群上可能没有）。

---

## 4. Docker：为什么要用容器，为什么用分布式

### 4.1 不用 Docker 会怎样

装 Hadoop 需要：下载解压 → 配 4 个 XML 文件 → 设环境变量 → 格式化 NameNode → 启动 4 个守护进程。装 HBase 还要配 ZK → 配 HDFS 路径 → 启动 2 个守护进程。换台机器全部重来。

**Docker 解决什么**：把所有安装步骤写成 Dockerfile，一台机器 `docker compose up -d` 就能起整个集群。删掉就 `docker compose down`，不留系统残留。

### 4.2 Docker 基本概念（必读）

| 概念 | 对比 | 说明 |
|------|------|------|
| **镜像 (Image)** | 安装光盘 | 只读模板，包含操作系统 + 软件 |
| **容器 (Container)** | 运行中的虚拟机 | 镜像的运行时实例 |
| **Dockerfile** | 安装说明书 | 如何从零构建一个镜像 |
| **docker-compose.yml** | 部署文档 | 如何同时启动多个容器并让它们互通 |

### 4.3 真分布式 vs 伪分布式

| | 伪分布式 | 真分布式 |
|------|------|------|
| NameNode | 1 个进程 | 1 个独立容器 |
| DataNode | 和 NameNode 在同一个 JVM | 2 个独立容器 |
| ResourceManager | 同上 | 1 个独立容器 |
| NodeManager | 同上 | 2 个独立容器 |
| 数据副本 | dfs.replication=1（无备份） | dfs.replication=2（一份数据存两份） |
| 网络通信 | 单机内部（无网络开销） | 容器间通过 Docker 网络 |
| 学习价值 | 理解概念 | 理解分布式真正运作方式 |

**为什么从伪分布式开始**：先确保代码能跑，再拆成真分布式。如果一开始就上 10 容器，出问题不知道是代码错了还是网络配错了。

### 4.4 我们的 10 容器架构

```
namenode          ← HDFS 主节点（谁存了哪个文件）
datanode1/2       ← 实际存数据的机器（副本=2）
resourcemanager   ← YARN 老大（谁有空算任务）
nodemanager1/2    ← YARN 工人（执行 Map/Reduce）
zookeeper         ← 协调 HBase（谁活着谁挂了）
hbase-master      ← HBase 管家（Region 分配）
regionserver1/2   ← HBase 工人（实际读写数据）
```

**每个组件独立容器的好处**：一个挂了不影响其他，可以分别查看日志，资源隔离。

---

## 5. HDFS：分布式文件系统

### 5.1 核心概念

```
文件 → 切分成 Block（默认 128MB）
     → 每个 Block 复制到多台 DataNode（默认 3 副本）
     → NameNode 记录"第 N 个 Block 存在哪几台机器上"
```

**为什么切块**：一个大文件 1GB，一台机器存不下怎么办？切成 8 个 128MB 的块，分散到 8 台机器。

**为什么要副本**：一台机器宕机，数据从副本机器恢复，不影响服务。

### 5.2 我们的 HDFS 配置

```xml
<!-- core-site.xml：告诉所有客户端"NameNode 在哪" -->
<property>
    <name>fs.defaultFS</name>
    <value>hdfs://namenode:9000</value>
</property>

<!-- hdfs-site.xml：几份副本 -->
<property>
    <name>dfs.replication</name>
    <value>2</value>  <!-- 伪分布式=1，真分布式=2 -->
</property>
```

### 5.3 日志怎么存的

Collector 收到一条日志事件，写入 HDFS 路径：`/user/hadp/logs/2026/06/25/events_20260625_test.json`

**为什么按日期分区**：
- MapReduce 按天分析时，只需读一个目录，不用扫一周的数据
- 删除旧数据时，直接删一个目录，不需要逐行删除

**JSON Lines 格式**：每行一个完整的 JSON 对象。MapReduce 默认按行读取输入，天然适配。

---

## 6. Collector：日志采集服务

### 6.1 Spring Boot 是什么

Spring Boot 是 Java 生态中最流行的 Web 框架。你不需要自己写启动 Tomcat 的代码，只需加个注解：

```java
@SpringBootApplication
public class CollectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
```

底层 Spark Boot 自动完成：启动内嵌 Tomcat → 扫描所有 @Controller → 注册 REST 端点 → 注入 @Service。

### 6.2 关键代码：写 HDFS

```java
// 第1步：拿到 HDFS 客户端
FileSystem fileSystem = FileSystem.get(new URI("hdfs://namenode:9000"), conf, "root");

// 第2步：确定文件路径（按日期分区）
String filePath = String.format("/user/hadp/logs/%s/events_%s.json", datePart, filePart);

// 第3步：写入数据（追加模式）
FSDataOutputStream out = fileSystem.append(new Path(filePath));
out.writeBytes(jsonLine + "\n");
out.close();
```

**HDFS 写操作的注意点**：
- HDFS 只支持**追加写**，不支持随机修改（删一行、改一行都不行）
- 如果文件不存在，先用 `create()` 创建，再用 `append()`
- 写入后需要 `close()` 才能真正落盘

### 6.3 最小编程模型

```
@RestController        ← 这个类是 REST API 入口
  └─ @RequestMapping   ← 所有方法的 URL 前缀
       └─ @PostMapping  ← 具体方法的 URL + HTTP 动词
            └─ @RequestBody  ← 把 HTTP Body 自动转成 Java 对象
```

---

## 7. MapReduce：离线分析引擎

### 7.1 核心思想：分而治之

```
原始日志（400条）
     │
     ├──→ Map 1（100条）──→ 中间结果──┐
     ├──→ Map 2（100条）──→ 中间结果──┤
     ├──→ Map 3（100条）──→ 中间结果──┼──→ Shuffle（按 key 分组）──→ Reduce──→ 最终结果
     └──→ Map 4（100条）──→ 中间结果──┘
```

**Map 阶段**：每个 Map 处理一部分数据（如 1 个 HDFS Block），输出 (key, value) 中间结果。

**Shuffle 阶段**（框架自动完成）：把相同 key 的中间结果分到同一个 Reduce。

**Reduce 阶段**：对同一 key 的所有 value 做聚合（加和、去重、求平均）。

### 7.2 我们写的 5 个 Job

#### Job 1：DailyStatsJob — 每日 PV + UV

```
Map: 输入 {"userId":"user_001","timestamp":1716883200000}
     输出 ("20260601", "1")           ← PV 每个事件一个 1
     输出 ("20260601", "USER:user_001") ← UV 每个用户一个标记

Reduce: 同一日期下
       PV = 数有多少个 "1"
       UV = 去重所有 "USER:xxx"，数不重复的 userId

写入 HBase: put 'daily_stats', '20260601', 'stats:pv', 42
           put 'daily_stats', '20260601', 'stats:uv', 10
```

**PV vs UV 的计算逻辑**：

| 指标 | 含义 | Map 输出 | Reduce 计算 |
|------|------|---------|------------|
| PV | 页面浏览量 | 每条事件 +1 | 累加 |
| UV | 独立访客 | 每条事件标识用户 | HashSet 去重后计数 |

**Q: 为什么 UV 不能用 Combiner 优化？**

Combiner 在 Map 端做本地预聚合，减少网络传输。但 UV 需要全局去重 —— 用户 A 在 Map1 和 Map2 都出现了，两个 Combiner 各自计为 1，Reduce 端收到两个 1 就会重复计算。所以 UV 不能 Combiner，PV 可以。

#### Job 2：PageStatsJob — 页面统计

```
Map: 输出 (日期|页面URL, 1)
Reduce: 累加同一日期的同一页面的所有 1 = 该页面 PV
```

#### Job 3：HourlyStatsJob — 小时趋势

```
Map: 输出 (yyyyMMddHH, 1) + (yyyyMMddHH, USER:xxx)
Reduce: 同 DailyStats，但 key 到小时粒度
```

每小时统计用于**流量监控**和**异常检测**（凌晨 3 点突然峰值 → 可能是攻击）。

#### Job 4：RetentionAnalysisJob — 用户留存

```
Map: 输出 (userId, date)  —— 标记每个用户在哪几天活跃过
Reduce: 对同一 userId，收集其全部活跃日期 → 存入 user_retention 表
```

**留存率怎么算**：基准日活跃 100 人，3 天后仍有 40 人活跃 → 3 日留存率 40%。

**业务价值**：衡量产品对用户的粘性。留存率高说明用户真的在用产品，不是一次性流量。

#### Job 5：FunnelAnalysisJob — 漏斗转化

```
漏斗定义: /home → /products → /cart → /checkout
Map: 检查每次访问的 pageUrl 是否在漏斗步骤中，输出 (userId, 步骤号|日期)
Reduce: 对同一 userId，取当天最高步骤号，则该用户经过了 0..maxStep 所有步骤
```

**业务价值**：找出转化瓶颈。如果 /home 到 /products 转化率 80%，但 /cart 到 /checkout 只有 10%，说明购物车页面（或支付流程）有问题。

### 7.3 任务编排

`AnalyticsRunner.java` 按顺序调用 5 个 Job：

```java
// 为什么顺序执行而不是并行？
// 1. 避免 YARN 资源争抢
// 2. 每个 Job 都有 Counters 监控，串行便于排查
// 3. 生产环境中可以用调度工具（Oozie/Azkaban）做 DAG 编排

dailySuccess  = runJob("day",    ...);  // 第1步
pageSuccess   = runJob("page",   ...);  // 第2步
hourlySuccess = runJob("hour",   ...);  // 第3步
retention     = runJob("retention",...);  // 第4步
funnel        = runJob("funnel", ...);  // 第5步
```

---

## 8. HBase：分布式列式数据库

### 8.1 为什么用 HBase 而不是 MySQL

| | MySQL | HBase |
|------|------|------|
| 存储结构 | 行式（一行所有列一起存） | 列式（列族独立存储） |
| 扩展方式 | 垂直扩展（换更大的机器） | 水平扩展（加更多机器） |
| 稀疏数据 | 浪费空间（NULL 列也要占位） | 不占空间（没数据的列不存储） |
| 查询方式 | SQL，支持复杂 Join | RowKey 定位 + Scan，不支持 Join |
| 适合场景 | 事务、关联查询 | 海量数据按 RowKey 检索 |

**我们为什么选 HBase**：日志分析结果的特点是"行数多（每天一条）、列数少（就 pv/uv 几列）、查询简单（按日期查）"。这种场景 HBase 比 MySQL 高效得多。

### 8.2 HBase 核心概念

```
表 (Table)
  └─ 行 (Row)          ← 由 RowKey 唯一标识
       └─ 列族 (ColumnFamily)  ← 表创建时定义，通常 1-2 个足够
            └─ 列 (Column)     ← 可动态添加，不需要预定义
                 └─ 值 (Value) ← 字节数组，可存任意类型
```

### 8.3 RowKey 设计哲学

```
daily_stats:    "20260601"           ← 8位日期，字典序 = 时间序
page_stats:     "20260601_/home"     ← 前缀过滤查同一天所有页面
hourly_stats:   "2026060116"         ← 扫一天24小时只需一次 Scan
funnel_stats:   "20260601_0"         ← 同一天所有步骤连续存储
```

**核心原则**：把查询最频繁的维度放在 RowKey 前缀，利用 HBase 按 RowKey 字典序排序存储的特性。

**反面例子**：如果 RowKey 用 `uuid_pageUrl`，那同一天的数据会分散在集群各处，每次查询都要全表扫。

### 8.4 读取方式

```java
// 方式1：Get — 精确查询一行
Get get = new Get(Bytes.toBytes("20260601"));
Result r = table.get(get);

// 方式2：Scan — 范围扫描
Scan scan = new Scan()
    .withStartRow(Bytes.toBytes("20260601"))      // 起始（含）
    .withStopRow(Bytes.toBytes("20260602"))        // 结束（不含）
    .setFilter(new PrefixFilter(Bytes.toBytes("20260601_")));  // 前缀过滤
ResultScanner scanner = table.getScanner(scan);
```

**Scan 的效率**：HBase 按 RowKey 排序存储，所以扫描一段连续的 RowKey 不需要全表遍历，只需定位到起始行然后顺序读。

### 8.5 Connection 管理

```java
// 为什么用单例？
// HBase Connection 是重量级对象：需要与 ZooKeeper 建会话、获取集群拓扑、
// 维护到每个 RegionServer 的连接池。每次 new 一个代价太大。
// 所以用双重检查锁实现懒加载单例。

private static volatile Connection connection;

public static Connection getConnection(String zkQuorum) {
    if (connection == null) {
        synchronized (LOCK) {
            if (connection == null) {
                connection = ConnectionFactory.createConnection(config);
            }
        }
    }
    return connection;
}
```

---

## 9. Query API：对外提供数据

### 9.1 最小编程模型

```
用户请求 GET /api/stats/daily?date=2026-06-01
  → StatsController.getDailyStats()
    → StatsService.getDailyStats(date)
      → 格式化 RowKey "20260601" → HBase Get → 读 Long 值
        → 组装 DailyStats 对象 → 返回 JSON
```

### 9.2 为什么 API 不能直连 HBase

如果让前端直连 HBase：
1. 需要把 HBase 端口暴露到公网（安全风险）
2. 前端需要知道 HBase 的 Java API（技术耦合）
3. 无法做权限控制、限流、日志审计

加一层 Spring Boot API：前端只和 HTTP+JSON 打交道，后端统一做权限、格式化、错误处理。

### 9.3 数据类型的坑

HBase 存的是**字节数组**。同一个值可以是字符串 "100" 的字节，也可以是 Long 值 100 的二进制编码（8 个字节）。

MapReduce 写入时用 `Bytes.toBytes(long)` 写的是二进制编码，Java 读的时候也要用 `Bytes.toLong()` 解。但如果用 HBase Shell 手工插入，Shell 存的是字符串。所以 API 需要兼容两种：

```java
private Long getLongValue(Result result, String column) {
    byte[] value = result.getValue(CF, Bytes.toBytes(column));
    if (value == null || value.length == 0) return 0L;
    if (value.length == 8) return Bytes.toLong(value);        // 二进制
    return Long.parseLong(Bytes.toString(value));              // 字符串
}
```

---

## 10. 扩展新功能：留存分析与漏斗分析

### 10.1 怎么加一个新 Job

1. 创建 `RetentionAnalysisJob.java`（含 Mapper + Reducer）
2. 在 `AnalyticsRunner.java` 的 switch 中添加 `case "retention"`
3. 在 `StatsService.java` 的 `initTables()` 中添加建表语句
4. 在 `StatsController.java` 中添加 API 端点
5. 运行 `mvn clean package -DskipTests` 重新编译

### 10.2 存留分析的设计思路

**为什么不在一个 Job 里算出所有留存率？**

留存分析需要跨天数据关联。Mapper 将每条日志标记为 `(userId, date)` 。Reducer 中收集每个用户的所有活跃日期。在后续查询阶段，APL逐日对比。

**为什么 MapReduce Job 不直接输出率值？**

因为 Reduce 时只需要知道每个活跃日的所有独有用户。留存率的真正计算 ("基准日有: N, 留存日的用户数: M → M/N") 更适合在 API 层动态计算，避免计算结果过期。

### 10.3 漏斗分析的设计思路

**漏斗步骤如何界定？**

我们把每页的一次访问都视为通过了此页面所在步骤。因为每个 session 可能会多次回访同一页，用最高步骤数来判定最终走到的阶段。每个用户的"每天最高步骤"作为其在当天的漏斗进度。

**为什么这样设计？而不是严格的按顺序？**

真实环境中的用户行为不是线性的——有人从 /cart 回到 /products，有人直接访问 /checkout。用严格顺序会丢失大量数据。基于最高步骤的分析方法既能发现大部分用户的通常路径，又能容忍用户来回横向浏览产生的噪声。

---

## 11. 伪分布式 → 真分布式

### 11.1 改了什么

| 项目 | 原（伪分布式） | 新（真分布式） |
|------|------|------|
| 容器数 | 3 | 10 |
| HDFS | 单 NameNode + DataNode 同容器 | NameNode 独立，2 个独立 DataNode |
| YARN | 单 RM + NM 同容器 | RM 独立，2 个独立 NM |
| HBase | 单 Master + RS 同容器 | Master 独立，2 个独立 RS |
| 副本数 | 1（不备份） | 2（每块一份副本） |
| 通信 | 无跨容器网络调用 | 全部通过 Docker 网络通信 |

### 11.2 核心改动

1. **Dockerfile 拆分**：`hadoop-base.Dockerfile`（只装不启动） + `hbase-base.Dockerfile`（只装不启动）
2. **Entrypoint 拆分**：每个角色一个启动脚本（`entrypoint-namenode.sh`、`entrypoint-datanode.sh` 等）
3. **配置文件更新**：`fs.defaultFS` 从 `localhost:9000` 改为 `namenode:9000`，`dfs.replication` 从 1 改为 2
4. **workers/regionservers 文件**：告诉 Master 哪些节点是 Worker

### 11.3 验证真分布式生效

```powershell
# 查看 HDFS 是否有 2 个 Live DataNode
docker exec namenode hdfs dfsadmin -report | grep "Live"

# 查看 YARN 是否有 2 个 Node
docker exec resourcemanager yarn node -list

# 查看 HBase 是否有 2 个 RegionServer
docker exec hbase-master bash -c "echo 'status' | hbase shell -n" | grep "servers"
```

输出示例：
```
Live datanodes (2):
Total Nodes:2
1 active master, 2 servers, 0 dead
```

---

## 12. 常见错误与排查

### 12.1 Maven 构建失败

| 错误 | 原因 | 解决 |
|------|------|------|
| `Could not find artifact jdk.tools` | JAVA_HOME 指向 JRE 而非 JDK | 设置为 JDK 路径（含 tools.jar） |
| `case 标签重复` | switch 中两个 case 值相同 | 检查 AnalyticsRunner 中 case 是否重复 |
| 依赖找不到 | hadp-common 未 install | 先 `mvn install -pl hadp-common` |

### 12.2 Docker 构建失败

| 错误 | 原因 | 解决 |
|------|------|------|
| `403 Forbidden` | 镜像加速器不可用 | Docker Engine 配置中清空 registry-mirrors |
| `port already allocated` | 旧容器占用端口 | `docker rm -f 容器名` |
| `exec format error` | 脚本有 Windows 换行符 | 转换为 LF (`\r\n` → `\n`) |

### 12.3 HDFS / HBase 连接失败

| 错误 | 原因 | 解决 |
|------|------|------|
| `Connection refused: hadoop:9000` | NameNode 只绑定了 localhost | 改为 `hdfs://namenode:9000` |
| `ConnectionLoss for /hbase` | ZK 在集群模式下有问题 | 设置 `ZOO_STANDALONE_ENABLED=true` |
| `Can't get master address` | HBase standalone 模式忽略外部 ZK | 设置 `hbase.cluster.distributed=true` + `HBASE_MANAGES_ZK=false` |

### 12.4 MapReduce 不写 HBase

检查清单：
1. ZK quorum 是否正确传入（`-zk zookeeper`）
2. NodeManager 容器能否 ping 通 `zookeeper`
3. 表是否已创建（`list` 命令检查）
4. Reducer 日志中是否有 HBase 相关 error（用 `yarn logs -applicationId` 查看）

---

## 附录：技术选型对比

### A. 为什么用 Hadoop 而不是 Spark

| | Hadoop MapReduce | Spark |
|------|------|------|
| 速度 | 磁盘中间结果，较慢 | 内存计算，快 10-100x |
| 稳定性 | 成熟，极少 Bug | 内存溢出风险高 |
| 学习曲线 | 概念简单（Map→Reduce） | 概念多（RDD/DF/DS） |
| 适合此处 | 学习分布式计算的基础 | 生产环境替代方案 |

**结论**：学习用 MapReduce 打基础。理解了 MapReduce 的工作方式，再用 Spark 会很快上手。

### B. 为什么用 HBase 而不是 Elasticsearch

| | HBase | Elasticsearch |
|------|------|------|
| 存储引擎 | HDFS | Lucene（倒排索引） |
| 查询能力 | RowKey + Scan | 全文搜索 + 聚合 |
| 运维复杂度 | 依赖 HDFS + ZK | 自身即完整系统 |
| 适合此处 | 按日期查询统计值 | 搜索日志原文 |

**结论**：我们的查询是"某天 PV 多少"，不需要全文搜索，HBase 更合适。如果能加上搜索日志原文的需求，可以引入 ES。

### C. 为什么 Spring Boot 不作为 Docker 服务

Spring Boot 服务跑在宿主机上而不是 Docker 容器里，是因为：
- Collector 需要访问宿主机端口映射的 HDFS（hadoop:9000）
- 如果放入 Docker，需要配置 Docker 内网络解析（增加复杂度）
- 学生调试时，IDE 直接启动 Spring Boot 比 docker exec 方便

---
