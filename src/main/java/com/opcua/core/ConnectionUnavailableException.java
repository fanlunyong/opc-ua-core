package com.opcua.core;

/**
 * 连接不可用异常 — 连接池耗尽或超时时抛出。
 */
public class ConnectionUnavailableException extends RuntimeException {
    private final String deviceId;

    public ConnectionUnavailableException(String deviceId) {
        super("连接不可用: deviceId=" + deviceId);
        this.deviceId = deviceId;
    }

    public ConnectionUnavailableException(String deviceId, String message) {
        super(message);
        this.deviceId = deviceId;
    }

    public String getDeviceId() {
        return deviceId;
    }
}
