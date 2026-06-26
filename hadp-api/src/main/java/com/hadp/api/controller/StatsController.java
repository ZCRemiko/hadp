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
 * 统计查询 REST Controller.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private static final Logger LOG = LoggerFactory.getLogger(StatsController.class);

    @Autowired
    private StatsService statsService;

    /** Health check. */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "HADP Query API");
        result.put("hbaseConnected", statsService.isHbaseConnected());
        return ResponseEntity.ok(result);
    }

    /** GET /api/stats/daily?date=yyyy-MM-dd */
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

    /** GET /api/stats/daily/range?start=yyyy-MM-dd&end=yyyy-MM-dd */
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

    /** GET /api/stats/pages/top?date=yyyy-MM-dd&limit=N */
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

    /** GET /api/stats/pages/detail?date=yyyy-MM-dd&url=/path */
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

    /** GET /api/stats/hourly?date=yyyy-MM-dd */
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

    /** GET /api/stats/retention?date=yyyy-MM-dd */
    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> getRetention(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        List<Map<String, Object>> data = statsService.getRetentionSummary(date);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("items", data);
        return ResponseEntity.ok(result);
    }

    /** GET /api/stats/funnel?date=yyyy-MM-dd */
    @GetMapping("/funnel")
    public ResponseEntity<Map<String, Object>> getFunnel(
            @RequestParam("date") @DateTimeFormat(pattern = "yyyy-MM-dd") Date date) {

        List<Map<String, Object>> steps = statsService.getFunnelData(date);

        Map<String, Object> result = new HashMap<>();
        result.put("date", date);
        result.put("steps", steps);
        return ResponseEntity.ok(result);
    }

    /** POST /api/admin/init */
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
