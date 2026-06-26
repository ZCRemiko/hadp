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
 * HBase 连接管理器（单例）。Connection 创建开销大，全应用共享一个实例。
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
     * 获取 HBase 连接（懒加载 + 双重检查锁，线程安全）。
     *
     * @param zkQuorum ZooKeeper 地址，例如 "zookeeper:2181"
     * @return HBase Connection 实例
     */
    public static Connection getConnection(String zkQuorum) {
        if (connection == null) {
            synchronized (LOCK) {
                if (connection == null) {
                    try {
                        Configuration config = HBaseConfiguration.create();

                        config.set("hbase.zookeeper.quorum", zkQuorum.replace(":2181", ""));
                        config.set("hbase.zookeeper.property.clientPort", "2181");

                        config.set("hbase.rpc.timeout", "30000");
                        config.set("hbase.client.retries.number", "3");
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
     * 获取 HBase 表对象（轻量级，调用者负责关闭）。
     * Table 非线程安全，不要在多线程间共享同一实例。
     *
     * @param tableName HBase 表名
     * @param zkQuorum  ZooKeeper 地址
     * @return Table 对象
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
