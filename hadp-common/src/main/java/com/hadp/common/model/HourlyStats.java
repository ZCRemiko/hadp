package com.hadp.common.model;

/**
 * 小时级统计结果 - 存储在 HBase 表 "hourly_stats" 中
 *
 * 【HBase 表设计】
 * 表名: hourly_stats
 * RowKey: yyyyMMddHH (例如 "2024052816" 表示2024年5月28日16时)
 * 列族: stats
 *   列: stats:pv          - 该小时内的页面浏览量
 *   列: stats:uv          - 该小时内的独立访客数
 *   列: stats:totalEvents - 该小时内的事件总数
 *
 * 【为什么需要小时级统计？】
 * 1. 流量监控：发现流量突增/突降的时间段
 * 2. 容量规划：分析各时段负载，合理分配服务器资源
 * 3. 异常检测：凌晨3点突然出现大量请求 → 可能是攻击
 */
public class HourlyStats {

    /** 小时字符串 yyyyMMddHH，例如 "2024052816" */
    private String dateHour;

    /** 该小时 PV */
    private Long pv;

    /** 该小时 UV */
    private Long uv;

    /** 该小时事件总数 */
    private Long totalEvents;

    public HourlyStats() {
    }

    public HourlyStats(String dateHour, Long pv, Long uv) {
        this.dateHour = dateHour;
        this.pv = pv;
        this.uv = uv;
    }

    public String getDateHour() {
        return dateHour;
    }

    public void setDateHour(String dateHour) {
        this.dateHour = dateHour;
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

    public Long getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(Long totalEvents) {
        this.totalEvents = totalEvents;
    }

    @Override
    public String toString() {
        return "HourlyStats{" +
                "dateHour='" + dateHour + '\'' +
                ", pv=" + pv +
                ", uv=" + uv +
                ", totalEvents=" + totalEvents +
                '}';
    }
}
