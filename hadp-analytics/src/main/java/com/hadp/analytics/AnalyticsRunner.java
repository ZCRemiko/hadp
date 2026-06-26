package com.hadp.analytics;

import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离线分析任务调度器, 依次执行 DailyStats / PageStats / HourlyStats / Retention / Funnel.
 */
public class AnalyticsRunner extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsRunner.class);

    @Override
    public int run(String[] args) throws Exception {
        String inputPath = null;
        String outputBase = null;
        String zkQuorum = "localhost";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-input":
                    inputPath = args[++i];
                    break;
                case "-output":
                    outputBase = args[++i];
                    break;
                case "-zk":
                    zkQuorum = args[++i];
                    break;
                default:
                    System.err.println("未知参数: " + args[i]);
                    printUsage();
                    return 1;
            }
        }

        if (inputPath == null || outputBase == null) {
            System.err.println("错误: 必须指定 -input 和 -output 参数");
            printUsage();
            return 1;
        }

        LOG.info("==========================================");
        LOG.info("  HADP 离线分析任务开始");
        LOG.info("  输入路径: {}", inputPath);
        LOG.info("  输出基准: {}", outputBase);
        LOG.info("  ZooKeeper: {}", zkQuorum);
        LOG.info("==========================================");

        LOG.info("[1/5] 开始每日统计任务...");
        boolean dailySuccess = runJob("DailyStats", inputPath, outputBase + "/daily", zkQuorum, "day");
        if (!dailySuccess) {
            LOG.error("[1/5] 每日统计任务失败！");
            return 1;
        }
        LOG.info("[1/5] 每日统计任务完成 ✓");

        LOG.info("[2/5] 开始页面统计任务...");
        boolean pageSuccess = runJob("PageStats", inputPath, outputBase + "/page", zkQuorum, "page");
        if (!pageSuccess) {
            LOG.error("[2/5] 页面统计任务失败！");
            return 1;
        }
        LOG.info("[2/5] 页面统计任务完成 ✓");

        LOG.info("[3/5] 开始小时级统计任务...");
        boolean hourlySuccess = runJob("HourlyStats", inputPath, outputBase + "/hourly", zkQuorum, "hour");
        if (!hourlySuccess) {
            LOG.error("[3/3] 小时级统计任务失败！");
            return 1;
        }
        LOG.info("[3/5] 小时级统计任务完成 ✓");

        LOG.info("[4/5] 开始用户留存分析任务...");
        boolean retentionSuccess = runJob("Retention", inputPath, outputBase + "/retention", zkQuorum, "retention");
        if (!retentionSuccess) {
            LOG.error("[4/5] 用户留存分析任务失败！");
        } else {
            LOG.info("[4/5] 用户留存分析任务完成 ✓");
        }

        LOG.info("[5/5] 开始漏斗转化分析任务...");
        boolean funnelSuccess = runJob("Funnel", inputPath, outputBase + "/funnel", zkQuorum, "funnel");
        if (!funnelSuccess) {
            LOG.error("[5/5] 漏斗转化分析任务失败！");
        } else {
            LOG.info("[5/5] 漏斗转化分析任务完成 ✓");
        }

        LOG.info("==========================================");
        LOG.info("  所有分析任务完成！");
        LOG.info("  结果已写入 HBase 和 HDFS: {}", outputBase);
        LOG.info("==========================================");

        return 0;
    }

    private boolean runJob(String jobName, String input, String output, String zk, String type) {
        try {
            org.apache.hadoop.mapreduce.Job job;

            switch (type) {
                case "day":
                    job = DailyStatsJob.createJob(input, output, zk);
                    break;
                case "page":
                    job = PageStatsJob.createJob(input, output, zk);
                    break;
                case "hour":
                    job = HourlyStatsJob.createJob(input, output, zk);
                    break;
                case "retention":
                    job = RetentionAnalysisJob.createJob(input, output, zk);
                    break;
                case "funnel":
                    job = FunnelAnalysisJob.createJob(input, output, zk);
                    break;
                default:
                    LOG.error("未知的统计类型: {}", type);
                    return false;
            }

            LOG.info("  {}任务配置: 输入={}, 输出={}", jobName, input, output);

            boolean success = job.waitForCompletion(true);

            if (success) {
                long mapCount = job.getCounters()
                        .findCounter("org.apache.hadoop.mapreduce.TaskCounter", "MAP_INPUT_RECORDS")
                        .getValue();
                long reduceCount = job.getCounters()
                        .findCounter("org.apache.hadoop.mapreduce.TaskCounter", "REDUCE_OUTPUT_RECORDS")
                        .getValue();
                LOG.info("  {}任务统计: 输入{}条, 输出{}条", jobName, mapCount, reduceCount);
            }

            return success;

        } catch (Exception e) {
            LOG.error("  {}任务异常: {}", jobName, e.getMessage(), e);
            return false;
        }
    }

    private void printUsage() {
        System.out.println("用法: hadoop jar hadp-analytics.jar " +
                "com.hadp.analytics.AnalyticsRunner -input <path> -output <path> [-zk <host>]");
        System.out.println("示例: hadoop jar hadp-analytics.jar " +
                "com.hadp.analytics.AnalyticsRunner -input /user/hadp/logs/2024/05/28 " +
                "-output /user/hadp/output/20240528 -zk zookeeper");
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new AnalyticsRunner(), args);
        System.exit(exitCode);
    }
}
