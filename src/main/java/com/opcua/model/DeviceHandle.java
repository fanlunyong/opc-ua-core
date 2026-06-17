package com.opcua.model;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 设备运行时句柄 — 持有设备配置、连接实例和任务引用。
 *
 * <p>注意：wrappers 字段当前使用 {@code List<Object>} 占位，
 * 待 Task 2 创建 MiloClientWrapper 后替换为具体类型。</p>
 */
public class DeviceHandle {

    /** 设备标识 */
    private final String id;

    /** 设备配置 */
    private final DeviceConfig config;

    /**
     * Milo 客户端封装列表。
     * TODO: change to MiloClientWrapper after Task 2
     */
    private final List<Object> wrappers;

    /** 轮询定时任务句柄列表 */
    private final List<ScheduledFuture<?>> pollingTasks;

    /** 设备运行时状态（volatile 保证可见性） */
    private volatile DeviceState state;

    public DeviceHandle(String id,
                        DeviceConfig config,
                        List<Object> wrappers,
                        List<ScheduledFuture<?>> pollingTasks,
                        DeviceState state) {
        this.id = id;
        this.config = config;
        this.wrappers = wrappers;
        this.pollingTasks = pollingTasks;
        this.state = state;
    }

    // --- Getters / Setters ---

    public String getId() {
        return id;
    }

    public DeviceConfig getConfig() {
        return config;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getWrappers() {
        return (List<T>) (List<?>) wrappers;
    }

    public List<ScheduledFuture<?>> getPollingTasks() {
        return pollingTasks;
    }

    public DeviceState getState() {
        return state;
    }

    public void setState(DeviceState state) {
        this.state = state;
    }

    @Override
    public String toString() {
        return "DeviceHandle{" +
                "id='" + id + '\'' +
                ", state=" + state +
                '}';
    }
}
