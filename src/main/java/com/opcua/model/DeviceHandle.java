package com.opcua.model;

import com.opcua.wrapper.MiloClientWrapper;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * 设备运行时句柄 — 持有设备标识、配置、连接实例、订阅和任务引用。
 */
public class DeviceHandle {

    /** 设备标识 */
    private final String id;

    /** 设备配置 */
    private final DeviceConfig config;

    /** Milo 客户端封装列表（连接池） */
    private final List<MiloClientWrapper> wrappers;

    /**
     * 订阅列表。
     * TODO: change to Subscription after Task 4
     */
    private final List<Object> subscriptions;

    /** 轮询定时任务句柄列表 */
    private final List<ScheduledFuture<?>> pollingTasks;

    /** 设备运行时状态（volatile 保证可见性） */
    private volatile DeviceState state;

    public DeviceHandle(String id,
                        DeviceConfig config,
                        List<MiloClientWrapper> wrappers,
                        List<Object> subscriptions,
                        List<ScheduledFuture<?>> pollingTasks,
                        DeviceState state) {
        this.id = id;
        this.config = config;
        this.wrappers = wrappers;
        this.subscriptions = subscriptions;
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

    public List<MiloClientWrapper> getWrappers() {
        return wrappers;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getSubscriptions() {
        return (List<T>) (List<?>)
                subscriptions;
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
