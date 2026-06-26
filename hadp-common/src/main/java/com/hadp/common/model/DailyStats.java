package com.hadp.common.model;

/**
 * 每日统计结果 - 存储在 HBase 表 "daily_stats" 中
 *
 * 【HBase 表设计】
 * 表名: daily_stats
 * RowKey: yyyyMMdd (例如 "20240528")
 * 列族: stats
 *   列: stats:pv          - 页面浏览量 (Page View)
 *   列: stats:uv          - 独立访客数 (Unique Visitor)
 *   列: stats:avgDuration - 平均停留时长（毫秒）
 *   列: stats:totalEvents - 总事件数
 *
 * 【HBase 简介】
 * HBase 是运行在 HDFS 之上的分布式列式数据库，设计灵感来自 Google BigTable。
 * 核心概念：
 * - RowKey  : 行键，唯一标识一行数据，按字典序排序存储
 * - 列族    : 列的集合，一个表可以有多个列族（设计时确定，不建议频繁修改）
 * - 列限定符: 列族下的具体列名（可以动态新增）
 * - Cell    : 行键+列族+列限定符+时间戳 唯一确定一个单元格
 *
 * 【PV vs UV】
 * PV (Page View) : 页面被浏览的总次数，同一用户多次访问计为多次
 * UV (Unique Visitor) : 独立访客数，同一用户一天内多次访问只计一次
 */
public class DailyStats {

    /** 日期字符串 yyyyMMdd，例如 "20240528" */
    private String date;

    /** 日页面浏览量 PV */
    private Long pv;

    /** 日独立访客数 UV */
    private Long uv;

    /** 平均停留时长（毫秒） */
    private Long avgDuration;

    /** 总事件数（所有事件类型的总和） */
    private Long totalEvents;

    public DailyStats() {
    }

    public DailyStats(String date, Long pv, Long uv) {
        this.date = date;
        this.pv = pv;
        this.uv = uv;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getPv() {
        return pv;
    }

    public void setPv(Long pv) {
        this.pv = pv;
    }

    public Long getUv() {
        return uv;
    }

    public void setUv(Long uv) {
        this.uv = uv;
    }

    public Long getAvgDuration() {
        return avgDuration;
    }

    public void setAvgDuration(Long avgDuration) {
        this.avgDuration = avgDuration;
    }

    public Long getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(Long totalEvents) {
        this.totalEvents = totalEvents;
    }

    @Override
    public String toString() {
        return "DailyStats{" +
                "date='" + date + '\'' +
                ", pv=" + pv +
                ", uv=" + uv +
                ", avgDuration=" + avgDuration +
                ", totalEvents=" + totalEvents +
                '}';
    }
}
