package com.hadp.collector.controller;

import com.hadp.collector.service.LogService;
import com.hadp.common.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志采集 REST Controller
 *
 * 【什么是 Controller？】
 * Controller 是 Spring MVC 中的"控制器"，负责接收 HTTP 请求，
 * 调用业务逻辑层（Service），然后返回 HTTP 响应。
 *
 * 【注解说明】
 * @RestController = @Controller + @ResponseBody
 *   表示返回的是 JSON 数据，而不是 HTML 页面
 *
 * @RequestMapping("/api/logs")
 *   所有方法的路由都以 /api/logs 开头
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final Logger LOG = LoggerFactory.getLogger(LogController.class);

    @Autowired
    private LogService logService;

    /**
     * 健康检查接口
     *
     * 【请求示例】
     * GET http://localhost:8080/api/logs/health
     *
     * 【响应示例】
     * {"status": "UP", "hdfsConnected": true}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "HADP Collector");
        result.put("hdfsConnected", logService.isHdfsConnected());
        return ResponseEntity.ok(result);
    }

    /**
     * 上报单条日志事件
     *
     * 【请求示例】
     * POST http://localhost:8080/api/logs/event
     * Content-Type: application/json
     *
     * {
     *   "userId": "user_001",
     *   "eventType": "page_view",
     *   "pageUrl": "/home",
     *   "referrer": "https://www.google.com",
     *   "ipAddress": "192.168.1.1",
     *   "userAgent": "Mozilla/5.0...",
     *   "timestamp": 1716883200000,
     *   "duration": 5000
     * }
     *
     * @param event JSON 请求体，Spring 自动反序列化为 LogEvent 对象
     * @return 写入结果
     */
    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> reportEvent(@RequestBody LogEvent event) {
        LOG.info("收到日志事件: userId={}, eventType={}, pageUrl={}",
                event.getUserId(), event.getEventType(), event.getPageUrl());

        // -------- 参数校验 --------
        if (event.getUserId() == null || event.getUserId().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(buildError("userId 不能为空"));
        }
        if (event.getTimestamp() == null) {
            return ResponseEntity.badRequest()
                    .body(buildError("timestamp 不能为空"));
        }

        // -------- 写入 HDFS --------
        boolean success = logService.writeEvent(event);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("message", success ? "事件已写入HDFS" : "写入HDFS失败");
        result.put("userId", event.getUserId());
        result.put("timestamp", event.getTimestamp());

        if (success) {
            return ResponseEntity.ok(result);
        } else {
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 批量上报日志事件
     *
     * 【请求示例】
     * POST http://localhost:8080/api/logs/batch
     * Content-Type: application/json
     *
     * [
     *   {"userId":"user_001","eventType":"page_view","pageUrl":"/home","timestamp":1716883200000},
     *   {"userId":"user_002","eventType":"page_view","pageUrl":"/products","timestamp":1716883201000},
     *   {"userId":"user_001","eventType":"click","pageUrl":"/home","timestamp":1716883202000}
     * ]
     *
     * 【为什么需要批量接口？】
     * 1. 减少 HTTP 请求次数，降低网络开销
     * 2. 提高吞吐量，适合高并发场景
     * 3. HDFS 写入是"追加写"，批量写入可以减少文件碎片
     *
     * @param events JSON 数组，Spring 自动反序列化为 List<LogEvent>
     * @return 写入结果统计
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> reportBatch(@RequestBody List<LogEvent> events) {
        LOG.info("收到批量日志: {} 条", events.size());

        if (events == null || events.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(buildError("事件列表不能为空"));
        }

        int successCount = 0;
        int failCount = 0;

        for (LogEvent event : events) {
            if (event.getUserId() == null || event.getTimestamp() == null) {
                failCount++;
                continue;
            }
            if (logService.writeEvent(event)) {
                successCount++;
            } else {
                failCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", events.size());
        result.put("success", successCount);
        result.put("failed", failCount);
        result.put("message", String.format("成功 %d 条，失败 %d 条", successCount, failCount));

        return ResponseEntity.ok(result);
    }

    /**
     * 构建错误响应体
     */
    private Map<String, Object> buildError(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", message);
        return error;
    }
}
