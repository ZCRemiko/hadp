package com.hadp.common.model;

/**
 * 小时级统计结果，存储在 HBase 表 hourly_stats。
 * RowKey: yyyyMMddHH，列族 stats，列 pv/uv/totalEvents。
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
