package com.opcua.model;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 设备运行时句柄 — 持有设备标识、连接实例、订阅和任务引用。
 *
 * <p>注意：wrappers 和 subscriptions 字段当前使用 {@code List<Object>} 占位，
 * 待 Task 2 创建具体类型后替换。</p>
 */
public class DeviceHandle {

    /** 设备标识 */
    private final String id;

    /**
     * Milo 客户端封装列表。
     * TODO: change to MiloClientWrapper after Task 2
     */
    private final List<Object> wrappers;

    /**
     * 订阅列表。
     * TODO: change to Subscription after Task 2
     */
    private final List<Object> subscriptions;

    /** 轮询定时任务句柄列表 */
    private final List<ScheduledFuture<?>> pollingTasks;

    /** 设备运行时状态（volatile 保证可见性） */
    private volatile DeviceState state;

    public DeviceHandle(String id,
                        List<Object> wrappers,
                        List<Object> subscriptions,
                        List<ScheduledFuture<?>> pollingTasks,
                        DeviceState state) {
        this.id = id;
        this.wrappers = wrappers;
        this.subscriptions = subscriptions;
        this.pollingTasks = pollingTasks;
        this.state = state;
    }

    // --- Getters / Setters ---

    public String getId() {
        return id;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getWrappers() {
        return (List<T>) (List<?>) wrappers;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getSubscriptions() {
        return (List<T>) (List<?>) subscriptions;
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
