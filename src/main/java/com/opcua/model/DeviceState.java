package com.opcua.model;

import java.time.Instant;

/**
 * 设备运行时状态。
 */
public class DeviceState {

    private final String deviceId;
    private final ConnectionState connectionState;
    private final int activeConnections;
    private final int reconnectCount;
    private final Instant lastConnectedAt;
    private final String lastError;

    /**
     * 全参构造函数。
     */
    public DeviceState(String deviceId,
                       ConnectionState connectionState,
                       int activeConnections,
                       int reconnectCount,
                       Instant lastConnectedAt,
                       String lastError) {
        this.deviceId = deviceId;
        this.connectionState = connectionState;
        this.activeConnections = activeConnections;
        this.reconnectCount = reconnectCount;
        this.lastConnectedAt = lastConnectedAt;
        this.lastError = lastError;
    }

    // --- Getters ---

    public String getDeviceId() {
        return deviceId;
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public int getActiveConnections() {
        return activeConnections;
    }

    public int getReconnectCount() {
        return reconnectCount;
    }

    public Instant getLastConnectedAt() {
        return lastConnectedAt;
    }

    public String getLastError() {
        return lastError;
    }

    @Override
    public String toString() {
        return "DeviceState{" +
                "deviceId='" + deviceId + '\'' +
                ", connectionState=" + connectionState +
                ", activeConnections=" + activeConnections +
                ", reconnectCount=" + reconnectCount +
                '}';
    }
}
