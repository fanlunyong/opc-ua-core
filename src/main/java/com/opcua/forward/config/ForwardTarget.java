package com.opcua.forward.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个转发目标配置。type 决定使用哪个 Sender，其余字段按 type 解释。
 */
public class ForwardTarget {

    private String type;            // kafka | influxdb | mqtt | http
    private boolean enabled = true;
    private int queueCapacity = 1000;
    private int workerThreads = 1;

    // Kafka
    private String bootstrapServers;
    private String securityProtocol;
    private String topic;

    // InfluxDB
    private String url;
    private String bucket;
    private String org;
    private String token;

    // MQTT
    private String brokerUrl;
    private String clientId;
    private int qos = 1;

    // HTTP
    private long timeoutMillis = 5000;

    /** 透传任意未识别属性（便于 sender 扩展） */
    private Map<String, String> properties = new HashMap<>();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String v) { this.bootstrapServers = v; }

    public String getSecurityProtocol() { return securityProtocol; }
    public void setSecurityProtocol(String v) { this.securityProtocol = v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }

    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }

    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }

    public String getOrg() { return org; }
    public void setOrg(String v) { this.org = v; }

    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String v) { this.brokerUrl = v; }

    public String getClientId() { return clientId; }
    public void setClientId(String v) { this.clientId = v; }

    public int getQos() { return qos; }
    public void setQos(int qos) { this.qos = qos; }

    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long v) { this.timeoutMillis = v; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> v) { this.properties = v; }
}
