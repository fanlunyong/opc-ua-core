package com.opcua.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备配置。
 */
public class DeviceConfig {

    /** 设备标识 */
    private String deviceId;

    /** 产品标识 */
    private String productId;

    /** OPC UA 服务端端点 URL */
    private String endpointUrl;

    /** 会话超时（秒），默认 600 */
    private int sessionTimeout = 600;

    /** 最大连接数，默认 3 */
    private int maxConnections = 3;

    /** 安全认证配置，默认 new SecurityConfig() */
    private SecurityConfig security = new SecurityConfig();

    /** 订阅组列表 */
    private List<SubscriptionGroupConfig> subscriptions = new ArrayList<>();

    public DeviceConfig() {
    }

    // --- Getters / Setters ---

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public int getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    public List<SubscriptionGroupConfig> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<SubscriptionGroupConfig> subscriptions) {
        this.subscriptions = subscriptions;
    }
}
