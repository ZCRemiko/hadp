package com.hadp.api.controller;

import com.hadp.api.service.StatsService;
import com.hadp.common.model.DailyStats;
import com.hadp.common.model.HourlyStats;
import com.hadp.common.model.PageStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计查询 REST Controller
 *
 * 提供对 HBase 聚合数据的查询接口
 * 端口: 8081
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final Logger LOG = LoggerFactory.getLogger(StatsController.class);

    @Autowired
    private StatsService statsService;

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "HADP Query API");
        result.put("hbaseConnected", statsService.isHbaseConnected());
        return ResponseEntity.ok(result);
    }

    // ==================== 每日统计 ====================

    /**
     * 查询指定日期的每日统计（PV/UV）
     *
     * GET /api/stats/daily?date=2024-05-28
     *
     * 响应示例:
     * {
     *   "date": "20240528",
     *   "pv": 15000,
     *   "uv": 3200,
     *   "avgDuration": 45000,
     *   "totalEvents": 18000
     * }
     */
    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyStats(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        DailyStats stats = statsService.getDailyStats(date);

        if (stats == null) {
            return ResponseEntity.ok(buildEmpty("daily", date));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("date", stats.getDate());
        result.put("pv", stats.getPv());
        result.put("uv", stats.getUv());
        result.put("avgDuration", stats.getAvgDuration());
        result.put("totalEvents", stats.getTotalEvents());

        return ResponseEntity.ok(result);
    }

    /**
     * 查询日期范围的每日统计
     *
     * GET /api/stats/daily/range?start=2024-05-01&end=2024-05-28
     *
     * 响应示例:
     * {
     *   "items": [
     *     {"date":"20240501","pv":12000,"uv":3000},
     *     {"date":"20240502","pv":13500,"uv":3100},
     *     ...
     *   ]
     * }
     */
    @GetMapping("/daily/range")
    public ResponseEntity<Map<String, Object>> getDailyRange(
            @RequestParam("start") @DateTimeFormat(pattern = "yyyy-MM-dd") Date start,
            @RequestParam("end") @DateTimeFormat(pattern = "yyyy-MM-dd") Date end) {

        List<DailyStats> statsList = statsService.getDailyRange(start, end);

        Map<String, Object> result = new HashMap<>();
        result.put("start", start);
        result.put("end", end);
        result.put("count", statsList.size());
        result.put("items", statsList);

        return ResponseEntity.ok(result);
    }

    // ==================== 页面统计 ====================

    /**
     * 查询热门页面（按 PV 降序）
     *
     * GET /api/stats/pages/top?date=2024-05-28&limit=10
     */
    @GetMapping("/pages/top")
    public ResponseEntity<Map<String, Object>> getTopPages(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        List<PageStats> pages = statsService.getTopPages(date, limit);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("limit", limit);
        result.put("items", pages);

        return ResponseEntity.ok(result);
    }

    /**
     * 查询指定页面的统计信息
     *
     * GET /api/stats/pages/detail?date=2024-05-28&url=/home
     */
    @GetMapping("/pages/detail")
    public ResponseEntity<Map<String, Object>> getPageDetail(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date,
            @RequestParam("url") String pageUrl) {

        PageStats stats = statsService.getPageStats(date, pageUrl);

        if (stats == null) {
            return ResponseEntity.ok(buildEmpty("page", date));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("date", stats.getDate());
        result.put("pageUrl", stats.getPageUrl());
        result.put("pv", stats.getPv());
        result.put("avgDuration", stats.getAvgDuration());
        result.put("uniqueUsers", stats.getUniqueUsers());

        return ResponseEntity.ok(result);
    }

    // ==================== 小时级统计 ====================

    /**
     * 查询某一天各小时的 PV/UV 趋势
     *
     * GET /api/stats/hourly?date=2024-05-28
     *
     * 响应示例:
     * {
     *   "date": "20240528",
     *   "items": [
     *     {"dateHour":"2024052800","pv":500,"uv":200},
     *     {"dateHour":"2024052801","pv":300,"uv":150},
     *     ...
     *   ]
     * }
     */
    @GetMapping("/hourly")
    public ResponseEntity<Map<String, Object>> getHourlyStats(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        List<HourlyStats> hourlyList = statsService.getHourlyStats(date);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("count", hourlyList.size());
        result.put("items", hourlyList);

        return ResponseEntity.ok(result);
    }

    // ==================== 留存分析 ====================

    /**
     * 查询指定日期的用户留存概览
     *
     * GET /api/stats/retention?date=2026-06-01
     */
    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> getRetention(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        List<Map<String, Object>> data = statsService.getRetentionSummary(date);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("items", data);
        return ResponseEntity.ok(result);
    }

    // ==================== 漏斗分析 ====================

    /**
     * 查询指定日期的漏斗转化数据
     *
     * GET /api/stats/funnel?date=2026-06-01
     *
     * 响应:
     * [
     *   {"step":0, "url":"/home",     "users":1000, "rate":100},
     *   {"step":1, "url":"/products", "users":500,  "rate":50},
     *   {"step":2, "url":"/cart",     "users":200,  "rate":40},
     *   {"step":3, "url":"/checkout", "users":80,   "rate":40}
     * ]
     */
    @GetMapping("/funnel")
    public ResponseEntity<Map<String, Object>> getFunnel(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        List<Map<String, Object>> steps = statsService.getFunnelData(date);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("steps", steps);
        return ResponseEntity.ok(result);
    }

    // ==================== 管理接口 ====================

    /**
     * 手动初始化/重建 HBase 表
     *
     * POST /api/admin/init
     */
    @PostMapping("/admin/init")
    public ResponseEntity<Map<String, Object>> initTables() {
        try {
            statsService.initTables();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "HBase 表初始化成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "初始化失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    private Map<String, Object> buildEmpty(String type, Date date) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "未找到 " + type + " 统计数据");
        result.put("date", date);
        result.put("items", new java.util.ArrayList<>());
        return result;
    }
}
