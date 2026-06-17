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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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

    /** 每设备的连接信号量，限制并发占用数（大小 = maxConnections） */
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /**
     * 每个 wrapper 的最后使用时间（Unix 毫秒），用于空闲连接回收。
     * 包私有（测试可见）。
     */
    final ConcurrentHashMap<MiloClientWrapper, Long> lastUsedTimes = new ConcurrentHashMap<>();

    /** 空闲连接驱逐调度器（单线程守护），默认每 60 秒检查一次 */
    private final ScheduledExecutorService evictionScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "opcua-idle-eviction");
                t.setDaemon(true);
                return t;
            });

    {
        // 启动定期空闲驱逐任务，每 60 秒执行一次
        evictionScheduler.scheduleWithFixedDelay(
                this::evictIdleConnections, 60, 60, TimeUnit.SECONDS);
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
        long now = System.currentTimeMillis();
        for (int i = 0; i < poolSize; i++) {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            wrappers.add(wrapper);
            lastUsedTimes.put(wrapper, now);
            // 异步连接，不等待
            wrapper.connect();
        }

        // 创建 Semaphore，许可数 = maxConnections
        Semaphore semaphore = new Semaphore(poolSize, true);

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
        roundRobinCounters.put(deviceId, new AtomicInteger(0));
        semaphores.put(deviceId, semaphore);

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
        semaphores.remove(deviceId);
        DeviceHandle handle = devices.remove(deviceId);

        if (handle != null) {
            for (MiloClientWrapper wrapper : handle.getWrappers()) {
                lastUsedTimes.remove(wrapper);
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
        if (handle == null) {
            return null;
        }

        List<MiloClientWrapper> wrappers = handle.getWrappers();
        if (wrappers.isEmpty()) {
            return null;
        }

        return selectWrapper(deviceId, wrappers);
    }

    /**
     * 从连接池中分配可用连接（带 acquire 语义）。
     *
     * <p>先获取信号量许可（受 maxConnections 限制），然后轮询选取 wrapper。
     * 超过超时时间无法获取许可时抛出 {@link ConnectionUnavailableException}。</p>
     *
     * @param deviceId  设备标识
     * @param timeoutMs 等待超时（毫秒）
     * @return MiloClientWrapper 实例
     * @throws ConnectionUnavailableException 设备不存在或连接池耗尽
     */
    public MiloClientWrapper acquireClient(String deviceId, long timeoutMs)
            throws ConnectionUnavailableException {

        DeviceHandle handle = devices.get(deviceId);
        if (handle == null) {
            throw new ConnectionUnavailableException(deviceId);
        }

        Semaphore semaphore = semaphores.get(deviceId);
        if (semaphore == null) {
            throw new ConnectionUnavailableException(deviceId);
        }

        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionUnavailableException(deviceId);
        }

        if (!acquired) {
            throw new ConnectionUnavailableException(deviceId);
        }

        // 获取 wrapper（轮询）并更新最后使用时间
        MiloClientWrapper wrapper = selectWrapper(deviceId, handle.getWrappers());
        lastUsedTimes.put(wrapper, System.currentTimeMillis());

        return wrapper;
    }

    /**
     * 释放连接，归还信号量许可。
     *
     * @param deviceId 设备标识
     */
    public void releaseClient(String deviceId) {
        Semaphore semaphore = semaphores.get(deviceId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /**
     * 获取设备句柄（测试可见）。
     *
     * @param deviceId 设备标识
     * @return DeviceHandle，不存在时返回 null
     */
    DeviceHandle getHandle(String deviceId) {
        return devices.get(deviceId);
    }

    /**
     * 驱逐空闲连接：关闭空闲超过 idleTimeoutSeconds 的 wrapper，
     * 并用新 wrapper 替换。
     *
     * <p>空闲超时由 {@link DeviceConfig#getIdleTimeoutSeconds()} 配置，
     * 值 &lt;= 0 表示永不过期，跳过该设备。</p>
     */
    void evictIdleConnections() {
        long now = System.currentTimeMillis();

        for (Map.Entry<String, DeviceHandle> entry : devices.entrySet()) {
            String deviceId = entry.getKey();
            DeviceHandle handle = entry.getValue();
            DeviceConfig config = handle.getConfig();

            int idleTimeoutSeconds = config.getIdleTimeoutSeconds();
            if (idleTimeoutSeconds <= 0) {
                continue;
            }

            long idleTimeoutMs = idleTimeoutSeconds * 1000L;
            List<MiloClientWrapper> wrappers = handle.getWrappers();

            for (int i = 0; i < wrappers.size(); i++) {
                MiloClientWrapper wrapper = wrappers.get(i);
                Long lastUsed = lastUsedTimes.getOrDefault(wrapper, 0L);

                if (now - lastUsed > idleTimeoutMs) {
                    logger.info("驱逐空闲连接: deviceId={}, wrapper={}, idleMs={}",
                            deviceId, wrapper, now - lastUsed);

                    // 断开旧连接
                    try {
                        wrapper.disconnect();
                    } catch (Exception e) {
                        logger.warn("驱逐时断开连接异常: deviceId={}", deviceId, e);
                    }

                    lastUsedTimes.remove(wrapper);

                    // 创建替换 wrapper 并建立连接
                    MiloClientWrapper newWrapper = new MiloClientWrapper(config);
                    newWrapper.connect();
                    wrappers.set(i, newWrapper);
                    lastUsedTimes.put(newWrapper, now);

                    logger.info("空闲连接已替换: deviceId={}", deviceId);
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
        semaphores.clear();
        lastUsedTimes.clear();

        // 关闭驱逐调度器
        evictionScheduler.shutdownNow();
        try {
            if (!evictionScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("驱逐调度器未能在 5 秒内终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.info("所有设备已关闭");
    }

    /**
     * 从连接池中按轮询策略选取一个 wrapper。
     */
    private MiloClientWrapper selectWrapper(String deviceId, List<MiloClientWrapper> wrappers) {
        AtomicInteger counter = roundRobinCounters.get(deviceId);
        if (counter == null) {
            return wrappers.get(0);
        }
        int idx = counter.getAndIncrement() % wrappers.size();
        return wrappers.get(idx);
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
