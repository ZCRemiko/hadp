# 面试准备：HADP 项目

---

## 1. 项目介绍（2 分钟版本）

> 我做的是一个分布式用户行为日志分析平台。用 Docker Compose 搭了一个 10 容器的 Hadoop 真分布式集群，包括 HDFS 的 2 个 DataNode、YARN 的 2 个 NodeManager、HBase 的 2 个 RegionServer。
>
> 数据链路是：Spring Boot 采集日志写入 HDFS，然后用 5 个 MapReduce 作业做离线分析——日 PV/UV、页面统计、小时趋势，还有留存和漏斗转化。MapReduce Reducer 聚合完结果直接写 HBase，最后用另一个 Spring Boot 服务从 HBase 读数据返回 JSON。
>
> HBase 的表 RowKey 按 yyyyMMdd 格式设计，这样同一天的数据在物理上是连续存储的，做范围扫描效率很高。

---

## 2. 面试官常问（10 个高频问题）

### Q1：HDFS 的读写流程是什么样的？

**答**：写的时候，客户端先找 NameNode 申请创建文件，NameNode 返回一批 DataNode 的地址，客户端把数据拆成 Block 直接发给第一个 DataNode，然后 DataNode 之间像管道一样逐个转发，每个 Block 默认存 3 个副本。全部写完 NameNode 才标记文件完成。

读的时候，客户端问 NameNode 这个文件有哪些 Block、分别存在哪些 DataNode 上，NameNode 返回位置清单，客户端直接从最近的 DataNode 下载。

### Q2：MapReduce 的 Shuffle 过程是干什么的？

**答**：Map 端输出的是 `(key, value)` 对，存在本地磁盘。Shuffle 阶段框架把相同 key 的数据通过网络发到同一个 Reducer。具体步骤：分区、排序、合并（Combiner 如果配了的话）、网络传输、再合并排序，最后交给 Reduce。

### Q3：你的 PV 和 UV 是怎么算的？

**答**：Mapper 对每条日志输出两个键值对——一个是 `(日期, 1)` 给 PV 计数，一个是 `(日期, USER:用户ID)` 给 UV 去重。Reducer 里对同一日期的数据，遇到 `1` 就 PV 加 1，遇到 `USER:xxx` 就放进 HashSet，最后 UV 就是 Set 的 size。

### Q4：为什么 UV 不能用 Combiner？

**答**：Combiner 在 Map 端做本地预聚合。PV 可以做——100 个 1 本地先加成一个 100，发给 Reducer 只发一个值。但 UV 不行——如果用户 A 在 Map1 和 Map2 都出现，两个 Combiner 各自计为 1，Reducer 收到两个 1 就无法去重了。必须把所有 `USER:A` 都发到同一个 Reducer 再做全局去重。

### Q5：HBase RowKey 为什么设计成 yyyyMMdd？

**答**：HBase 按 RowKey 的字典序排序存储。yyyyMMdd 格式下，字典序和自然时间序一致，20260601 < 20260602 < 20260603。做范围 Scan 时，同一天甚至连续几天的数据在物理上是挨着的，顺序读就行，不需要跨节点跳转。

如果用 UUID 或者时间戳当 RowKey，同一天的数据会散落在集群各处，查询时要全表扫描。

### Q6：HBase 和 MySQL 在这个场景下怎么选？

**答**：我们的数据特点是行数多（每天一条）、列数少（就 pv/uv 几列）、查询简单（按日期查）、没有关联查询。HBase 按 RowKey 定位一行只需要一次 IO，比 MySQL 的 B+ 树索引更适合这种场景。而且 HBase 水平扩展方便，加机器就能扩容，MySQL 做分库分表复杂得多。

如果是关联查询或者事务场景，那肯定选 MySQL。这里不需要。

### Q7：你的 Docker 集群是怎么保证服务启动顺序的？

**答**：docker-compose 的 depends_on 只能保证容器启动顺序，不能保证容器内服务已就绪。NameNode 启动脚本里做了循环等待——先格式化（如果是第一次），启动守护进程，然后 curl 检查 9870 端口是否返回 HTTP 200，等到了才继续下一步。DataNode 也是先等 NameNode 就绪再启动自己的守护进程。HBase 等 ZK 和 HDFS 都通了才开始。

### Q8：你的项目里数据是怎么保证可靠性的？

**答**：HDFS 层面，配置文件设了 dfs.replication=2，每个 Block 存在 2 个 DataNode 上，挂一个节点数据还在。HBase 层面，数据实际存在 HDFS 上，继承了 HDFS 的副本机制。MapReduce 的任务如果某个 NodeManager 挂了，YARN 会自动在其他节点重试。

### Q9：MapReduce 任务失败了怎么办？

**答**：MapReduce 有自动重试机制。单次 Map 或 Reduce 失败，框架会在另一个节点重试，默认重试 4 次。如果全部重试都失败，整个 Job 标记为 FAILED。生产环境可以配置报警，我们项目里就是看 YARN Web UI 和任务日志排查。

### Q10：你这个项目如果有 10 倍数据量，哪里会先出问题？

**答**：首先是 HBase 的热点问题。目前所有数据都按 yyyyMMdd 分布，每天的写入集中在一个 Region，如果某天数据量特别大，这个 Region 所在的 RegionServer 会成为瓶颈。解决方案是 RowKey 加盐或反转——比如在前面加个 hash 前缀，让写入分散。

其次是 MapReduce 阶段。如果数据从 100MB 变成 1TB，Map 任务数量会暴增，YARN 的资源分配可能成为瓶颈。可以调大 split size 减少 Map 数量，或者增加 NodeManager。

---

## 3. 可能被追问的"遇到什么问题、怎么解决的"（结合本项目实际）

### 困难 1：Docker 容器内无法访问外网，Hadoop/HBase 二进制包下载失败

**现象**：Dockerfile 里 wget/curl 下载 archive.apache.org 全部超时，但宿主机可以访问。

**尝试过的方案**：
- 换用国内镜像（清华 TUNA、阿里云镜像）→ 同样被防火墙拦截
- 设置 Docker build 代理 → 过于复杂

**最终方案**：在宿主机下载好 tar.gz，用 COPY 指令复制进镜像，不再在容器内网络下载。

**这样做的理由**：这是个人项目，不需要考虑"换个网络环境怎么办"。如果用实际生产环境，通常会有内部 artifact 仓库或者基础镜像预装好 Hadoop。

### 困难 2：HBase 启动时反复 ConnectionLoss，连不上 ZooKeeper

**现象**：HBase 日志报 `ConnectionLossException`，但 ping 能通。

**原因**：docker-compose 给 ZK 配了 `ZOO_SERVERS=server.1=zookeeper:2888:3888`，这让 ZK 进入了集群模式。单节点集群模式下 ZK 期望至少有半数节点在线才会对外服务，导致 HBase 连不上。

**解决方案对比**：
- 再加 2 个 ZK 节点组成 3 节点集群 → 太重，个人项目用不到
- 去掉 ZOO_SERVERS，用 standalone 模式 → 一行配置解决问题

**最终方案**：设置 `ZOO_STANDALONE_ENABLED=true`，去掉 ZOO_SERVERS。

### 困难 3：HBase standalone 模式无视外部 ZK，始终启动内置 MiniZK

**现象**：已经配了 `HBASE_MANAGES_ZK=false`，HBase 还是启动自己的嵌入式 ZooKeeper。

**原因**：`hbase.cluster.distributed=false` 时，HBase 强制使用内置 ZK，忽略 HBASE_MANAGES_ZK 设置。

**解决方案**：把 `hbase.cluster.distributed` 设为 `true`（伪分布式/真分布式），HBase 就会使用外部 ZK。

### 困难 4：宿主机 API 连接 HBase 返回空数据

**现象**：API 的 HBase Connection 显示连接成功，但查不出数据。

**原因**：HBase 客户端从 ZK 拿到 RegionServer 的地址是 Docker 内部 hostname（如 `regionserver1:16020`），宿主机上这个 hostname 解析不了。

**尝试过的方案**：
- 修改 Windows hosts 文件 → 需要管理员权限，不方便
- 把 Spring Boot 也容器化 → 增加复杂性

**最终方案**：在 HBase Connection 配置里加了 `hbase.ipc.client.specific.addresses`，把 `regionserver1` 映射到 `127.0.0.1`。

**取舍**：这个方案在生产环境不适用（所有 RS 的 IP 都一样怎么区分），但个人项目中所有容器都通过宿主机端口映射暴露，这样做是够用的。

### 困难 5：Shell 脚本换行符导致 "exec format error"

**现象**：容器启动日志反复报 `exec /entrypoint.sh: exec format error`。

**原因**：Windows 下写的脚本默认 CRLF 换行符，Linux 容器不认识。

**解决方案**：`(Get-Content file.sh -Raw) -replace "\r\n","\n" | Set-Content file.sh` 把换行符转成 LF。

---

## 4. 技术选型的"为什么"（面试官可能会问）

| 选型 | 理由 | 备选 |
|------|------|------|
| **Java 8** | Hadoop 3.2 / HBase 2.4 官方编译版本，用更高版本会缺 jdk.tools | Java 11（需额外处理兼容） |
| **HDFS** | 不用预先安装任何软件，Docker 拉起就能跑出分布式效果 | 本地文件系统（失去分布式意义） |
| **HBase** | 按 RowKey 范围扫描是天然优势，适合"按天查询统计值"的场景 | MySQL（更适合关联查询）/ Elasticsearch（更适合全文搜索） |
| **MapReduce** | 学习分布式计算基础概念的最佳入口，YARN 管理资源天然适配 | Spark（更快但掩盖了底层原理） |
| **Docker** | 环境一致性问题——不依赖宿主机已安装 Hadoop | 手动安装（换机重配太费劲） |
| **Docker-Compose 多容器** | 无额外集群成本的真实分布式环境，每个组件独立容器便于观察切换的资源/行为 | 单容器伪分布式（看不到网络通信、节点故障等真分布式行为） |

### 各方案中为什么最终选了当前这个

很多候选组件（Hive 的 SQL 方式分析、Spark 的更快速度、非 Docker 的原生模式、Kafka 的实时缓冲层 等）在学习路线里并非被否决，而这个项目的目标阶段更像是"用较为入门但又是主力生态组件，快速构建出有说服力的分布式链路"。当项目还需要找实习 / 提炼简历时，个人项目的选型也会偏向尽量直观且同学自己真能讲清楚每条链路的选项。因此每个取舍其实都是"当前阶段最优、下一阶段可替换"的打法，比如：

- MapReduce 学懂后，下一轮就可以用 Spark 提速并把分析逻辑移植过去
- Docker 环境稳定后，下一轮如果想学 K8s（容器化编排）也会有经验支撑
- HBase 理解后，想学更现代的分析型数据库（ClickHouse/Doris）也会看出"为什么有的场景更适合实时分析列存"

这些点在面试中表达出来，会比单纯背技术名词更有说服力。
