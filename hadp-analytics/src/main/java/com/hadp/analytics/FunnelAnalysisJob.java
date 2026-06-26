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
import java.util.*;

/**
 * 漏斗转化分析 MapReduce 任务.
 * 默认漏斗: /home → /products → /cart → /checkout.
 * RowKey: date_step, 写入 HBase funnel_stats 表.
 */
public class FunnelAnalysisJob {

    private static final Logger LOG = LoggerFactory.getLogger(FunnelAnalysisJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private static final byte[] CF = Bytes.toBytes("stats");

    // Funnel steps ordered by depth (higher index = deeper)
    private static final String[] FUNNEL_STEPS = {"/home", "/products", "/cart", "/checkout"};

    public static class FunnelMapper extends Mapper<LongWritable, Text, Text, Text> {
        private final Text outKey = new Text();
        private final Text outVal = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            String line = value.toString().trim();
            if (line.isEmpty()) return;
            try {
                LogEvent event = OBJECT_MAPPER.readValue(line, LogEvent.class);
                if (event.getUserId() == null || event.getTimestamp() == null || event.getPageUrl() == null) return;

                String pageUrl = event.getPageUrl();
                int step = -1;
                for (int i = FUNNEL_STEPS.length - 1; i >= 0; i--) {
                    if (pageUrl.contains(FUNNEL_STEPS[i])) {
                        step = i;
                        break;
                    }
                }
                if (step < 0) return;

                String date = DATE_FORMAT.get().format(new Date(event.getTimestamp()));
                outKey.set(event.getUserId());
                outVal.set(step + "|" + date);
                context.write(outKey, outVal);
                context.getCounter("Funnel", "EVENTS").increment(1);

            } catch (Exception ignored) {
                context.getCounter("Funnel", "PARSE_ERR").increment(1);
            }
        }
    }

    public static class FunnelReducer extends Reducer<Text, Text, Text, Text> {
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
            Map<String, Integer> maxStepPerDay = new HashMap<>();
            for (Text v : values) {
                String[] parts = v.toString().split("\\|", 2);
                int step = Integer.parseInt(parts[0]);
                String date = parts.length > 1 ? parts[1] : "unknown";
                maxStepPerDay.merge(date, step, Math::max);
            }

            for (Map.Entry<String, Integer> entry : maxStepPerDay.entrySet()) {
                String date = entry.getKey();
                int maxStep = entry.getValue();
                for (int s = 0; s <= maxStep; s++) {
                    String rowKey = date + "_" + s;
                    try (Table table = hbaseConn.getTable(TableName.valueOf("funnel_stats"))) {
                        Put put = new Put(Bytes.toBytes(rowKey));
                        put.addColumn(CF, Bytes.toBytes("step"), Bytes.toBytes(s));
                        put.addColumn(CF, Bytes.toBytes("url"), Bytes.toBytes(FUNNEL_STEPS[s]));
                        put.addColumn(CF, Bytes.toBytes("c"), Bytes.toBytes(1L));
                        table.put(put);
                    }
                }
            }
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
        Job job = Job.getInstance(conf, "Funnel-" + System.currentTimeMillis());
        job.setJarByClass(FunnelAnalysisJob.class);
        job.setMapperClass(FunnelMapper.class);
        job.setReducerClass(FunnelReducer.class);
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

    public static String[] getSteps() {
        return FUNNEL_STEPS;
    }
}
