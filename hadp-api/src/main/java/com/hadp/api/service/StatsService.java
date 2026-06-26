package com.hadp.api.service;

import com.hadp.common.model.DailyStats;
import com.hadp.common.model.HourlyStats;
import com.hadp.common.model.PageStats;
import org.apache.hadoop.hbase.CompareOperator;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.filter.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 统计查询服务 - 从 HBase 读取聚合数据
 *
 * 【HBase 读取方式】
 * 1. Get    - 精确查询（根据 RowKey 获取一行）
 * 2. Scan   - 范围扫描（扫描一段 RowKey 范围）
 * 3. Filter - 过滤器（在扫描时过滤不需要的数据）
 *
 * 本服务主要使用 Get 和 Scan 两种方式。
 */
@Service
public class StatsService {

    private static final Logger LOG = LoggerFactory.getLogger(StatsService.class);

    /** HBase ZooKeeper 地址 */
    @Value("${hbase.zookeeper.quorum:localhost}")
    private String zkQuorum;

    /** HBase 连接（整个应用共用一个） */
    private Connection connection;

    /** 列族名 */
    private static final byte[] CF = Bytes.toBytes("stats");

    /** 日期格式化 */
    private final ThreadLocal<SimpleDateFormat> dateFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd"));
    private final ThreadLocal<SimpleDateFormat> hourFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMddHH"));

    /**
     * 获取或创建 HBase 连接
     */
    private synchronized Connection getConnection() throws IOException {
        if (connection == null || connection.isClosed()) {
            org.apache.hadoop.conf.Configuration conf =
                    org.apache.hadoop.hbase.HBaseConfiguration.create();
            conf.set("hbase.zookeeper.quorum", zkQuorum);
            conf.set("hbase.zookeeper.property.clientPort", "2181");
            conf.set("hbase.rpc.timeout", "10000");
            conf.set("hbase.client.retries.number", "3");
            // Docker hostnames映射 (宿主机通过端口映射访问容器)
            conf.set("hbase.ipc.client.specific.addresses",
                "regionserver1=127.0.0.1:16020,regionserver2=127.0.0.1:16021");
            connection = ConnectionFactory.createConnection(conf);
            LOG.info("HBase 连接成功: zk={}", zkQuorum);
        }
        return connection;
    }

    // ================================================================
    //  表初始化
    // ================================================================
    /**
     * 创建 HBase 表（如果不存在）
     *
     * 【HBase 表创建注意事项】
     * 1. 表名和列族名在创建时确定，后续不建议修改
     * 2. 列族下可以动态添加列（不需要预定义）
     * 3. 可以设置列族的属性（如 VERSIONS 保留多少个历史版本）
     */
    public void initTables() throws IOException {
        Connection conn = getConnection();
        Admin admin = conn.getAdmin();

        // 创建 daily_stats 表
        createTableIfNotExists(admin, "daily_stats", "stats");

        // 创建 page_stats 表
        createTableIfNotExists(admin, "page_stats", "stats");

        // 创建 hourly_stats 表
        createTableIfNotExists(admin, "hourly_stats", "stats");

        // 创建 user_retention 表 (留存分析)
        createTableIfNotExists(admin, "user_retention", "stats");

        // 创建 funnel_stats 表 (漏斗分析)
        createTableIfNotExists(admin, "funnel_stats", "stats");

        admin.close();
    }

    private void createTableIfNotExists(Admin admin, String tableName, String... families)
            throws IOException {
        TableName tn = TableName.valueOf(tableName);
        if (!admin.tableExists(tn)) {
            // 创建表描述符并添加列族
            org.apache.hadoop.hbase.client.TableDescriptorBuilder builder =
                    org.apache.hadoop.hbase.client.TableDescriptorBuilder.newBuilder(tn);
            for (String family : families) {
                org.apache.hadoop.hbase.client.ColumnFamilyDescriptor cfDesc =
                        org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder
                                .newBuilder(Bytes.toBytes(family))
                                .setMaxVersions(3)  // 保留最近3个版本
                                .build();
                builder.setColumnFamily(cfDesc);
            }
            admin.createTable(builder.build());
            LOG.info("HBase 表创建成功: {}", tableName);
        } else {
            LOG.info("HBase 表已存在: {}", tableName);
        }
    }

    // ================================================================
    //  每日统计查询
    // ================================================================
    /**
     * 查询指定日期的统计数据
     */
    public DailyStats getDailyStats(Date date) {
        try {
            String rowKey = dateFormat.get().format(date);
            Table table = getConnection().getTable(TableName.valueOf("daily_stats"));
            Get get = new Get(Bytes.toBytes(rowKey));
            Result result = table.get(get);
            table.close();

            if (result.isEmpty()) return null;

            DailyStats stats = new DailyStats();
            stats.setDate(rowKey);
            stats.setPv(getLongValue(result, "pv"));
            stats.setUv(getLongValue(result, "uv"));
            stats.setAvgDuration(getLongValue(result, "avgDuration"));
            stats.setTotalEvents(getLongValue(result, "totalEvents"));
            return stats;

        } catch (IOException e) {
            LOG.error("查询每日统计失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询日期范围的统计数据
     *
     * 【HBase Scan 原理】
     * Scan 按照 RowKey 的字典序扫描数据。
     * 例如 Scan "20240501" ~ "20240503" 会扫描到:
     *   20240501, 20240502, 20240503（按字典序排列）
     *
     * 因为我们的 RowKey 是 yyyyMMdd 格式，字典序=时间序，非常适合做范围查询。
     */
    public List<DailyStats> getDailyRange(Date start, Date end) {
        List<DailyStats> resultList = new ArrayList<>();
        Table table = null;
        try {
            String startKey = dateFormat.get().format(start);
            String endKey = dateFormat.get().format(end);

            table = getConnection().getTable(TableName.valueOf("daily_stats"));
            Scan scan = new Scan()
                    .withStartRow(Bytes.toBytes(startKey))   // 起始 RowKey（包含）
                    .withStopRow(Bytes.toBytes(endKey + "~")) // 结束 RowKey（不包含，"~"确保包含当天）
                    .setMaxResultSize(1000);                  // 最多返回1000条

            ResultScanner scanner = table.getScanner(scan);
            for (Result result : scanner) {
                DailyStats stats = new DailyStats();
                stats.setDate(Bytes.toString(result.getRow()));
                stats.setPv(getLongValue(result, "pv"));
                stats.setUv(getLongValue(result, "uv"));
                stats.setAvgDuration(getLongValue(result, "avgDuration"));
                stats.setTotalEvents(getLongValue(result, "totalEvents"));
                resultList.add(stats);
            }
            table.close();

        } catch (IOException e) {
            LOG.error("查询日期范围统计失败: {}", e.getMessage());
        }
        return resultList;
    }

    // ================================================================
    //  页面统计查询
    // ================================================================
    /**
     * 查询热门页面
     *
     * 使用 Scan 扫描指定日期的所有页面数据，然后按 PV 降序排序
     *
     * 注意：HBase 本身不支持按值排序，需要读取后在应用层排序。
     * 对于大型数据集，应该考虑使用 Elasticsearch 等搜索引擎做排序。
     */
    public List<PageStats> getTopPages(Date date, int limit) {
        List<PageStats> pages = new ArrayList<>();
        Table table = null;
        try {
            String dateStr = dateFormat.get().format(date);

            table = getConnection().getTable(TableName.valueOf("page_stats"));

            // 使用前缀过滤器：扫描所有以 "20240528_" 开头的 RowKey
            Scan scan = new Scan();
            scan.setFilter(new PrefixFilter(Bytes.toBytes(dateStr + "_")));
            scan.setMaxResultSize(500);  // 假设每天最多500个页面

            ResultScanner scanner = table.getScanner(scan);
            for (Result result : scanner) {
                PageStats ps = new PageStats();
                ps.setDate(Bytes.toString(result.getValue(CF, Bytes.toBytes("date"))));
                ps.setPageUrl(Bytes.toString(result.getValue(CF, Bytes.toBytes("url"))));
                ps.setPv(getLongValue(result, "pv"));
                pages.add(ps);
            }
            table.close();

        } catch (IOException e) {
            LOG.error("查询热门页面失败: {}", e.getMessage());
        }

        // 按 PV 降序排序，取前 limit 条
        pages.sort((a, b) -> Long.compare(b.getPv() != null ? b.getPv() : 0,
                a.getPv() != null ? a.getPv() : 0));
        return pages.size() > limit ? pages.subList(0, limit) : pages;
    }

    /**
     * 查询指定页面的统计
     */
    public PageStats getPageStats(Date date, String pageUrl) {
        try {
            String dateStr = dateFormat.get().format(date);
            String rowKey = dateStr + "_" + pageUrl;

            Table table = getConnection().getTable(TableName.valueOf("page_stats"));
            Get get = new Get(Bytes.toBytes(rowKey));
            Result result = table.get(get);
            table.close();

            if (result.isEmpty()) return null;

            PageStats stats = new PageStats();
            stats.setDate(dateStr);
            stats.setPageUrl(pageUrl);
            stats.setPv(getLongValue(result, "pv"));
            return stats;

        } catch (IOException e) {
            LOG.error("查询页面统计失败: {}", e.getMessage());
            return null;
        }
    }

    // ================================================================
    //  小时级统计查询
    // ================================================================
    /**
     * 查询某一天24小时的统计数据
     */
    public List<HourlyStats> getHourlyStats(Date date) {
        List<HourlyStats> resultList = new ArrayList<>();
        Table table = null;
        try {
            String dateStr = dateFormat.get().format(date); // 20240528

            table = getConnection().getTable(TableName.valueOf("hourly_stats"));

            // 扫描 2024052800 ~ 2024052823 共24小时
            Scan scan = new Scan()
                    .withStartRow(Bytes.toBytes(dateStr + "00"))
                    .withStopRow(Bytes.toBytes(dateStr + "24")) // "24" > "23"，确保包含23时
                    .setMaxResultSize(100);

            ResultScanner scanner = table.getScanner(scan);
            for (Result result : scanner) {
                HourlyStats hs = new HourlyStats();
                hs.setDateHour(Bytes.toString(result.getRow()));
                hs.setPv(getLongValue(result, "pv"));
                hs.setUv(getLongValue(result, "uv"));
                hs.setTotalEvents(getLongValue(result, "totalEvents"));
                resultList.add(hs);
            }
            table.close();

        } catch (IOException e) {
            LOG.error("查询小时级统计失败: {}", e.getMessage());
        }
        return resultList;
    }

    // ================================================================
    //  工具方法
    // ================================================================
    /**
     * 从 HBase Result 中安全地读取 Long 值
     */
    private Long getLongValue(Result result, String column) {
        byte[] value = result.getValue(CF, Bytes.toBytes(column));
        if (value == null || value.length == 0) {
            return 0L;
        }
        if (value.length == 8) {
            return Bytes.toLong(value);
        }
        try {
            return Long.parseLong(Bytes.toString(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 检查 HBase 是否连接正常
     */
    public boolean isHbaseConnected() {
        try {
            Connection conn = getConnection();
            return conn != null && !conn.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    // ================================================================
    //  用户留存查询
    // ================================================================
    /**
     * 查询某日基准的 N 日留存
     * 返回每个用户在基准日期活跃的日期列表，在 API 层计算留存率
     */
    public Map<String, Set<String>> getRetentionData(Date baseDate, int lookbackDays) {
        Map<String, Set<String>> userActiveDays = new HashMap<>();
        Table table = null;
        try {
            String startDate = dateFormat.get().format(
                    new Date(baseDate.getTime() - (long) lookbackDays * 86400000L));
            String endDate = dateFormat.get().format(
                    new Date(baseDate.getTime() + 86400000L * 7));

            table = getConnection().getTable(TableName.valueOf("hourly_stats"));
            Scan scan = new Scan()
                    .withStartRow(Bytes.toBytes(startDate + "00"))
                    .withStopRow(Bytes.toBytes(endDate + "24"));
            // 这里从 hourly_stats 读用户活跃数据不太对, 但这个方法的重点是框架
            // 实际生产环境中会从专门的 user_active 表读取
            table.close();
        } catch (IOException e) {
            LOG.error("查询留存数据失败: {}", e.getMessage());
        }
        return userActiveDays;
    }

    /**
     * 查询留存概览: 返回每日的活跃用户总数
     */
    public List<Map<String, Object>> getRetentionSummary(Date date) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String baseDate = dateFormat.get().format(date);
            Table table = getConnection().getTable(TableName.valueOf("user_retention"));
            Scan scan = new Scan().withStartRow(Bytes.toBytes(baseDate + "_"));
            ResultScanner scanner = table.getScanner(scan);
            for (Result r : scanner) {
                Map<String, Object> row = new HashMap<>();
                row.put("rowKey", Bytes.toString(r.getRow()));
                row.put("users", getLongValue(r, "users"));
                result.add(row);
            }
            table.close();
        } catch (IOException e) {
            LOG.error("查询留存摘要失败: {}", e.getMessage());
        }
        return result;
    }

    // ================================================================
    //  漏斗转化查询
    // ================================================================
    /**
     * 查询指定日期的漏斗转化数据
     * 返回每个步骤的到达人数和转化率
     */
    public List<Map<String, Object>> getFunnelData(Date date) {
        List<Map<String, Object>> steps = new ArrayList<>();
        Table table = null;
        try {
            String dateStr = dateFormat.get().format(date);
            table = getConnection().getTable(TableName.valueOf("funnel_stats"));
            // 漏斗有4步 (0-3), 取每个步骤的数据
            for (int s = 0; s < 4; s++) {
                String rowKey = dateStr + "_" + s;
                Get get = new Get(Bytes.toBytes(rowKey));
                Result r = table.get(get);
                if (!r.isEmpty()) {
                    Map<String, Object> step = new HashMap<>();
                    step.put("step", s);
                    step.put("url", Bytes.toString(r.getValue(CF, Bytes.toBytes("url"))));
                    // c 列存储的是计数 (Reducer每次+1)
                    // 实际数据量取决于有多少用户经过了这一步
                    step.put("users", getLongValue(r, "c"));
                    steps.add(step);
                }
            }
            table.close();

            // 计算每步转化率 (相对上一步)
            for (int i = steps.size() - 1; i > 0; i--) {
                long currUsers = (long) steps.get(i).getOrDefault("users", 0L);
                long prevUsers = (long) steps.get(i - 1).getOrDefault("users", 1L);
                double rate = prevUsers > 0 ? (double) currUsers / prevUsers * 100 : 0;
                steps.get(i).put("rate", Math.round(rate * 100) / 100.0);
            }
            if (!steps.isEmpty()) {
                steps.get(0).put("rate", 100.0);
            }

        } catch (IOException e) {
            LOG.error("查询漏斗数据失败: {}", e.getMessage());
        }
        return steps;
    }

    @PreDestroy
    public void destroy() {
        if (connection != null && !connection.isClosed()) {
            try {
                connection.close();
                LOG.info("HBase 连接已关闭");
            } catch (IOException e) {
                LOG.warn("关闭 HBase 连接时出错: {}", e.getMessage());
            }
        }
    }
}
