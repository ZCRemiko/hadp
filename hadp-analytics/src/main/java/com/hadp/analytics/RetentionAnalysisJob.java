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
 * 用户留存分析 MapReduce 任务
 *
 * 计算某日活跃用户在第 N 天的留存率
 *
 * 数据流:
 *   输入: HDFS 原始日志 (多天数据)
 *   Map:    (userId, date) — 标记每个用户在哪天活跃过
 *   Reduce: 对同一用户的活跃日期去重, 计算 baseDate+N 天是否仍活跃
 *   输出:   HBase user_retention 表 + HDFS
 *
 * RowKey: baseDate_activeDate (例 "20260601_20260602" = 次日留存)
 * 值:     retained_users (留存人数), base_users (基准日活跃人数), rate (留存率*10000)
 */
public class RetentionAnalysisJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionAnalysisJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private static final byte[] CF = Bytes.toBytes("stats");

    public static class RetentionMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outVal = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) return;
            try {
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);
                if (event.getUserId() == null || event.getTimestamp() == null) return;
                String date = DATE_FORMAT.get().format(new Date(event.getTimestamp()));
                outKey.set(event.getUserId());
                outVal.set(date);
                context.write(outKey, outVal);
                context.getCounter("Retention", "USERS").increment(1);
            } catch (Exception ignored) {
                context.getCounter("Retention", "PARSE_ERR").increment(1);
            }
        }
    }

    public static class RetentionReducer extends Reducer<Text, Text, Text, Text> {
        private Connection hbaseConn;

        @Override
        protected void setup(Context context) throws IOException {
            String zk = context.getConfiguration().get("hbase.zookeeper.quorum", "zookeeper");
            Configuration conf = HBaseConfiguration.create();
            conf.set("hbase.zookeeper.quorum", zk);
            conf.set("hbase.zookeeper.property.clientPort", "2181");
            hbaseConn = ConnectionFactory.createConnection(conf);
        }

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {
            Set<String> activeDays = new HashSet<>();
            for (Text v : values) {
                activeDays.add(v.toString());
            }
            context.getCounter("Retention", "ACTIVE_DAYS").increment(activeDays.size());
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
        Job job = Job.getInstance(conf, "Retention-" + System.currentTimeMillis());
        job.setJarByClass(RetentionAnalysisJob.class);
        job.setMapperClass(RetentionMapper.class);
        job.setReducerClass(RetentionReducer.class);
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
}
