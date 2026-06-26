package com.hadp.collector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hadp.common.model.LogEvent;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 日志写入服务，将事件以 JSON Lines 格式写入 HDFS。
 * 路径 /user/hadp/logs/yyyy/MM/dd/events_yyyyMMdd_HHmmss.json，按日期分区便于 MapReduce 按天读取。
 */
@Service
public class LogService {

    private static final Logger LOG = LoggerFactory.getLogger(LogService.class);

    /** HDFS NameNode 地址（从配置文件读取） */
    @Value("${hadoop.hdfs.uri:hdfs://localhost:9000}")
    private String hdfsUri;

    /** HDFS 上的日志根目录 */
    @Value("${hadoop.hdfs.log-path:/user/hadp/logs}")
    private String logBasePath;

    /** HDFS 文件系统客户端实例 */
    private FileSystem fileSystem;

    /** JSON 序列化工具（线程安全，可以共用） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 日期格式化器（线程不安全，但这里只在单次写入中使用） */
    private final ThreadLocal<SimpleDateFormat> dateFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy/MM/dd"));
    private final ThreadLocal<SimpleDateFormat> fileDateFormat =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyyMMdd_HHmmss"));

    /**
     * 初始化 HDFS 连接。
     */
    @PostConstruct
    public void init() {
        try {
            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", hdfsUri);
            conf.set("dfs.permissions.enabled", "false");
            conf.set("dfs.client.socket-timeout", "30000");
            System.setProperty("HADOOP_USER_NAME", "hadp");

            fileSystem = FileSystem.get(new URI(hdfsUri), conf, "hadp");
            LOG.info("HDFS 连接成功: {}", hdfsUri);

            Path basePath = new Path(logBasePath);
            if (!fileSystem.exists(basePath)) {
                fileSystem.mkdirs(basePath);
                LOG.info("已创建HDFS目录: {}", logBasePath);
            }
        } catch (Exception e) {
            LOG.error("HDFS 连接失败: {}", e.getMessage(), e);
            fileSystem = null;
        }
    }

    /**
     * 释放 HDFS 连接资源。
     */
    @PreDestroy
    public void destroy() {
        if (fileSystem != null) {
            try {
                fileSystem.close();
                LOG.info("HDFS 连接已关闭");
            } catch (IOException e) {
                LOG.warn("关闭 HDFS 连接时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 将单条日志事件写入 HDFS
     *
     * @param event 日志事件对象
     * @return true=写入成功, false=写入失败
     */
    public boolean writeEvent(LogEvent event) {
        if (fileSystem == null) {
            LOG.error("HDFS 未连接，无法写入事件");
            return false;
        }

        try {
            String jsonLine = objectMapper.writeValueAsString(event);

            long timestamp = event.getTimestamp() != null ? event.getTimestamp() : System.currentTimeMillis();
            Date date = new Date(timestamp);

            String datePart = dateFormat.get().format(date);
            String filePart = fileDateFormat.get().format(date);

            String filePath = String.format("%s/%s/events_%s.json",
                    logBasePath, datePart, filePart);

            Path hdfsPath = new Path(filePath);

            // HDFS 仅支持追加写，不支持随机修改
            FSDataOutputStream outputStream = null;
            int retries = 3;
            while (retries > 0) {
                try {
                    if (fileSystem.exists(hdfsPath)) {
                        outputStream = fileSystem.append(hdfsPath);
                    } else {
                        Path parentDir = hdfsPath.getParent();
                        if (!fileSystem.exists(parentDir)) {
                            fileSystem.mkdirs(parentDir);
                        }
                        outputStream = fileSystem.create(hdfsPath, true); // true=覆盖已存在文件
                    }
                    break;
                } catch (IOException e) {
                    retries--;
                    if (retries == 0) {
                        throw e;
                    }
                    LOG.warn("HDFS 写入失败，剩余重试次数: {}，原因: {}", retries, e.getMessage());
                    Thread.sleep(100);
                }
            }

            outputStream.writeBytes(jsonLine + "\n");
            outputStream.hflush();   // 刷新到 DataNode，不强制落盘
            outputStream.close();

            LOG.debug("日志已写入HDFS: {}", hdfsPath);
            return true;

        } catch (Exception e) {
            LOG.error("写入HDFS失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查 HDFS 是否连接正常
     */
    public boolean isHdfsConnected() {
        if (fileSystem == null) {
            return false;
        }
        try {
            fileSystem.listStatus(new Path("/"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
