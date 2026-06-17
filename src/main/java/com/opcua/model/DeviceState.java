package com.opcua.model;

import java.time.Instant;

/**
 * 设备运行时状态。
 */
public class DeviceState {

    private final String deviceId;
    private final ConnectionState state;
    private final Instant connectedSince;
    private final Instant lastDataReceived;
    private final String message;

    /**
     * 全参构造函数。
     */
    public DeviceState(String deviceId,
                       ConnectionState state,
                       Instant connectedSince,
                       Instant lastDataReceived,
                       String message) {
        this.deviceId = deviceId;
        this.state = state;
        this.connectedSince = connectedSince;
        this.lastDataReceived = lastDataReceived;
        this.message = message;
    }

    // --- Getters ---

    public String getDeviceId() {
        return deviceId;
    }

    public ConnectionState getState() {
        return state;
    }

    public Instant getConnectedSince() {
        return connectedSince;
    }

    public Instant getLastDataReceived() {
        return lastDataReceived;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "DeviceState{" +
                "deviceId='" + deviceId + '\'' +
                ", state=" + state +
                ", connectedSince=" + connectedSince +
                ", lastDataReceived=" + lastDataReceived +
                '}';
    }
}
