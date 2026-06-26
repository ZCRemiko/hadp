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
 * 漏斗转化分析 MapReduce 任务
 *
 * 分析用户在一组连续步骤中的转化率
 * 默认漏斗: /home → /products → /cart → /checkout
 *
 * Mapper 输出: (userId, step|date) — 标记用户在哪天到达了哪个步骤
 * Reducer:  对同一用户, 排重最高步骤, 累计每个步骤的到达人数
 * 写入 HBase funnel_stats 表
 *
 * RowKey: date_step (例 "20260601_0" = 第0步, "20260601_1" = 第1步)
 * 值:     users (到达该步骤的用户数), rate (相对于上一步的转化率*10000)
 */
public class FunnelAnalysisJob {

    private static final Logger LOG = LoggerFactory.getLogger(FunnelAnalysisJob.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private static final byte[] CF = Bytes.toBytes("stats");

    // 漏斗步骤定义 (顺序重要, 越后面步骤号越大)
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
                // 检查是否匹配漏斗中的某个步骤
                int step = -1;
                for (int i = FUNNEL_STEPS.length - 1; i >= 0; i--) {
                    if (pageUrl.contains(FUNNEL_STEPS[i])) {
                        step = i;
                        break;
                    }
                }
                if (step < 0) return; // 不在漏斗中, 跳过

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
            // 找该用户到达的最高漏斗步骤 (每天取最高)
            Map<String, Integer> maxStepPerDay = new HashMap<>();
            for (Text v : values) {
                String[] parts = v.toString().split("\\|", 2);
                int step = Integer.parseInt(parts[0]);
                String date = parts.length > 1 ? parts[1] : "unknown";
                maxStepPerDay.merge(date, step, Math::max);
            }

            // 为该用户每天的最高步骤计数
            for (Map.Entry<String, Integer> entry : maxStepPerDay.entrySet()) {
                String date = entry.getKey();
                int maxStep = entry.getValue();
                // 该用户经过了 steps 0..maxStep 所有步骤
                for (int s = 0; s <= maxStep; s++) {
                    String rowKey = date + "_" + s;
                    try (Table table = hbaseConn.getTable(TableName.valueOf("funnel_stats"))) {
                        Put put = new Put(Bytes.toBytes(rowKey));
                        put.addColumn(CF, Bytes.toBytes("step"), Bytes.toBytes(s));
                        put.addColumn(CF, Bytes.toBytes("url"), Bytes.toBytes(FUNNEL_STEPS[s]));
                        put.addColumn(CF, Bytes.toBytes("c"), Bytes.toBytes(1L)); // 每条记录贡献1
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
