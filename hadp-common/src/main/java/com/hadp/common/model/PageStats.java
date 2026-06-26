package com.hadp.common.model;

/**
 * 页面统计结果 - 存储在 HBase 表 "page_stats" 中
 *
 * 【HBase 表设计】
 * 表名: page_stats
 * RowKey: yyyyMMdd + "_" + MD5(pageUrl前8位)
 *   例如: "20240528_a1b2c3d4"
 *   这样做的好处是：相同日期的数据物理上存储在一起，查询高效
 * 列族: stats
 *   列: stats:pv          - 该页面被浏览的次数
 *   列: stats:avgDuration - 该页面平均停留时长
 *   列: stats:uniqueUsers - 访问该页面的独立用户数
 */
public class PageStats {

    /** 日期字符串 yyyyMMdd */
    private String date;

    /** 页面URL */
    private String pageUrl;

    /** 该页面浏览量 */
    private Long pv;

    /** 该页面平均停留时长（毫秒） */
    private Long avgDuration;

    /** 访问该页面的独立用户数 */
    private Long uniqueUsers;

    public PageStats() {
    }

    public PageStats(String date, String pageUrl, Long pv) {
        this.date = date;
        this.pageUrl = pageUrl;
        this.pv = pv;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public Long getPv() {
        return pv;
    }

    public void setPv(Long pv) {
        this.pv = pv;
    }

    public Long getAvgDuration() {
        return avgDuration;
    }

    public void setAvgDuration(Long avgDuration) {
        this.avgDuration = avgDuration;
    }

    public Long getUniqueUsers() {
        return uniqueUsers;
    }

    public void setUniqueUsers(Long uniqueUsers) {
        this.uniqueUsers = uniqueUsers;
    }

    @Override
    public String toString() {
        return "PageStats{" +
                "date='" + date + '\'' +
                ", pageUrl='" + pageUrl + '\'' +
                ", pv=" + pv +
                '}';
    }
}
