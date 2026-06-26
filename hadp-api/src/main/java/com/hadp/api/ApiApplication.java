package com.hadp.api;

import com.hadp.api.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;

/**
 * API 模块启动类, 提供 HBase 分析结果查询服务.
 */
@SpringBootApplication
public class ApiApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ApiApplication.class);

    @Autowired
    private StatsService statsService;

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    /** 启动后自动初始化 HBase 表. */
    @PostConstruct
    public void init() {
        try {
            statsService.initTables();
            LOG.info("HBase 表初始化完成");
        } catch (Exception e) {
            LOG.warn("HBase 表初始化失败（可能 HBase 还未就绪）: {}", e.getMessage());
            LOG.warn("请确保 Docker 环境已启动，然后调用 POST /api/admin/init 手动初始化");
        }
    }
}
