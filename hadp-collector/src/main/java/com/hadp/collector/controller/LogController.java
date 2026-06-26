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
 * 日志采集 REST Controller。
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private static final Logger LOG = LoggerFactory.getLogger(LogController.class);

    @Autowired
    private LogService logService;

    /**
     * 健康检查接口。
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
     * 上报单条日志事件。
     *
     * @param event JSON 请求体
     * @return 写入结果
     */
    @PostMapping("/event")
    public ResponseEntity<Map<String, Object>> reportEvent(@RequestBody LogEvent event) {
        LOG.info("收到日志事件: userId={}, eventType={}, pageUrl={}",
                event.getUserId(), event.getEventType(), event.getPageUrl());

        if (event.getUserId() == null || event.getUserId().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(buildError("userId 不能为空"));
        }
        if (event.getTimestamp() == null) {
            return ResponseEntity.badRequest()
                    .body(buildError("timestamp 不能为空"));
        }

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
     * 批量上报日志事件。
     *
     * @param events JSON 数组
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
