package com.opcua.core;

import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceHandle;
import com.opcua.model.DeviceState;
import com.opcua.wrapper.MiloClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 设备连接管理器。
 *
 * <p>维护设备注册表，管理每个设备的连接池（有界，大小 = maxConnections），
 * 提供负载均衡的连接获取和统一的启停控制。</p>
 */
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /** 设备注册表 */
    private final ConcurrentHashMap<String, DeviceHandle> devices = new ConcurrentHashMap<>();

    /** 轮询计数器（每设备独立） */
    private final ConcurrentHashMap<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    /** 连接信号量（每设备独立），许可数 = maxConnections */
    private final ConcurrentHashMap<String, java.util.concurrent.Semaphore> semaphores = new ConcurrentHashMap<>();

    /** 每个 wrapper 的最后使用时间（用于空闲驱逐） */
    private final java.util.Map<MiloClientWrapper, Long> lastUsedTimes = new java.util.concurrent.ConcurrentHashMap<>();

    /** 空闲连接驱逐调度器 */
    private final java.util.concurrent.ScheduledExecutorService evictionScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cm-eviction");
                t.setDaemon(true);
                return t;
            });

    public ConnectionManager() {
        evictionScheduler.scheduleWithFixedDelay(
                this::evictIdleConnections, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 批量启动设备连接。
     * 单个设备连接失败不影响其他设备启动。
     *
     * @param configs 设备配置列表
     */
    public void startAll(List<DeviceConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            logger.info("startAll: 配置列表为空，无设备需要启动");
            return;
        }

        for (DeviceConfig config : configs) {
            try {
                addDevice(config);
            } catch (Exception e) {
                logger.error("设备启动失败，跳过: deviceId={}, error={}",
                        config.getDeviceId(), e.getMessage());
            }
        }
    }

    /**
     * 注册设备并建立连接池。
     *
     * @param config 设备配置
     * @return 设备运行时句柄
     */
    public DeviceHandle addDevice(DeviceConfig config) {
        String deviceId = config.getDeviceId();
        logger.info("注册设备: deviceId={}, maxConnections={}", deviceId, config.getMaxConnections());

        // 创建连接池
        int poolSize = config.getMaxConnections();
        List<MiloClientWrapper> wrappers = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            wrappers.add(wrapper);
            // 异步连接，不等待
            wrapper.connect();
        }

        // 创建设备状态
        DeviceState state = aggregateState(deviceId, wrappers);

        // 创建句柄
        DeviceHandle handle = new DeviceHandle(
                deviceId,
                config,
                wrappers,
                new ArrayList<>(),
                new ArrayList<>(),
                state
        );

        // 注册
        devices.put(deviceId, handle);
        semaphores.put(deviceId, new java.util.concurrent.Semaphore(poolSize));
        roundRobinCounters.put(deviceId, new AtomicInteger(0));

        logger.info("设备注册完成: deviceId={}, 连接池大小={}", deviceId, poolSize);
        return handle;
    }

    /**
     * 移除设备，断开所有连接。
     *
     * @param deviceId 设备标识
     */
    public void removeDevice(String deviceId) {
        logger.info("移除设备: deviceId={}", deviceId);

        roundRobinCounters.remove(deviceId);
        java.util.concurrent.Semaphore semaphore = semaphores.remove(deviceId);
        DeviceHandle handle = devices.remove(deviceId);

        if (handle != null) {
            // 清理 lastUsedTimes
            handle.getWrappers().forEach(lastUsedTimes::remove);
            for (MiloClientWrapper wrapper : handle.getWrappers()) {
                try {
                    wrapper.disconnect();
                } catch (Exception e) {
                    logger.warn("断开连接时发生异常: deviceId={}, wrapper={}, error={}",
                            deviceId, wrapper, e.getMessage());
                }
            }
            logger.info("设备已移除: deviceId={}", deviceId);
        }
    }

    /**
     * 获取设备的一个连接（轮询负载均衡）。
     *
     * @param deviceId 设备标识
     * @return MiloClientWrapper 实例，设备不存在时返回 null
     */
    public MiloClientWrapper getClient(String deviceId) {
        DeviceHandle handle = devices.get(deviceId);
        if (handle == null) return null;
        if (handle.getWrappers().isEmpty()) return null;
        return selectWrapper(deviceId);
    }

    /**
     * 获取设备连接（带超时）。
     * @throws ConnectionUnavailableException 连接池耗尽或超时
     */
    public MiloClientWrapper acquireClient(String deviceId, long timeoutMs) {
        java.util.concurrent.Semaphore semaphore = semaphores.get(deviceId);
        if (semaphore == null) {
            throw new ConnectionUnavailableException(deviceId, "设备不存在: " + deviceId);
        }
        try {
            if (!semaphore.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new ConnectionUnavailableException(deviceId,
                        "连接池耗尽，超时 " + timeoutMs + "ms: deviceId=" + deviceId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionUnavailableException(deviceId, "获取连接被中断: " + deviceId);
        }
        MiloClientWrapper wrapper = selectWrapper(deviceId);
        lastUsedTimes.put(wrapper, System.currentTimeMillis());
        return wrapper;
    }

    /** 释放连接 */
    public void releaseClient(String deviceId) {
        java.util.concurrent.Semaphore semaphore = semaphores.get(deviceId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    // 提取轮询选择逻辑
    private MiloClientWrapper selectWrapper(String deviceId) {
        DeviceHandle handle = devices.get(deviceId);
        List<MiloClientWrapper> wrappers = handle.getWrappers();
        java.util.concurrent.atomic.AtomicInteger counter = roundRobinCounters.get(deviceId);
        return wrappers.get(counter.getAndIncrement() % wrappers.size());
    }

    /** 驱逐空闲连接 */
    public void evictIdleConnections() {
        long now = System.currentTimeMillis();
        for (DeviceHandle handle : devices.values()) {
            String deviceId = handle.getId();
            DeviceConfig config = handle.getConfig();
            int idleTimeoutSeconds = config.getIdleTimeoutSeconds();
            if (idleTimeoutSeconds <= 0) continue;
            long idleThreshold = idleTimeoutSeconds * 1000L;

            List<MiloClientWrapper> wrappers = handle.getWrappers();
            for (int i = 0; i < wrappers.size(); i++) {
                MiloClientWrapper wrapper = wrappers.get(i);
                Long lastUsed = lastUsedTimes.getOrDefault(wrapper, 0L);
                if (lastUsed > 0 && (now - lastUsed) > idleThreshold) {
                    logger.info("驱逐空闲连接: deviceId={}, idle={}ms", deviceId, now - lastUsed);
                    try { wrapper.disconnect(); } catch (Exception ignored) {}
                    // 替换为新 wrapper
                    MiloClientWrapper newWrapper = new MiloClientWrapper(config);
                    newWrapper.connect();
                    wrappers.set(i, newWrapper);
                    lastUsedTimes.put(newWrapper, now);
                    lastUsedTimes.remove(wrapper);
                }
            }
        }
    }

    /**
     * 获取设备运行状态。
     *
     * @param deviceId 设备标识
     * @return DeviceState，设备不存在时返回 null
     */
    public DeviceState getState(String deviceId) {
        DeviceHandle handle = devices.get(deviceId);
        if (handle == null) {
            return null;
        }

        return aggregateState(deviceId, handle.getWrappers());
    }

    /**
     * 获取所有设备的运行状态。
     *
     * @return deviceId → DeviceState 的不可变映射
     */
    public Map<String, DeviceState> getAllStates() {
        return Collections.unmodifiableMap(
                devices.entrySet().stream()
                        .map(entry -> {
                            String deviceId = entry.getKey();
                            DeviceHandle handle = entry.getValue();
                            return aggregateState(deviceId, handle.getWrappers());
                        })
                        .collect(Collectors.toMap(
                                DeviceState::getDeviceId,
                                s -> s
                        ))
        );
    }

    /**
     * 关闭所有设备连接并清空注册表。
     */
    public void shutdown() {
        logger.info("关闭所有设备连接，设备数={}", devices.size());

        for (Map.Entry<String, DeviceHandle> entry : devices.entrySet()) {
            String deviceId = entry.getKey();
            DeviceHandle handle = entry.getValue();
            for (MiloClientWrapper wrapper : handle.getWrappers()) {
                try {
                    wrapper.disconnect();
                } catch (Exception e) {
                    logger.warn("shutdown 时断开连接异常: deviceId={}, error={}",
                            deviceId, e.getMessage());
                }
            }
        }

        devices.clear();
        roundRobinCounters.clear();
        evictionScheduler.shutdown();
        semaphores.clear();
        lastUsedTimes.clear();
        logger.info("所有设备已关闭");
    }

    /**
     * 聚合连接池状态为设备级状态。
     *
     * <p>逻辑: 任一 wrapper 为 CONNECTED → CONNECTED；
     * 否则任一 wrapper 为 RECONNECTING → RECONNECTING；
     * 否则 → DISCONNECTED。</p>
     */
    private DeviceState aggregateState(String deviceId, List<MiloClientWrapper> wrappers) {
        boolean hasConnected = false;
        boolean hasReconnecting = false;

        for (MiloClientWrapper wrapper : wrappers) {
            ConnectionState s = wrapper.getState();
            if (s == ConnectionState.CONNECTED) {
                hasConnected = true;
                break;
            } else if (s == ConnectionState.RECONNECTING) {
                hasReconnecting = true;
            }
        }

        ConnectionState aggregated;
        if (hasConnected) {
            aggregated = ConnectionState.CONNECTED;
        } else if (hasReconnecting) {
            aggregated = ConnectionState.RECONNECTING;
        } else {
            aggregated = ConnectionState.DISCONNECTED;
        }

        return new DeviceState(deviceId, aggregated, null, null, null);
    }
}
