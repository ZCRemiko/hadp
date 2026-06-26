package com.hadp.common.util;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * HBase 连接管理器 - 单例模式
 *
 * 【为什么需要这个类？】
 * HBase 的 Connection 对象是"重量级"的——创建它需要与 ZooKeeper 建立会话，
 * 获取集群元数据，维护与 RegionServer 的连接池。
 * 如果每次操作都创建新连接，性能会非常差，而且浪费系统资源。
 * 因此使用"单例模式"：整个应用只创建一个 Connection 实例，所有操作共享它。
 *
 * 【HBase 连接流程】
 * 1. 应用创建 Configuration 对象，指定 ZooKeeper 地址
 * 2. ConnectionFactory 读取配置，连接到 ZooKeeper
 * 3. ZooKeeper 返回 HBase 集群的元数据（Master地址、RegionServer列表等）
 * 4. Connection 与各 RegionServer 建立 RPC 连接池
 * 5. 通过 Connection 获取 Table 对象，进行读写操作
 *
 * 【关键配置说明】
 * - hbase.zookeeper.quorum  : ZooKeeper 集群地址（HBase 用 ZK 做分布式协调）
 * - hbase.zookeeper.property.clientPort : ZooKeeper 客户端端口，默认 2181
 * - hbase.client.retries.number         : 操作失败重试次数，默认 15
 */
public class HBaseConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(HBaseConnectionManager.class);

    /** HBase 连接对象（整个JVM进程唯一一份） */
    private static volatile Connection connection;

    /** 锁对象，用于线程安全的懒加载 */
    private static final Object LOCK = new Object();

    // 工具类不允许实例化
    private HBaseConnectionManager() {
    }

    /**
     * 获取 HBase 连接（懒加载 + 双重检查锁，线程安全）
     *
     * @param zkQuorum ZooKeeper 地址，例如 "zookeeper:2181"
     *                  开发环境：localhost:2181
     *                  Docker环境：zookeeper:2181
     * @return HBase Connection 实例
     */
    public static Connection getConnection(String zkQuorum) {
        if (connection == null) {
            synchronized (LOCK) {
                if (connection == null) {
                    try {
                        Configuration config = HBaseConfiguration.create();

                        // ---------- 必填配置 ----------
                        // ZooKeeper 集群地址列表（多个用逗号分隔）
                        config.set("hbase.zookeeper.quorum", zkQuorum.replace(":2181", ""));
                        // ZooKeeper 客户端端口
                        config.set("hbase.zookeeper.property.clientPort", "2181");

                        // ---------- 可选优化配置 ----------
                        // RPC 超时时间（毫秒），默认 60 秒
                        config.set("hbase.rpc.timeout", "30000");
                        // 客户端操作重试次数，默认 15 次
                        config.set("hbase.client.retries.number", "3");
                        // 客户端扫描器缓存行数，增大可提高 Scan 性能
                        config.set("hbase.client.scanner.caching", "100");

                        LOG.info("正在连接 HBase，ZooKeeper地址: {}", zkQuorum);
                        connection = ConnectionFactory.createConnection(config);
                        LOG.info("HBase 连接创建成功");

                    } catch (IOException e) {
                        LOG.error("HBase 连接创建失败: {}", e.getMessage(), e);
                        throw new RuntimeException("无法连接到 HBase，请检查 ZooKeeper 是否启动", e);
                    }
                }
            }
        }
        return connection;
    }

    /**
     * 获取 HBase 表对象
     *
     * 【注意】Table 对象是轻量级的，可以每次操作时获取，用完后关闭。
     * 但 Table 不是线程安全的，不要在多线程间共享同一个 Table 实例。
     *
     * @param tableName HBase 表名
     * @param zkQuorum  ZooKeeper 地址
     * @return Table 对象（调用者负责关闭）
     */
    public static Table getTable(String tableName, String zkQuorum) {
        try {
            return getConnection(zkQuorum).getTable(TableName.valueOf(tableName));
        } catch (IOException e) {
            LOG.error("获取 HBase 表失败: {}, 原因: {}", tableName, e.getMessage(), e);
            throw new RuntimeException("获取 HBase 表失败: " + tableName, e);
        }
    }

    /**
     * 关闭 HBase 连接（通常在应用关闭时调用）
     */
    public static void closeConnection() {
        if (connection != null) {
            synchronized (LOCK) {
                if (connection != null) {
                    try {
                        connection.close();
                        LOG.info("HBase 连接已关闭");
                    } catch (IOException e) {
                        LOG.warn("关闭 HBase 连接时出错: {}", e.getMessage());
                    } finally {
                        connection = null;
                    }
                }
            }
        }
    }
}
