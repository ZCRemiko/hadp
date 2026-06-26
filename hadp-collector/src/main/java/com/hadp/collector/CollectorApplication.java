package com.hadp.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Collector 模块启动类，日志采集服务（默认端口 8080）。
 */
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
