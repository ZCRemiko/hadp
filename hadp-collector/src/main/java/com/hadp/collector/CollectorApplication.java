package com.hadp.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Collector 模块启动类 - 日志采集服务
 *
 * 【什么是 Spring Boot？】
 * Spring Boot 是一个简化 Java Web 开发的框架。你不需要自己配置 Tomcat 服务器、
 * 不需要写大量 XML 配置，只需要添加 `@SpringBootApplication` 注解，
 * Spring Boot 就会自动完成以下工作：
 * 1. 启动内嵌的 Tomcat Web 服务器
 * 2. 扫描并注册所有的 @Controller（REST API 端点）
 * 3. 扫描并注册所有的 @Service（业务逻辑组件）
 * 4. 自动配置 JSON 序列化、日志等基础设施
 *
 * 【运行方式】
 * - IDE 中直接运行本类的 main 方法
 * - 命令行: java -jar hadp-collector-1.0.0.jar
 * - Maven:  mvn spring-boot:run -pl hadp-collector
 *
 * 【默认端口】8080 （可在 application.yml 中修改）
 */
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
