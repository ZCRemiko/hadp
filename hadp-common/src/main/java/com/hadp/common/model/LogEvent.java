package com.hadp.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户行为日志事件，客户端上报的 JSON 记录。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogEvent {

    /** 用户唯一标识 */
    @JsonProperty("userId")
    private String userId;

    /** 事件类型：page_view / click / search / purchase */
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

    /** 事件时间戳（毫秒级 Unix 时间戳） */
    @JsonProperty("timestamp")
    private Long timestamp;

    /** 页面停留时长（毫秒），-1 表示未知 */
    @JsonProperty("duration")
    private Long duration;

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
