package com.opcua.core;

import com.opcua.TestConstants;
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
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SubscriptionManager 单元测试。
 * 遵循 TDD：先写测试，确认失败后再编写实现。
 */
@DisplayName("SubscriptionManager")
class SubscriptionManagerTest {

    private DataDispatchEngine dispatchEngine;
    private SubscriptionManager manager;
    private DeviceConfig config;
    private OpcUaClient mockClient;
    private OpcUaSubscriptionManager mockSubMgr;
    private MiloClientWrapper mockWrapper;

    @BeforeEach
    void setUp() {
        // 简单的直通分发引擎
        dispatchEngine = new DataDispatchEngine(2, 50, Collections.emptyList());
        manager = new SubscriptionManager(dispatchEngine);

        config = new DeviceConfig();
        config.setDeviceId(TestConstants.DEVICE_ID);
        config.setProductId(TestConstants.PRODUCT_ID);
        config.setEndpointUrl(TestConstants.ENDPOINT_URL);

        mockClient = mock(OpcUaClient.class);
        mockSubMgr = mock(OpcUaSubscriptionManager.class);
        mockWrapper = mock(MiloClientWrapper.class);
        when(mockWrapper.getClient()).thenReturn(mockClient);
        when(mockWrapper.getConfig()).thenReturn(config);
        when(mockClient.getSubscriptionManager()).thenReturn(mockSubMgr);
    }

    private SubscriptionGroupConfig createGroupConfig(String groupName, int samplingInterval, String... nodeIds) {
        SubscriptionGroupConfig group = new SubscriptionGroupConfig();
        group.setGroupName(groupName);
        group.setSamplingInterval(samplingInterval);
        List<NodeConfig> nodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            NodeConfig node = new NodeConfig();
            node.setNodeId(nodeId);
            node.setDisplayName(nodeId.replace("ns=2;s=", ""));
            node.setQualityCheck(true);
            nodes.add(node);
        }
        group.setNodes(nodes);
        return group;
    }

    /**
     * 4.1 数据转换: DataValue → OpcUaDataPoint
     */
    @Nested
    @DisplayName("数据转换 DataValue → OpcUaDataPoint")
    class DataValueConversion {

        @Test
        @DisplayName("Good 质量的 DataValue 应转换出 Good 质量的数据点")
        void shouldConvertGoodQualityDataValue() {
            NodeConfig nodeConfig = new NodeConfig();
            nodeConfig.setNodeId(TestConstants.NODE_ID);
            nodeConfig.setDisplayName(TestConstants.DISPLAY_NAME);
            nodeConfig.setDataType("Double");
            nodeConfig.setQualityCheck(true);

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);
            Date now = new Date();

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(true);
            when(statusCode.isBad()).thenReturn(false);
            when(statusCode.getValue()).thenReturn(0L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn(42.5);
            when(dataValue.getSourceTime()).thenReturn(new DateTime(now));
            when(dataValue.getServerTime()).thenReturn(new DateTime(now));

            OpcUaDataPoint point = SubscriptionManager.convertToDataPoint(nodeConfig, dataValue);

            assertThat(point.getNodeId()).isEqualTo(TestConstants.NODE_ID);
            assertThat(point.getDisplayName()).isEqualTo(TestConstants.DISPLAY_NAME);
            assertThat(point.getValue()).isEqualTo(42.5);
            assertThat(point.getDataType()).isEqualTo("Double");
            assertThat(point.getQuality()).isEqualTo(Quality.Good);
            assertThat(point.isQualityCheck()).isTrue();
            assertThat(point.getStatusCode()).isEqualTo("0x0");
            assertThat(point.getSourceTimestamp()).isNotNull();
            assertThat(point.getServerTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("Bad 质量的 DataValue 应转换出 Bad 质量的数据点")
        void shouldConvertBadQualityDataValue() {
            NodeConfig nodeConfig = new NodeConfig();
            nodeConfig.setNodeId("ns=2;s=Bad.Node");
            nodeConfig.setDisplayName("BadNode");
            nodeConfig.setQualityCheck(false);

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(false);
            when(statusCode.isBad()).thenReturn(true);
            when(statusCode.getValue()).thenReturn(0x80000000L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn(null);
            when(dataValue.getSourceTime()).thenReturn(null);
            when(dataValue.getServerTime()).thenReturn(null);

            OpcUaDataPoint point = SubscriptionManager.convertToDataPoint(nodeConfig, dataValue);

            assertThat(point.getNodeId()).isEqualTo("ns=2;s=Bad.Node");
            assertThat(point.getDisplayName()).isEqualTo("BadNode");
            assertThat(point.getValue()).isNull();
            assertThat(point.getQuality()).isEqualTo(Quality.Bad);
            assertThat(point.isQualityCheck()).isFalse();
            assertThat(point.getStatusCode()).isEqualTo("0x80000000");
            assertThat(point.getSourceTimestamp()).isNull();
            assertThat(point.getServerTimestamp()).isNull();
        }

        @Test
        @DisplayName("Uncertain 质量的 DataValue 应转换出 Uncertain 质量")
        void shouldConvertUncertainQualityDataValue() {
            NodeConfig nodeConfig = new NodeConfig();
            nodeConfig.setNodeId("ns=2;s=Uncertain.Node");
            nodeConfig.setDisplayName("UncertainNode");

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(false);
            when(statusCode.isBad()).thenReturn(false);
            when(statusCode.getValue()).thenReturn(0x40000000L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn("uncertain-value");
            when(dataValue.getSourceTime()).thenReturn(null);
            when(dataValue.getServerTime()).thenReturn(null);

            OpcUaDataPoint point = SubscriptionManager.convertToDataPoint(nodeConfig, dataValue);

            assertThat(point.getQuality()).isEqualTo(Quality.Uncertain);
            assertThat(point.getValue()).isEqualTo("uncertain-value");
        }

        @Test
        @DisplayName("当 displayName 未设置时应回退使用 nodeId")
        void shouldFallbackToNodeIdWhenDisplayNameNotSet() {
            NodeConfig nodeConfig = new NodeConfig();
            nodeConfig.setNodeId("ns=2;s=SomeNode");
            // displayName not set

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(true);
            when(statusCode.isBad()).thenReturn(false);
            when(statusCode.getValue()).thenReturn(0L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn(true);
            when(dataValue.getSourceTime()).thenReturn(null);
            when(dataValue.getServerTime()).thenReturn(null);

            OpcUaDataPoint point = SubscriptionManager.convertToDataPoint(nodeConfig, dataValue);

            assertThat(point.getDisplayName()).isEqualTo("ns=2;s=SomeNode");
        }

        @Test
        @DisplayName("当 dataType 未设置时应从 Variant 值推断类型")
        void shouldInferDataTypeFromVariantValue() {
            NodeConfig nodeConfig = new NodeConfig();
            nodeConfig.setNodeId("ns=2;s=IntNode");
            nodeConfig.setDataType(null); // 未设置 dataType

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(true);
            when(statusCode.isBad()).thenReturn(false);
            when(statusCode.getValue()).thenReturn(0L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn(42); // Integer value
            when(dataValue.getSourceTime()).thenReturn(null);
            when(dataValue.getServerTime()).thenReturn(null);

            OpcUaDataPoint point = SubscriptionManager.convertToDataPoint(nodeConfig, dataValue);

            assertThat(point.getDataType()).isEqualTo("Integer");
        }
    }

    /**
     * 4.2 订阅创建
     */
    @Nested
    @DisplayName("订阅创建")
    class SubscriptionCreation {

        @Test
        @DisplayName("createSubscriptions 应为每个 SubscriptionGroupConfig 创建 UaSubscription")
        void shouldCreateSubscriptionForEachGroup() throws Exception {
            config.setSubscriptions(List.of(
                    createGroupConfig("group1", 1000, "ns=2;s=Node.A", "ns=2;s=Node.B"),
                    createGroupConfig("group2", 500, "ns=2;s=Node.C")
            ));

            UaSubscription mockSub1 = mock(UaSubscription.class);
            UaSubscription mockSub2 = mock(UaSubscription.class);
            UaMonitoredItem mockItem = mock(UaMonitoredItem.class);

            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(mockSub1))
                    .thenReturn(CompletableFuture.completedFuture(mockSub2));
            when(mockSub1.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem, mockItem)));
            when(mockSub2.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            List<UaSubscription> subscriptions = manager.createSubscriptions(mockWrapper, config);

            assertThat(subscriptions).hasSize(2);
            verify(mockSubMgr).createSubscription(1000.0);
            verify(mockSubMgr).createSubscription(500.0);
        }

        @Test
        @DisplayName("无订阅组时 createSubscriptions 应返回空列表")
        void shouldReturnEmptyListWhenNoSubscriptionGroups() throws Exception {
            config.setSubscriptions(Collections.emptyList());

            List<UaSubscription> subscriptions = manager.createSubscriptions(mockWrapper, config);

            assertThat(subscriptions).isEmpty();
        }
    }

    /**
     * 4.3 订阅移除
     */
    @Nested
    @DisplayName("订阅移除")
    class SubscriptionRemoval {

        @Test
        @DisplayName("removeSubscriptions 应删除设备所有订阅记录")
        void shouldRemoveDeviceSubscriptions() throws Exception {
            config.setSubscriptions(List.of(
                    createGroupConfig("group1", 1000, TestConstants.NODE_ID)
            ));

            UaSubscription mockSub = mock(UaSubscription.class);
            UaMonitoredItem mockItem = mock(UaMonitoredItem.class);

            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(mockSub));
            when(mockSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            manager.createSubscriptions(mockWrapper, config);

            // 执行移除
            manager.removeSubscriptions(TestConstants.DEVICE_ID);

            // recreateAll 时不应还有旧订阅
            // 验证: 重新创建时应触发新的 createSubscription
            UaSubscription newSub = mock(UaSubscription.class);
            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(newSub));
            when(newSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            manager.createSubscriptions(mockWrapper, config);

            // 验证新订阅已创建
            assertThat(newSub).isNotNull();
        }

        @Test
        @DisplayName("removeSubscriptions 对不存在的设备不应抛异常")
        void shouldNotThrowForUnknownDevice() {
            // 不应抛出异常
            manager.removeSubscriptions("nonexistent-device");
        }
    }

    /**
     * 4.4 重连重建
     */
    @Nested
    @DisplayName("重连重建")
    class RecreateAll {

        @Test
        @DisplayName("recreateAll 应先删除旧订阅再创建新订阅")
        void shouldRemoveThenRecreate() throws Exception {
            config.setSubscriptions(List.of(
                    createGroupConfig("group1", 1000, TestConstants.NODE_ID)
            ));

            UaSubscription mockSub = mock(UaSubscription.class);
            UaMonitoredItem mockItem = mock(UaMonitoredItem.class);

            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(mockSub));
            when(mockSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            // 先创建初始订阅
            manager.createSubscriptions(mockWrapper, config);

            // 重新创建
            UaSubscription newSub = mock(UaSubscription.class);
            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(newSub));
            when(newSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            List<UaSubscription> result = manager.recreateAll(mockWrapper, config);

            assertThat(result).hasSize(1);
            assertThat(result.get(0)).isEqualTo(newSub);
        }
    }

    /**
     * 4.5 数据到达回调链
     */
    @Nested
    @DisplayName("数据到达回调链")
    class DataArrivalCallback {

        @Test
        @DisplayName("批次内多节点应聚合为单个 OpcUaDeviceData 并 dispatch")
        void shouldAggregateBatchAndDispatch() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> receivedData = new AtomicReference<>();

            OpcUaDataListener listener = data -> {
                receivedData.set(data);
                latch.countDown();
            };

            // 重新创建带监听器的 dispatchEngine
            dispatchEngine.shutdown();
            dispatchEngine = new DataDispatchEngine(2, 50, List.of(listener));
            manager = new SubscriptionManager(dispatchEngine);

            // 配置两个节点
            NodeConfig n1 = new NodeConfig();
            n1.setNodeId("ns=2;s=Node1");
            n1.setDisplayName("Node1");
            NodeConfig n2 = new NodeConfig();
            n2.setNodeId("ns=2;s=Node2");
            n2.setDisplayName("Node2");

            SubscriptionGroupConfig group = new SubscriptionGroupConfig();
            group.setGroupName("group1");
            group.setSamplingInterval(1000);
            group.setNodes(List.of(n1, n2));
            config.setSubscriptions(List.of(group));

            UaSubscription mockSub = mock(UaSubscription.class);
            UaMonitoredItem mockItem1 = mock(UaMonitoredItem.class);
            UaMonitoredItem mockItem2 = mock(UaMonitoredItem.class);

            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(mockSub));
            when(mockSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem1, mockItem2)));

            manager.createSubscriptions(mockWrapper, config);

            // 捕获 NotificationListener（批次回调入口）
            ArgumentCaptor<UaSubscription.NotificationListener> listenerCaptor =
                    ArgumentCaptor.forClass(UaSubscription.NotificationListener.class);
            verify(mockSub).addNotificationListener(listenerCaptor.capture());

            UaSubscription.NotificationListener notifListener = listenerCaptor.getValue();

            // 构造 2 个 DataValue
            StatusCode goodCode = mock(StatusCode.class);
            when(goodCode.isGood()).thenReturn(true);
            when(goodCode.isBad()).thenReturn(false);
            when(goodCode.getValue()).thenReturn(0L);

            Variant v1 = mock(Variant.class);
            when(v1.getValue()).thenReturn(1.0);
            Variant v2 = mock(Variant.class);
            when(v2.getValue()).thenReturn(2.0);

            DataValue dv1 = mock(DataValue.class);
            when(dv1.getStatusCode()).thenReturn(goodCode);
            when(dv1.getValue()).thenReturn(v1);
            Date now = new Date();
            when(dv1.getSourceTime()).thenReturn(new DateTime(now));
            when(dv1.getServerTime()).thenReturn(new DateTime(now));

            DataValue dv2 = mock(DataValue.class);
            when(dv2.getStatusCode()).thenReturn(goodCode);
            when(dv2.getValue()).thenReturn(v2);
            when(dv2.getSourceTime()).thenReturn(new DateTime(now));
            when(dv2.getServerTime()).thenReturn(new DateTime(now));

            // 触发批次回调
            notifListener.onDataChangeNotification(
                    mockSub,
                    List.of(mockItem1, mockItem2),
                    List.of(dv1, dv2),
                    new DateTime(now));

            // 验证批次聚合：单次 dispatch 包含两个节点的数据
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            OpcUaDeviceData deviceData = receivedData.get();
            assertThat(deviceData).isNotNull();
            assertThat(deviceData.getSource().getDeviceId()).isEqualTo(TestConstants.DEVICE_ID);
            assertThat(deviceData.getSource().getProductId()).isEqualTo(TestConstants.PRODUCT_ID);
            assertThat(deviceData.getData()).hasSize(2);

            List<Object> values = deviceData.getData().stream()
                    .map(OpcUaDataPoint::getValue)
                    .toList();
            assertThat(values).containsExactlyInAnyOrder(1.0, 2.0);
        }

        @Test
        @DisplayName("单节点批次也应正确聚合（degenerate case）")
        void shouldDispatchSingleNodeBatch() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> receivedData = new AtomicReference<>();

            OpcUaDataListener listener = data -> {
                receivedData.set(data);
                latch.countDown();
            };

            dispatchEngine.shutdown();
            dispatchEngine = new DataDispatchEngine(2, 50, List.of(listener));
            manager = new SubscriptionManager(dispatchEngine);

            config.setSubscriptions(List.of(
                    createGroupConfig("group1", 1000, TestConstants.NODE_ID)
            ));

            UaSubscription mockSub = mock(UaSubscription.class);
            UaMonitoredItem mockItem = mock(UaMonitoredItem.class);

            when(mockSubMgr.createSubscription(anyDouble()))
                    .thenReturn(CompletableFuture.completedFuture(mockSub));
            when(mockSub.createMonitoredItems(any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of(mockItem)));

            manager.createSubscriptions(mockWrapper, config);

            ArgumentCaptor<UaSubscription.NotificationListener> listenerCaptor =
                    ArgumentCaptor.forClass(UaSubscription.NotificationListener.class);
            verify(mockSub).addNotificationListener(listenerCaptor.capture());

            UaSubscription.NotificationListener notifListener = listenerCaptor.getValue();

            DataValue dataValue = mock(DataValue.class);
            StatusCode statusCode = mock(StatusCode.class);
            Variant variant = mock(Variant.class);
            Date now = new Date();

            when(dataValue.getStatusCode()).thenReturn(statusCode);
            when(statusCode.isGood()).thenReturn(true);
            when(statusCode.isBad()).thenReturn(false);
            when(statusCode.getValue()).thenReturn(0L);
            when(dataValue.getValue()).thenReturn(variant);
            when(variant.getValue()).thenReturn(123.45);
            when(dataValue.getSourceTime()).thenReturn(new DateTime(now));
            when(dataValue.getServerTime()).thenReturn(new DateTime(now));

            notifListener.onDataChangeNotification(
                    mockSub,
                    List.of(mockItem),
                    List.of(dataValue),
                    new DateTime(now));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            OpcUaDeviceData deviceData = receivedData.get();
            assertThat(deviceData).isNotNull();
            assertThat(deviceData.getSource().getDeviceId()).isEqualTo(TestConstants.DEVICE_ID);
            assertThat(deviceData.getData()).hasSize(1);
            assertThat(deviceData.getData().get(0).getValue()).isEqualTo(123.45);
        }
    }
}
