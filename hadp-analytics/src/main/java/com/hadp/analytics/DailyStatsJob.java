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
 * 每日统计 MapReduce 任务，计算每日 PV 与 UV。
 * Mapper 以日期为 key 输出 PV 计数（"1"）与 UV 标记（"USER:userId"）；
 * Reducer 累加 PV 并用 Set 对 userId 去重得到 UV，写入 HBase daily_stats。
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

    /**
     * Mapper：将原始日志解析为 (日期, PV/UV 标记) 中间键值对。
     */
    public static class LogMapper extends Mapper<LongWritable, Text, Text, Text> {

        private final Text outputKey = new Text();
        private final Text pvValue = new Text("1");
        private final Text uvValue = new Text();

        /**
         * 每读取一行输入调用一次。
         */
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            String line = value.toString().trim();
            if (line.isEmpty()) {
                return;
            }

            try {
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);

                if (event.getUserId() == null || event.getTimestamp() == null) {
                    return;
                }

                Date date = new Date(event.getTimestamp());
                String dateStr = DATE_FORMAT.get().format(date);
                outputKey.set(dateStr);

                context.write(outputKey, pvValue);

                // "USER:userId" 标记交由 Reducer 去重
                uvValue.set("USER:" + event.getUserId());
                context.write(outputKey, uvValue);

                if (context.getCounter("LogMapper", "PROCESSED").getValue() % 1000 == 0) {
                    LOG.info("Map进度: 已处理 {} 条记录",
                            context.getCounter("LogMapper", "PROCESSED").getValue());
                }
                context.getCounter("LogMapper", "PROCESSED").increment(1);

            } catch (Exception e) {
                // 解析失败的行跳过，不中断任务
                LOG.warn("解析日志行失败: {}", e.getMessage());
                context.getCounter("LogMapper", "PARSE_ERROR").increment(1);
            }
        }
    }

    /**
     * Reducer：聚合同一日期的值，累加 PV，用 Set 对 userId 去重得到 UV。
     */
    public static class StatsReducer extends Reducer<Text, Text, Text, Text> {

        /** HBase 连接（在 setup 中初始化） */
        private Connection hbaseConn;
        private String zkQuorum;

        /**
         * 初始化 HBase 连接（每个 Reducer 一次）。
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
         * 归约同一 key 的所有值。
         */
        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {

            long pv = 0;
            Set<String> uniqueUsers = new HashSet<>();

            for (Text val : values) {
                String str = val.toString();
                if (str.startsWith("USER:")) {
                    String userId = str.substring(5); // 去掉 "USER:" 前缀
                    uniqueUsers.add(userId);
                } else {
                    pv++;
                }
            }

            long uv = uniqueUsers.size();
            LOG.debug("Reducer: date={}, PV={}, UV={}", key.toString(), pv, uv);

            writeToHBase(key.toString(), pv, uv);

            // 同时输出到 HDFS 备份
            context.write(key, new Text(String.format("%d\t%d", pv, uv)));
        }

        /**
         * 将统计结果写入 HBase daily_stats。
         */
        private void writeToHBase(String date, long pv, long uv) {
            Table table = null;
            try {
                table = hbaseConn.getTable(TableName.valueOf("daily_stats"));

                Put put = new Put(Bytes.toBytes(date));
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
         * 关闭 HBase 连接。
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

        Configuration conf = new Configuration();
        conf.set("hbase.zookeeper.quorum", zkQuorum);

        Job job = Job.getInstance(conf, "DailyStats-" + System.currentTimeMillis());

        job.setJarByClass(DailyStatsJob.class);

        job.setMapperClass(LogMapper.class);
        job.setReducerClass(StatsReducer.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setInputFormatClass(TextInputFormat.class);
        FileInputFormat.addInputPath(job, new org.apache.hadoop.fs.Path(inputPath));
        // 日志按天分目录存储，需递归读取
        FileInputFormat.setInputDirRecursive(job, true);

        org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(
                job, new org.apache.hadoop.fs.Path(outputPath));

        // UV 去重不能用 Combiner 做本地预聚合，因此不设置

        return job;
    }

    /**
     * 命令行入口：DailyStatsJob &lt;input&gt; &lt;output&gt; [zkQuorum]
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
