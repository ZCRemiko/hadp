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

/**
 * 小时级统计 MapReduce 任务
 *
 * 按小时统计 PV 和 UV，用于流量监控和异常检测
 *
 * key 格式: "yyyyMMddHH"（例如 "2024052816"）
 * 比每日统计更细粒度，便于发现峰值时段
 *
 * HBase 表: hourly_stats
 * RowKey: yyyyMMddHH
 */
public class HourlyStatsJob {

    private static final Logger LOG = LoggerFactory.getLogger(HourlyStatsJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 小时格式化: "yyyyMMddHH" */
    private static final ThreadLocal<SimpleDateFormat> HOUR_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHH"));

    private static final byte[] CF_STATS = Bytes.toBytes("stats");

    /**
     * Mapper: 提取小时作为 key
     */
    public static class HourMapper extends Mapper<LongWritable, Text, Text, Text> {

        private final Text outputKey = new Text();
        private final Text pvValue = new Text("1");
        private final Text uvValue = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            String line = value.toString().trim();
            if (line.isEmpty()) return;

            try {
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);
                if (event.getUserId() == null || event.getTimestamp() == null) return;

                String hourStr = HOUR_FORMAT.get().format(new Date(event.getTimestamp()));
                outputKey.set(hourStr);

                // PV 计数
                context.write(outputKey, pvValue);

                // UV 标记（用于去重）
                uvValue.set("USER:" + event.getUserId());
                context.write(outputKey, uvValue);

                context.getCounter("HourMapper", "PROCESSED").increment(1);
            } catch (Exception e) {
                context.getCounter("HourMapper", "PARSE_ERROR").increment(1);
            }
        }
    }

    /**
     * Reducer: 聚合同一小时的 PV 和 UV
     */
    public static class HourReducer extends Reducer<Text, Text, Text, Text> {

        private Connection hbaseConn;

        @Override
        protected void setup(Context context) throws IOException {
            String zkQuorum = context.getConfiguration().get("hbase.zookeeper.quorum", "localhost");
            Configuration hbaseConf = HBaseConfiguration.create();
            hbaseConf.set("hbase.zookeeper.quorum", zkQuorum);
            hbaseConf.set("hbase.zookeeper.property.clientPort", "2181");
            hbaseConn = ConnectionFactory.createConnection(hbaseConf);
            LOG.info("HourReducer HBase 连接成功");
        }

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {

            long pv = 0;
            java.util.Set<String> uniqueUsers = new java.util.HashSet<>();

            for (Text val : values) {
                String str = val.toString();
                if (str.startsWith("USER:")) {
                    uniqueUsers.add(str.substring(5));
                } else {
                    pv++;
                }
            }

            long uv = uniqueUsers.size();
            String hourStr = key.toString();

            // -------- 写入 HBase hourly_stats 表 --------
            Table table = null;
            try {
                table = hbaseConn.getTable(TableName.valueOf("hourly_stats"));
                Put put = new Put(Bytes.toBytes(hourStr));
                put.addColumn(CF_STATS, Bytes.toBytes("pv"), Bytes.toBytes(pv));
                put.addColumn(CF_STATS, Bytes.toBytes("uv"), Bytes.toBytes(uv));
                table.put(put);
            } finally {
                if (table != null) table.close();
            }

            // 输出到 HDFS
            context.write(key, new Text(pv + "\t" + uv));
        }

        @Override
        protected void cleanup(Context context) {
            if (hbaseConn != null) {
                try { hbaseConn.close(); } catch (IOException ignored) {}
            }
        }
    }

    public static Job createJob(String inputPath, String outputPath, String zkQuorum)
            throws IOException {

        Configuration conf = new Configuration();
        conf.set("hbase.zookeeper.quorum", zkQuorum);

        Job job = Job.getInstance(conf, "HourlyStats-" + System.currentTimeMillis());
        job.setJarByClass(HourlyStatsJob.class);
        job.setMapperClass(HourMapper.class);
        job.setReducerClass(HourReducer.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(Text.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);

        job.setInputFormatClass(TextInputFormat.class);
        FileInputFormat.addInputPath(job, new org.apache.hadoop.fs.Path(inputPath));
        FileInputFormat.setInputDirRecursive(job, true);
        org.apache.hadoop.mapreduce.lib.output.FileOutputFormat.setOutputPath(
                job, new org.apache.hadoop.fs.Path(outputPath));

        return job;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: HourlyStatsJob <inputPath> <outputPath> [zkQuorum]");
            System.exit(1);
        }
        Job job = createJob(args[0], args[1], args.length > 2 ? args[2] : "localhost");
        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
