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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    /** deviceId → 创建订阅时使用的 OpcUaClient 引用，用于 removeSubscriptions 时调用 deleteSubscription */
    private final ConcurrentHashMap<String, OpcUaClient> deviceClients = new ConcurrentHashMap<>();

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

                    // 建立 UaMonitoredItem → NodeConfig 映射，供批次回调反查节点配置
                    Map<UaMonitoredItem, NodeConfig> itemToNode = new IdentityHashMap<>();
                    for (int i = 0; i < items.size(); i++) {
                        itemToNode.put(items.get(i), groupConfig.getNodes().get(i));
                    }

                    // 注册批次回调（每次 PublishResponse 内的所有 MonitoredItem 一次回调）
                    final String productId = config.getProductId();
                    final String endpointUrl = config.getEndpointUrl();
                    subscription.addNotificationListener(new UaSubscription.NotificationListener() {
                        @Override
                        public void onDataChangeNotification(UaSubscription sub,
                                                             List<UaMonitoredItem> monitoredItems,
                                                             List<DataValue> dataValues,
                                                             DateTime publishTime) {
                            onBatchReceived(deviceId, productId, endpointUrl,
                                    itemToNode, monitoredItems, dataValues);
                        }
                    });
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
        deviceClients.put(deviceId, client);
        return result;
    }

    /**
     * 移除某设备的所有订阅记录，并在服务端删除对应订阅以释放 NotificationListener。
     *
     * @param deviceId 设备标识
     */
    public void removeSubscriptions(String deviceId) {
        List<UaSubscription> removed = subscriptions.remove(deviceId);
        OpcUaClient client = deviceClients.remove(deviceId);
        if (removed == null) {
            return;
        }
        if (client != null) {
            for (UaSubscription subscription : removed) {
                try {
                    client.getSubscriptionManager()
                            .deleteSubscription(subscription.getSubscriptionId())
                            .get();
                } catch (Exception e) {
                    logger.warn("订阅服务端删除失败（忽略）: deviceId={}, subId={}, error={}",
                            deviceId, subscription.getSubscriptionId(), e.getMessage());
                }
            }
        }
        logger.info("移除订阅记录: deviceId={}, 订阅数={}", deviceId, removed.size());
    }

    /**
     * 移除所有设备的订阅，并在服务端删除以确保关停时无悬挂回调。
     */
    public void removeAll() {
        for (String deviceId : new ArrayList<>(subscriptions.keySet())) {
            removeSubscriptions(deviceId);
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
     * <p>复用 {@link QualityEvaluator} 与 {@link DataMapper} 的解析策略，
     * 与 {@link ReadWriteHandler#buildDataPoint} 对齐：null StatusCode 视为 Uncertain。</p>
     *
     * @param nodeConfig 节点配置
     * @param dataValue  Milo 数据值
     * @return 聚合数据点
     */
    static OpcUaDataPoint convertToDataPoint(NodeConfig nodeConfig, DataValue dataValue) {
        StatusCode statusCode = dataValue.getStatusCode();
        Quality quality = QualityEvaluator.evaluate(statusCode);

        // 状态码十六进制字符串（null 时为 null，与 ReadWriteHandler 对齐）
        String statusCodeHex = statusCode != null
                ? "0x" + Long.toHexString(statusCode.getValue())
                : null;

        // 值提取
        Object value = null;
        if (dataValue.getValue() != null) {
            value = dataValue.getValue().getValue();
        }

        String displayName = DataMapper.resolveDisplayName(nodeConfig.getDisplayName(),
                nodeConfig.getNodeId());
        String dataType = DataMapper.resolveDataType(nodeConfig.getDataType(), value);

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
     * 批次回调：将一次 PublishResponse 中同设备所有节点变更聚合为单个 OpcUaDeviceData，
     * 然后通过 dispatchEngine 一次性分发。
     */
    private void onBatchReceived(String deviceId, String productId, String endpointUrl,
                                 Map<UaMonitoredItem, NodeConfig> itemToNode,
                                 List<UaMonitoredItem> monitoredItems,
                                 List<DataValue> dataValues) {
        try {
            int n = Math.min(monitoredItems.size(), dataValues.size());
            List<OpcUaDataPoint> dataPoints = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                NodeConfig nodeConfig = itemToNode.get(monitoredItems.get(i));
                if (nodeConfig == null) {
                    // 未识别的 MonitoredItem，跳过
                    continue;
                }
                OpcUaDataPoint dataPoint = convertToDataPoint(nodeConfig, dataValues.get(i));
                QualityEvaluator.logIfNeeded(dataPoint.getQuality(), nodeConfig.isQualityCheck(),
                        nodeConfig.getNodeId());
                dataPoints.add(dataPoint);
            }
            if (dataPoints.isEmpty()) {
                return;
            }

            OpcUaDeviceData.SourceInfo sourceInfo = new OpcUaDeviceData.SourceInfo(
                    productId, deviceId, endpointUrl
            );

            // 时间戳取首个 dataPoint 的 sourceTimestamp，缺失时 fallback 当前
            Instant timestamp = dataPoints.get(0).getSourceTimestamp() != null
                    ? dataPoints.get(0).getSourceTimestamp()
                    : Instant.now();

            OpcUaDeviceData deviceData = new OpcUaDeviceData(timestamp, sourceInfo, dataPoints);
            dispatchEngine.dispatch(deviceData);
        } catch (Exception e) {
            logger.error("批次回调处理异常: deviceId={}, error={}", deviceId, e.getMessage());
        }
    }
}
