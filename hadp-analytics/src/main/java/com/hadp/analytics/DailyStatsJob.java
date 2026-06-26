package com.hadp.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hadp.common.model.LogEvent;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 每日统计 MapReduce 任务 - 计算 PV 和 UV
 *
 * ================================================================
 * 【MapReduce 核心概念】
 * ================================================================
 *
 * MapReduce 是 Google 提出的分布式计算模型，Hadoop 是其开源实现。
 * 整个计算过程分为三个阶段：
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  1. MAP 阶段（映射）                                         │
 * │     输入：HDFS 文件（按行读取，每行一条记录）                   │
 * │     处理：解析每行数据，提取关键字段，输出中间键值对             │
 * │     输出：(key, value) 中间结果，写入本地磁盘                   │
 * ├─────────────────────────────────────────────────────────────┤
 * │  2. SHUFFLE 阶段（洗牌，由框架自动完成）                       │
 * │     分区：根据 key 的 hash 值分配到不同的 Reducer               │
 * │     排序：每个分区内的 (key, value) 按 key 排序                │
 * │     分组：相同 key 的 values 合并到一起                        │
 * ├─────────────────────────────────────────────────────────────┤
 * │  3. REDUCE 阶段（归约）                                       │
 * │     输入：同一个 key 的所有 values 的迭代器                    │
 * │     处理：对 values 进行聚合计算（求和、去重、求平均等）         │
 * │     输出：最终结果，写入 HDFS 或 HBase                         │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 【本任务的 MapReduce 流程】
 *
 * 输入文件（JSON Lines 格式）：
 *   {"userId":"u1","eventType":"page_view","pageUrl":"/home","timestamp":1716883200000}
 *   {"userId":"u2","eventType":"page_view","pageUrl":"/products","timestamp":1716883201000}
 *   {"userId":"u1","eventType":"page_view","pageUrl":"/about","timestamp":1716883202000}
 *   ...
 *
 * Mapper 输出：
 *   ("20240528", "1")              ← PV 计数（每条记录一个1）
 *   ("20240528", "USER:u1")        ← UV 去重（用户ID标记）
 *   ("20240528", "1")
 *   ("20240528", "USER:u2")
 *   ("20240528", "1")
 *   ("20240528", "USER:u1")        ← 重复的 u1，Reducer 会去重
 *
 * Shuffle 后到 Reducer：
 *   key="20240528", values=["1","USER:u1","1","USER:u2","1","USER:u1"]
 *
 * Reducer 输出（写入 HBase daily_stats 表）：
 *   Row: "20240528", stats:pv=3, stats:uv=2
 *
 * ================================================================
 */
public class DailyStatsJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyStatsJob.class);

    /** JSON 解析器（线程安全，可以共用） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 日期格式化器 */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));

    /** HBase 列族名 */
    private static final byte[] CF_STATS = Bytes.toBytes("stats");

    // ================================================================
    //  MAPPER 类
    // ================================================================
    /**
     * Mapper：将原始日志解析为中间键值对
     *
     * 泛型参数说明：
     *   LongWritable - 输入键类型（行偏移量，一般不用）
     *   Text         - 输入值类型（文件中的一行文本）
     *   Text         - 输出键类型（日期字符串 "20240528"）
     *   Text         - 输出值类型（"1" 表示PV，"USER:xxx" 表示UV）
     */
    public static class LogMapper extends Mapper<LongWritable, Text, Text, Text> {

        private final Text outputKey = new Text();
        private final Text pvValue = new Text("1");
        private final Text uvValue = new Text();

        /**
         * map 方法：每读取一行输入就调用一次
         *
         * @param key     当前行的字节偏移量（一般不用）
         * @param value   当前行的文本内容（一个 JSON 字符串）
         * @param context 上下文对象，用于输出中间结果和获取配置
         */
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            String line = value.toString().trim();
            if (line.isEmpty()) {
                return; // 跳过空行
            }

            try {
                // -------- 第1步：解析 JSON --------
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);

                if (event.getUserId() == null || event.getTimestamp() == null) {
                    return; // 跳过无效数据
                }

                // -------- 第2步：提取日期作为输出键 --------
                Date date = new Date(event.getTimestamp());
                String dateStr = DATE_FORMAT.get().format(date); // 20240528
                outputKey.set(dateStr);

                // -------- 第3步：输出 PV 计数（每条记录一次）--------
                context.write(outputKey, pvValue);

                // -------- 第4步：输出 UV 标记（用于去重计数）--------
                // 格式："USER:userId"，Reducer 中会提取 userId 去重
                uvValue.set("USER:" + event.getUserId());
                context.write(outputKey, uvValue);

                // 每处理1000条打印一次进度（方便监控）
                if (context.getCounter("LogMapper", "PROCESSED").getValue() % 1000 == 0) {
                    LOG.info("Map进度: 已处理 {} 条记录",
                            context.getCounter("LogMapper", "PROCESSED").getValue());
                }
                context.getCounter("LogMapper", "PROCESSED").increment(1);

            } catch (Exception e) {
                // 记录解析失败的日志并跳过（不中断整个任务）
                LOG.warn("解析日志行失败: {}", e.getMessage());
                context.getCounter("LogMapper", "PARSE_ERROR").increment(1);
            }
        }
    }

    // ================================================================
    //  REDUCER 类
    // ================================================================
    /**
     * Reducer：对同一日期（同一 key）的所有值进行聚合计算
     *
     * 输入示例：
     *   key="20240528", values=["1", "USER:u1", "1", "USER:u2", "1", "USER:u1"]
     *
     * 处理逻辑：
     *   1. 遍历所有 values
     *   2. 遇到 "1" → PV 计数 +1
     *   3. 遇到 "USER:xxx" → 加入 Set 去重
     *   4. 最终 PV=3（3个"1"），UV=2（Set 中有 u1, u2）
     *
     * 【为什么用 Set 去重？】
     * Java HashSet 保证元素唯一性，同一 userId 出现多次只算一次。
     * 例如上面示例中 "USER:u1" 出现了两次，但 Set 中只会保留一个。
     */
    public static class StatsReducer extends Reducer<Text, Text, Text, Text> {

        /** HBase 连接（在 setup 中初始化） */
        private Connection hbaseConn;
        private String zkQuorum;

        /**
         * setup 方法：Reducer 初始化时调用一次
         * 用于创建 HBase 连接等重量级资源
         */
        @Override
        protected void setup(Context context) throws IOException {
            zkQuorum = context.getConfiguration().get("hbase.zookeeper.quorum", "localhost");
            Configuration hbaseConf = HBaseConfiguration.create();
            hbaseConf.set("hbase.zookeeper.quorum", zkQuorum);
            hbaseConf.set("hbase.zookeeper.property.clientPort", "2181");
            hbaseConf.set("hbase.rpc.timeout", "30000");
            hbaseConf.set("hbase.client.retries.number", "3");
            try {
                hbaseConn = ConnectionFactory.createConnection(hbaseConf);
                LOG.info("Reducer HBase 连接成功: zk={}", zkQuorum);
            } catch (IOException e) {
                LOG.error("Reducer HBase 连接失败: {}", e.getMessage());
                throw e;
            }
        }

        /**
         * reduce 方法：对同一 key 的所有 values 执行归约计算
         *
         * @param key     日期字符串 "20240528"
         * @param values  该日期对应的所有值的迭代器
         * @param context 上下文对象，用于输出最终结果
         */
        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {

            long pv = 0;
            Set<String> uniqueUsers = new HashSet<>();

            // -------- 遍历所有值，分类统计 --------
            for (Text val : values) {
                String str = val.toString();
                if (str.startsWith("USER:")) {
                    // UV 去重：提取 userId 并加入 Set
                    String userId = str.substring(5); // 去掉 "USER:" 前缀
                    uniqueUsers.add(userId);
                } else {
                    // PV 计数：每条记录 +1
                    pv++;
                }
            }

            long uv = uniqueUsers.size();
            LOG.debug("Reducer: date={}, PV={}, UV={}", key.toString(), pv, uv);

            // -------- 写入 HBase 表 --------
            writeToHBase(key.toString(), pv, uv);

            // -------- 同时输出到 HDFS（用于调试和备份）--------
            context.write(key, new Text(String.format("%d\t%d", pv, uv)));
        }

        /**
         * 将统计结果写入 HBase 表 daily_stats
         *
         * @param date 日期字符串 "20240528"
         * @param pv   页面浏览量
         * @param uv   独立访客数
         */
        private void writeToHBase(String date, long pv, long uv) {
            Table table = null;
            try {
                table = hbaseConn.getTable(TableName.valueOf("daily_stats"));

                // 创建 Put 对象（HBase 的写入操作）
                // 参数：RowKey 的字节形式
                Put put = new Put(Bytes.toBytes(date));

                // 添加数据：put.addColumn(列族, 列名, 值)
                // HBase 中所有数据都存储为字节数组
                put.addColumn(CF_STATS, Bytes.toBytes("pv"), Bytes.toBytes(pv));
                put.addColumn(CF_STATS, Bytes.toBytes("uv"), Bytes.toBytes(uv));

                table.put(put);
                LOG.debug("HBase 写入成功: date={}, pv={}, uv={}", date, pv, uv);

            } catch (IOException e) {
                LOG.error("HBase 写入失败: date={}, {}", date, e.getMessage());
            } finally {
                if (table != null) {
                    try {
                        table.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        /**
         * cleanup 方法：Reducer 销毁前调用一次
         * 用于关闭连接等清理工作
         */
        @Override
        protected void cleanup(Context context) {
            if (hbaseConn != null) {
                try {
                    hbaseConn.close();
                    LOG.info("Reducer HBase 连接已关闭");
                } catch (IOException e) {
                    LOG.warn("关闭 HBase 连接时出错: {}", e.getMessage());
                }
            }
        }
    }

    // ================================================================
    //  主方法：配置并提交 MapReduce 任务
    // ================================================================
    /**
     * 创建并配置 DailyStats MapReduce 任务
     *
     * @param inputPath   HDFS 输入路径，例如 /user/hadp/logs/2024/05/28
     * @param outputPath  HDFS 输出路径，例如 /user/hadp/output/daily_20240528
     * @param zkQuorum    ZooKeeper 地址（HBase 依赖），例如 localhost:2181
     * @return 配置好的 Job 对象
     */
    public static Job createJob(String inputPath, String outputPath, String zkQuorum)
            throws IOException {

        // -------- 第1步：创建 Job 对象 --------
        Configuration conf = new Configuration();
        // HBase 的 ZooKeeper 地址，传递给 Mapper/Reducer
        conf.set("hbase.zookeeper.quorum", zkQuorum);

        Job job = Job.getInstance(conf, "DailyStats-" + System.currentTimeMillis());

        // -------- 第2步：指定主类（包含 Mapper/Reducer 的类）--------
        job.setJarByClass(DailyStatsJob.class);

        // -------- 第3步：设置 Mapper 和 Reducer --------
        job.setMapperClass(LogMapper.class);
        job.setReducerClass(StatsReducer.class);

        // -------- 第4步：设置输出类型 --------
        // Map 输出类型（必须和 Mapper 的泛型一致）
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        // Reduce 输出类型（必须和 Reducer 的泛型一致）
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        // -------- 第5步：设置输入格式和路径 --------
        job.setInputFormatClass(TextInputFormat.class);  // 按行读取文本文件
        FileInputFormat.addInputPath(job, new org.apache.hadoop.fs.Path(inputPath));
        // 支持递归读取子目录（因为日志按天分目录存储）
        FileInputFormat.setInputDirRecursive(job, true);

        // -------- 第6步：设置输出路径（运行时由参数传入）--------
        org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(
                job, new org.apache.hadoop.fs.Path(outputPath));

        // -------- 第7步：性能优化配置 --------
        // Map 端使用 Combiner（本地预聚合，减少网络传输）
        // 注意：UV去重不能简单用Combiner，所以这里不设置
        // conf.setCombinerClass(...);

        return job;
    }

    /**
     * 直接运行此任务的 main 方法（用于单独测试）
     *
     * 用法: hadoop jar jarfile com.hadp.analytics.DailyStatsJob <input> <output> <zk>
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: DailyStatsJob <inputPath> <outputPath> [zkQuorum]");
            System.err.println("示例: DailyStatsJob /user/hadp/logs/2024/05/28 /user/hadp/output/daily zookeeper");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args[1];
        String zkQuorum = args.length > 2 ? args[2] : "localhost";

        Job job = createJob(inputPath, outputPath, zkQuorum);
        boolean success = job.waitForCompletion(true);
        System.exit(success ? 0 : 1);
    }
}
