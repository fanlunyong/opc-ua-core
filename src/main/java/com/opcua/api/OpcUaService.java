package com.opcua.api;

import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceState;
import com.opcua.wrapper.MiloClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * OPC UA 统一对外 API 入口。
 *
 * <p>组合 {@link ConnectionManager}、{@link DataDispatchEngine}、
 * {@link SubscriptionManager}、{@link ReadWriteHandler}，对上层提供
 * 启动、监听器注册、写入、状态查询、关闭等单一入口。</p>
 */
public class OpcUaService {

    private static final Logger logger = LoggerFactory.getLogger(OpcUaService.class);

    private final ConnectionManager connectionManager;
    private final DataDispatchEngine dispatchEngine;
    private final SubscriptionManager subscriptionManager;
    private final ReadWriteHandler readWriteHandler;

    public OpcUaService(ConnectionManager connectionManager,
                        DataDispatchEngine dispatchEngine,
                        SubscriptionManager subscriptionManager,
                        ReadWriteHandler readWriteHandler) {
        this.connectionManager = connectionManager;
        this.dispatchEngine = dispatchEngine;
        this.subscriptionManager = subscriptionManager;
        this.readWriteHandler = readWriteHandler;
    }

    /**
     * 启动所有设备：建立连接、创建订阅、启动轮询。
     * 单设备启动失败不会中断其他设备。
     */
    public void start(List<DeviceConfig> configs) {
        connectionManager.startAll(configs);

        for (DeviceConfig config : configs) {
            String deviceId = config.getDeviceId();
            try {
                MiloClientWrapper wrapper = connectionManager.getClient(deviceId);
                if (wrapper == null) {
                    logger.warn("设备未连接，跳过订阅与轮询: deviceId={}", deviceId);
                    continue;
                }
                subscriptionManager.createSubscriptions(wrapper, config);
                readWriteHandler.startPolling(wrapper, config);
            } catch (Exception e) {
                logger.error("设备启动失败: deviceId={}, error={}", deviceId, e.getMessage());
            }
        }
    }

    /**
     * 注册数据监听器，将通过 DataDispatchEngine 多播接收所有设备数据。
     */
    public void registerListener(OpcUaDataListener listener) {
        dispatchEngine.addListener(listener);
    }

    /**
     * 写入指定设备节点的值。
     */
    public WriteResult writeValue(String deviceId, String nodeId, Object value) {
        MiloClientWrapper wrapper = connectionManager.getClient(deviceId);
        if (wrapper == null || wrapper.getClient() == null) {
            return WriteResult.failure(nodeId, "设备未连接: deviceId=" + deviceId);
        }
        return readWriteHandler.writeValue(wrapper.getClient(), nodeId, value);
    }

    /**
     * 获取所有设备的当前状态。
     */
    public Map<String, DeviceState> getDeviceStates() {
        return connectionManager.getAllStates();
    }

    /**
     * 暴露底层 ConnectionManager（高级用法）。
     */
    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * 关闭服务。顺序：
     * <ol>
     *   <li>SubscriptionManager.removeAll —— 取消所有订阅，避免新回调送入正在关闭的引擎</li>
     *   <li>ReadWriteHandler.shutdown —— 取消轮询并 awaitTermination</li>
     *   <li>ConnectionManager.shutdown —— 断开 Milo 客户端</li>
     *   <li>DataDispatchEngine.shutdown —— 最后关闭分发引擎，处理残留队列再退出</li>
     * </ol>
     * 此顺序避免关停期 Milo 通知线程将数据 dispatch 到已半关闭的引擎。
     */
    public void shutdown() {
        try {
            subscriptionManager.removeAll();
        } catch (Exception e) {
            logger.warn("SubscriptionManager.removeAll 关闭异常: {}", e.getMessage());
        }
        readWriteHandler.shutdown();
        connectionManager.shutdown();
        dispatchEngine.shutdown();
    }
}
