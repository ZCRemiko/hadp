package com.hadp.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户行为日志事件 - 核心数据模型
 *
 * 【什么是日志事件？】
 * 当用户在网站/App上进行操作时（浏览页面、点击按钮、搜索等），
 * 客户端会发送一条JSON格式的记录到后端，这就是"日志事件"。
 *
 * 【数据流向】
 * 客户端 HTTP POST --> Collector服务 --> 写入HDFS（原始日志文件）
 *                                         |
 *                                    MapReduce读取 --> 聚合计算 --> 写入HBase
 *                                                                    |
 *                                                               API查询返回
 *
 * 【JSON 格式示例】
 * {
 *   "userId": "user_001",
 *   "eventType": "page_view",
 *   "pageUrl": "/products/detail?id=123",
 *   "referrer": "https://www.baidu.com",
 *   "ipAddress": "192.168.1.100",
 *   "userAgent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
 *   "timestamp": 1716883200000,
 *   "duration": 5000
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogEvent {

    /** 用户唯一标识（用于计算UV - 独立访客数） */
    @JsonProperty("userId")
    private String userId;

    /**
     * 事件类型
     * page_view  - 页面浏览（主要分析对象）
     * click      - 点击事件
     * search     - 搜索事件
     * purchase   - 购买事件
     */
    @JsonProperty("eventType")
    private String eventType;

    /** 当前页面URL */
    @JsonProperty("pageUrl")
    private String pageUrl;

    /** 来源页面URL（用户从哪里点进来的） */
    @JsonProperty("referrer")
    private String referrer;

    /** 客户端IP地址 */
    @JsonProperty("ipAddress")
    private String ipAddress;

    /** 浏览器User-Agent字符串 */
    @JsonProperty("userAgent")
    private String userAgent;

    /**
     * 事件时间戳（毫秒级Unix时间戳）
     * 例如 1716883200000 表示 2024-05-28 16:00:00
     */
    @JsonProperty("timestamp")
    private Long timestamp;

    /** 页面停留时长（毫秒），-1 表示未知 */
    @JsonProperty("duration")
    private Long duration;

    // ==================== Lombok 会自动生成以下代码 ====================
    // 因为使用了 @Data 注解（安装Lombok插件后IDE可识别）
    // 如果你没有安装Lombok，下面的代码就是Lombok会自动生成的：
    //
    // public LogEvent() {}
    // public String getUserId() { return userId; }
    // public void setUserId(String userId) { this.userId = userId; }
    // ... (所有字段的 getter/setter)
    // public String toString() { ... }
    // public boolean equals(Object o) { ... }
    // public int hashCode() { ... }

    // 如果你没有安装 Lombok 插件，请取消下面所有注释：

    public LogEvent() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getReferrer() {
        return referrer;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Long getDuration() {
        return duration;
    }

    public void setDuration(Long duration) {
        this.duration = duration;
    }

    @Override
    public String toString() {
        return "LogEvent{" +
                "userId='" + userId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", pageUrl='" + pageUrl + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
