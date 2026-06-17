package com.opcua.core;

import com.opcua.api.OpcUaDataListener;
import com.opcua.model.DeviceConfig;
import com.opcua.model.NodeConfig;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import com.opcua.model.SubscriptionGroupConfig;
import com.opcua.wrapper.MiloClientWrapper;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscriptionManager;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoredItemCreateRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.MonitoringParameters;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * OPC UA 订阅管理器。
 *
 * <p>根据 DeviceConfig 为每个设备创建和管理 Milo UaSubscription，
 * 将订阅回调数据转换为 OpcUaDeviceData 并通过 DataDispatchEngine 分发给上层监听器。</p>
 */
public class SubscriptionManager {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionManager.class);

    private final DataDispatchEngine dispatchEngine;

    /** deviceId → 该设备的所有 UaSubscription */
    private final ConcurrentHashMap<String, List<UaSubscription>> subscriptions = new ConcurrentHashMap<>();

    /**
     * 构造订阅管理器。
     *
     * @param dispatchEngine 数据分发引擎
     */
    public SubscriptionManager(DataDispatchEngine dispatchEngine) {
        this.dispatchEngine = dispatchEngine;
    }

    /**
     * 为某设备的某个 wrapper 创建所有订阅组。
     *
     * @param wrapper Milo 客户端封装
     * @param config  设备配置
     * @return 创建的 UaSubscription 列表
     * @throws RuntimeException 订阅创建失败时抛出
     */
    public List<UaSubscription> createSubscriptions(MiloClientWrapper wrapper, DeviceConfig config) {
        OpcUaClient client = wrapper.getClient();
        String deviceId = config.getDeviceId();
        List<SubscriptionGroupConfig> groupConfigs = config.getSubscriptions();

        if (groupConfigs == null || groupConfigs.isEmpty()) {
            logger.info("无订阅组配置: deviceId={}", deviceId);
            return Collections.emptyList();
        }

        logger.info("开始创建订阅: deviceId={}, 订阅组数={}", deviceId, groupConfigs.size());

        List<UaSubscription> result = new ArrayList<>();

        for (SubscriptionGroupConfig groupConfig : groupConfigs) {
            try {
                UaSubscription subscription = client.getSubscriptionManager()
                        .createSubscription(groupConfig.getSamplingInterval())
                        .get();

                List<MonitoredItemCreateRequest> requests = new ArrayList<>();
                for (NodeConfig nodeConfig : groupConfig.getNodes()) {
                    NodeId nodeId = NodeId.parse(nodeConfig.getNodeId());
                    ReadValueId readValueId = new ReadValueId(
                            nodeId,
                            AttributeId.Value.uid(),
                            null,
                            null
                    );
                    MonitoringParameters parameters = new MonitoringParameters(
                            subscription.nextClientHandle(),
                            (double) groupConfig.getSamplingInterval(),
                            null,
                            UInteger.valueOf(10),
                            true
                    );
                    requests.add(new MonitoredItemCreateRequest(
                            readValueId,
                            MonitoringMode.Reporting,
                            parameters
                    ));
                }

                if (!requests.isEmpty()) {
                    List<UaMonitoredItem> items = subscription.createMonitoredItems(
                            TimestampsToReturn.Both,
                            requests
                    ).get();

                    for (int i = 0; i < items.size(); i++) {
                        UaMonitoredItem item = items.get(i);
                        final NodeConfig nc = groupConfig.getNodes().get(i);
                        item.setValueConsumer((java.util.function.Consumer<DataValue>) dataValue ->
                                onDataReceived(deviceId, config.getProductId(),
                                        config.getEndpointUrl(), nc, dataValue));
                    }
                }

                result.add(subscription);
                logger.info("订阅组创建成功: deviceId={}, groupName={}, 节点数={}",
                        deviceId, groupConfig.getGroupName(), groupConfig.getNodes().size());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("订阅创建被中断: deviceId=" + deviceId, e);
            } catch (ExecutionException e) {
                throw new RuntimeException("订阅创建失败: deviceId=" + deviceId, e.getCause());
            } catch (Exception e) {
                throw new RuntimeException("订阅创建失败: deviceId=" + deviceId, e);
            }
        }

        subscriptions.put(deviceId, result);
        return result;
    }

    /**
     * 移除某设备的所有订阅记录。
     *
     * @param deviceId 设备标识
     */
    public void removeSubscriptions(String deviceId) {
        List<UaSubscription> removed = subscriptions.remove(deviceId);
        if (removed != null) {
            logger.info("移除订阅记录: deviceId={}, 订阅数={}", deviceId, removed.size());
        }
    }

    /**
     * 重连后重建所有订阅。
     * 先移除旧订阅记录，再创建新订阅。
     *
     * @param wrapper Milo 客户端封装
     * @param config  设备配置
     * @return 新建的 UaSubscription 列表
     */
    public List<UaSubscription> recreateAll(MiloClientWrapper wrapper, DeviceConfig config) {
        String deviceId = config.getDeviceId();
        logger.info("重建订阅: deviceId={}", deviceId);

        removeSubscriptions(deviceId);
        return createSubscriptions(wrapper, config);
    }

    // === 数据转换（package-private 用于测试） ===

    /**
     * 将 Milo DataValue 转换为 OpcUaDataPoint。
     *
     * @param nodeConfig 节点配置
     * @param dataValue  Milo 数据值
     * @return 聚合数据点
     */
    static OpcUaDataPoint convertToDataPoint(NodeConfig nodeConfig, DataValue dataValue) {
        StatusCode statusCode = dataValue.getStatusCode();

        // 质量评估
        Quality quality;
        if (statusCode.isGood()) {
            quality = Quality.Good;
        } else if (statusCode.isBad()) {
            quality = Quality.Bad;
        } else {
            quality = Quality.Uncertain;
        }

        // 状态码十六进制字符串
        String statusCodeHex = "0x" + Long.toHexString(statusCode.getValue());

        // 值提取
        Object value = null;
        if (dataValue.getValue() != null) {
            value = dataValue.getValue().getValue();
        }

        // 数据类型: 优先使用配置，否则从 Variant 推断
        String dataType = nodeConfig.getDataType();
        if (dataType == null || dataType.isEmpty()) {
            if (value != null) {
                dataType = value.getClass().getSimpleName();
            }
        }

        // displayName: 优先使用配置，否则用 nodeId
        String displayName = nodeConfig.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = nodeConfig.getNodeId();
        }

        // 时间戳
        Instant sourceTimestamp = null;
        DateTime sourceTime = dataValue.getSourceTime();
        if (sourceTime != null) {
            Date javaDate = sourceTime.getJavaDate();
            if (javaDate != null) {
                sourceTimestamp = javaDate.toInstant();
            }
        }

        Instant serverTimestamp = null;
        DateTime serverTime = dataValue.getServerTime();
        if (serverTime != null) {
            Date javaDate = serverTime.getJavaDate();
            if (javaDate != null) {
                serverTimestamp = javaDate.toInstant();
            }
        }

        return new OpcUaDataPoint(
                nodeConfig.getNodeId(),
                displayName,
                value,
                dataType,
                quality,
                nodeConfig.isQualityCheck(),
                statusCodeHex,
                sourceTimestamp,
                serverTimestamp
        );
    }

    // === 内部方法 ===

    /**
     * 单个数据点到达时的回调处理。
     * 将 DataValue 转换为 OpcUaDataPoint，封装为 OpcUaDeviceData，交由分发引擎处理。
     */
    private void onDataReceived(String deviceId, String productId, String endpointUrl,
                                NodeConfig nodeConfig, DataValue dataValue) {
        try {
            OpcUaDataPoint dataPoint = convertToDataPoint(nodeConfig, dataValue);

            OpcUaDeviceData.SourceInfo sourceInfo = new OpcUaDeviceData.SourceInfo(
                    productId, deviceId, endpointUrl
            );

            OpcUaDeviceData deviceData = new OpcUaDeviceData(
                    dataPoint.getSourceTimestamp() != null
                            ? dataPoint.getSourceTimestamp()
                            : Instant.now(),
                    sourceInfo,
                    List.of(dataPoint)
            );

            dispatchEngine.dispatch(deviceData);
        } catch (Exception e) {
            logger.error("数据回调处理异常: deviceId={}, nodeId={}, error={}",
                    deviceId, nodeConfig.getNodeId(), e.getMessage());
        }
    }
}
