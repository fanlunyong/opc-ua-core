package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

import java.util.Objects;

/**
 * 连接指纹 — 决定 SharedConnection 共享粒度。
 *
 * <pre>
 * Kafka:    bootstrap-servers + securityProtocol
 * MQTT:     brokerUrl + clientId
 * InfluxDB: url + org + token
 * HTTP:     type 单例
 * </pre>
 */
public final class ConnectionFingerprint {

    private final String type;
    private final String key;

    private ConnectionFingerprint(String type, String key) {
        this.type = type;
        this.key = key;
    }

    public static ConnectionFingerprint of(ForwardTarget t) {
        String type = t.getType();
        String key = switch (type == null ? "" : type) {
            case "kafka" -> safe(t.getBootstrapServers()) + "|" + safe(t.getSecurityProtocol());
            case "mqtt" -> safe(t.getBrokerUrl()) + "|" + safe(t.getClientId());
            case "influxdb" -> safe(t.getUrl()) + "|" + safe(t.getOrg()) + "|" + safe(t.getToken());
            case "http" -> "singleton";
            default -> throw new IllegalArgumentException("Unknown sender type: " + type);
        };
        return new ConnectionFingerprint(type, key);
    }

    public String getType() { return type; }
    public String getKey() { return key; }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectionFingerprint that)) return false;
        return Objects.equals(type, that.type) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() { return Objects.hash(type, key); }

    @Override
    public String toString() { return type + ":" + key; }
}
