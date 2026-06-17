package com.opcua.core;

/**
 * 连接不可用异常。
 *
 * <p>当连接池中所有连接均被占用且达到最大连接数时，
 * 新的获取请求在超时时间内未能获取到连接时抛出此异常。</p>
 */
public class ConnectionUnavailableException extends Exception {

    private final String deviceId;

    /**
     * @param deviceId 设备标识
     */
    public ConnectionUnavailableException(String deviceId) {
        super("连接不可用: deviceId=" + deviceId + ", 连接池已耗尽");
        this.deviceId = deviceId;
    }

    /**
     * 获取设备标识。
     */
    public String getDeviceId() {
        return deviceId;
    }
}
