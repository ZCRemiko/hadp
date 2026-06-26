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
 * 页面统计 MapReduce 任务
 *
 * 统计每个页面每天被浏览的次数（PV）
 *
 * Mapper 输出: (日期|页面URL, "1")
 * Reducer 汇总: 对同一日期的同一页面，累加所有 "1" 即得到该页面当天的 PV
 *
 * HBase 写入表: page_stats
 * RowKey: date + "_" + pageUrl（方便按日期范围扫描）
 */
public class PageStatsJob {

    private static final Logger LOG = LoggerFactory.getLogger(PageStatsJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private static final byte[] CF_STATS = Bytes.toBytes("stats");

    /**
     * Mapper：提取 (日期, 页面URL) 作为 key
     */
    public static class PageMapper extends Mapper<LongWritable, Text, Text, Text> {

        private final Text outputKey = new Text();
        private final Text outputValue = new Text("1");

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            String line = value.toString().trim();
            if (line.isEmpty()) return;

            try {
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);
                if (event.getTimestamp() == null || event.getPageUrl() == null) return;

                // key 格式: "20240528|/products/detail?id=123"
                String dateStr = DATE_FORMAT.get().format(new Date(event.getTimestamp()));
                outputKey.set(dateStr + "|" + event.getPageUrl());

                context.write(outputKey, outputValue);
                context.getCounter("PageMapper", "PROCESSED").increment(1);

            } catch (Exception e) {
                context.getCounter("PageMapper", "PARSE_ERROR").increment(1);
            }
        }
    }

    /**
     * Reducer：累加同一页面的 PV，写入 HBase page_stats 表
     */
    public static class PageReducer extends Reducer<Text, Text, Text, Text> {

        private Connection hbaseConn;

        @Override
        protected void setup(Context context) throws IOException {
            String zkQuorum = context.getConfiguration().get("hbase.zookeeper.quorum", "localhost");
            Configuration hbaseConf = HBaseConfiguration.create();
            hbaseConf.set("hbase.zookeeper.quorum", zkQuorum);
            hbaseConf.set("hbase.zookeeper.property.clientPort", "2181");
            hbaseConn = ConnectionFactory.createConnection(hbaseConf);
            LOG.info("PageReducer HBase 连接成功");
        }

        @Override
        protected void reduce(Text key, Iterable<Text> values, Context context)
                throws IOException, InterruptedException {

            // 累加 PV
            long pv = 0;
            for (Text ignored : values) {
                pv++;
            }

            // key 格式: "20240528|/products/detail?id=123"
            String[] parts = key.toString().split("\\|", 2);
            String date = parts[0];
            String pageUrl = parts.length > 1 ? parts[1] : "";

            // -------- 写入 HBase --------
            Table table = null;
            try {
                table = hbaseConn.getTable(TableName.valueOf("page_stats"));
                // RowKey: date + "_" + pageUrl
                String rowKey = date + "_" + pageUrl;
                Put put = new Put(Bytes.toBytes(rowKey));
                put.addColumn(CF_STATS, Bytes.toBytes("pv"), Bytes.toBytes(pv));
                put.addColumn(CF_STATS, Bytes.toBytes("date"), Bytes.toBytes(date));
                put.addColumn(CF_STATS, Bytes.toBytes("url"), Bytes.toBytes(pageUrl));
                table.put(put);
            } finally {
                if (table != null) table.close();
            }

            // 同时输出到 HDFS
            context.write(key, new Text(String.valueOf(pv)));
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

        Job job = Job.getInstance(conf, "PageStats-" + System.currentTimeMillis());
        job.setJarByClass(PageStatsJob.class);
        job.setMapperClass(PageMapper.class);
        job.setReducerClass(PageReducer.class);

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
            System.err.println("用法: PageStatsJob <inputPath> <outputPath> [zkQuorum]");
            System.exit(1);
        }
        Job job = createJob(args[0], args[1], args.length > 2 ? args[2] : "localhost");
        System.exit(job.waitForCompletion(true) ? 0 : 1);
    }
}
