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
 * 日志写入服务 - 将日志事件以 JSON Lines 格式写入 HDFS
 *
 * 【什么是 Service？】
 * Spring 中的 @Service 表示这是一个"业务逻辑层"组件。
 * Controller 负责接收请求，Service 负责执行实际的业务逻辑。
 * 分层的好处：职责清晰，便于单元测试，便于复用。
 *
 * 【HDFS 写入流程】
 * 1. 获取 FileSystem 实例（HDFS 的 Java API 入口）
 * 2. 根据事件时间戳确定文件路径
 * 3. 使用 append() 或 create() 方法写入数据
 * 4. 关闭输出流
 *
 * 【HDFS 文件路径设计】
 * /user/hadp/logs/yyyy/MM/dd/events_yyyyMMdd_HHmmss.json
 *
 * 为什么这样设计？
 * - 按日期分区：方便 MapReduce 按天读取数据
 * - 时间戳包含在文件名中：便于追溯
 * - JSON Lines 格式：每行一个 JSON 对象，MapReduce 按行处理非常高效
 *
 * 【HDFS 核心概念】
 * HDFS (Hadoop Distributed File System) 是一个分布式文件系统：
 * - NameNode：管理文件元数据（文件名、目录结构、块信息）
 * - DataNode：存储实际的数据块（Block，默认128MB一块）
 * - 数据会被复制到多个 DataNode（默认3副本），保证高可用
 * - 写入时，数据以"追加"方式写入，不支持随机修改
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
     * @PostConstruct: 在 Spring 容器初始化此 Bean 后自动调用
     * 用于初始化 HDFS 连接
     */
    @PostConstruct
    public void init() {
        try {
            Configuration conf = new Configuration();
            // 设置 HDFS 文件系统默认地址
            conf.set("fs.defaultFS", hdfsUri);
            // 禁用权限检查（开发环境）
            conf.set("dfs.permissions.enabled", "false");
            // 设置 HDFS 客户端超时
            conf.set("dfs.client.socket-timeout", "30000");
            // 设置用户名为 hadp（生产环境应该用真实用户）
            System.setProperty("HADOOP_USER_NAME", "hadp");

            fileSystem = FileSystem.get(new URI(hdfsUri), conf, "hadp");
            LOG.info("HDFS 连接成功: {}", hdfsUri);

            // 确保日志根目录存在
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
     * @PreDestroy: 在 Spring 容器销毁此 Bean 之前自动调用
     * 用于释放资源
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
            // -------- 第1步：将 LogEvent 对象序列化为 JSON 字符串 --------
            String jsonLine = objectMapper.writeValueAsString(event);

            // -------- 第2步：根据时间戳确定文件路径 --------
            // 例如: /user/hadp/logs/2024/05/28/events_20240528_160000.json
            long timestamp = event.getTimestamp() != null ? event.getTimestamp() : System.currentTimeMillis();
            Date date = new Date(timestamp);

            String datePart = dateFormat.get().format(date);      // 2024/05/28
            String filePart = fileDateFormat.get().format(date);   // 20240528_160000

            String filePath = String.format("%s/%s/events_%s.json",
                    logBasePath, datePart, filePart);

            Path hdfsPath = new Path(filePath);

            // -------- 第3步：写入 HDFS（带重试机制）--------
            // HDFS 只支持"追加写"（append），不支持随机修改
            // 如果文件不存在则创建，如果存在则追加
            FSDataOutputStream outputStream = null;
            int retries = 3;
            while (retries > 0) {
                try {
                    if (fileSystem.exists(hdfsPath)) {
                        // 文件已存在 -> 追加写入
                        outputStream = fileSystem.append(hdfsPath);
                    } else {
                        // 文件不存在 -> 创建新文件
                        // 确保父目录存在
                        Path parentDir = hdfsPath.getParent();
                        if (!fileSystem.exists(parentDir)) {
                            fileSystem.mkdirs(parentDir);
                        }
                        outputStream = fileSystem.create(hdfsPath, true); // true=如果存在则覆盖
                    }
                    break; // 成功，跳出重试循环
                } catch (IOException e) {
                    retries--;
                    if (retries == 0) {
                        throw e; // 重试耗尽，抛出异常
                    }
                    LOG.warn("HDFS 写入失败，剩余重试次数: {}，原因: {}", retries, e.getMessage());
                    Thread.sleep(100); // 等待100ms后重试
                }
            }

            // -------- 第4步：写入数据并关闭流 --------
            // 每行一个 JSON 对象，最后加换行符
            outputStream.writeBytes(jsonLine + "\n");
            outputStream.hflush();   // 强制刷新到 DataNode（不强制刷磁盘）
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
            // 尝试列出根目录，验证连接有效性
            fileSystem.listStatus(new Path("/"));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
