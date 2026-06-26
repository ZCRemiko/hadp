package com.hadp.common.model;

/**
 * 每日统计结果，存储在 HBase 表 daily_stats。
 * RowKey: yyyyMMdd，列族 stats，列 pv/uv/avgDuration/totalEvents。
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
