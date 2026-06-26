package com.hadp.common.model;

/**
 * 页面统计结果，存储在 HBase 表 page_stats。
 * RowKey: yyyyMMdd_MD5(pageUrl前8位)，相同日期数据物理相邻以提升查询效率。
 * 列族 stats，列 pv/avgDuration/uniqueUsers。
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
