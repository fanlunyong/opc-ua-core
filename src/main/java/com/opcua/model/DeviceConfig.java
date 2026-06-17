package com.opcua.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备配置。
 */
public class DeviceConfig {

    /** 设备标识 */
    private String id;

    /** 产品标识 */
    private String productId;

    /** OPC UA 服务端端点 URL */
    private String endpoint;

    /** 安全认证配置，默认 new SecurityConfig() */
    private SecurityConfig security = new SecurityConfig();

    /** 连接池配置 */
    private ConnectionPoolConfig connectionPool = new ConnectionPoolConfig();

    /** 订阅组列表 */
    private List<SubscriptionGroupConfig> subscriptions = new ArrayList<>();

    /** 轮询节点列表 */
    private List<PollingNodeConfig> polling = new ArrayList<>();

    public DeviceConfig() {
    }

    // --- Getters / Setters ---

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public SecurityConfig getSecurity() {
        return security;
    }

    public void setSecurity(SecurityConfig security) {
        this.security = security;
    }

    public ConnectionPoolConfig getConnectionPool() {
        return connectionPool;
    }

    public void setConnectionPool(ConnectionPoolConfig connectionPool) {
        this.connectionPool = connectionPool;
    }

    public List<SubscriptionGroupConfig> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(List<SubscriptionGroupConfig> subscriptions) {
        this.subscriptions = subscriptions;
    }

    public List<PollingNodeConfig> getPolling() {
        return polling;
    }

    public void setPolling(List<PollingNodeConfig> polling) {
        this.polling = polling;
    }

    /**
     * 连接池配置。
     */
    public static class ConnectionPoolConfig {

        /** 最大连接数，默认 3 */
        private int maxConnections = 3;

        /** 空闲超时（秒），默认 60 */
        private int idleTimeoutSeconds = 60;

        public ConnectionPoolConfig() {
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        public int getIdleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }
    }
}
