package com.opcua.core;

import com.opcua.TestConstants;
import com.opcua.model.DeviceConfig;
import com.opcua.model.NodeConfig;
import com.opcua.model.Quality;
import com.opcua.model.SubscriptionGroupConfig;
import com.opcua.wrapper.MiloClientWrapper;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscriptionManager;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan A Task 3b — 验证 SubscriptionManager.onBatchReceived 调用
 * QualityEvaluator.logIfNeeded 接入 quality observability。
 */
class SubscriptionManagerQualityLoggingTest {

    private DataDispatchEngine dispatchEngine;
    private SubscriptionManager manager;
    private DeviceConfig config;
    private OpcUaClient mockClient;
    private OpcUaSubscriptionManager mockSubMgr;
    private MiloClientWrapper mockWrapper;

    @BeforeEach
    void setUp() {
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

    @AfterEach
    void tearDown() {
        dispatchEngine.shutdown();
    }

    @Test
    void onBatchReceived_invokesQualityEvaluatorLogIfNeededForEachDataPoint() throws Exception {
        // 配置两个节点，开启 qualityCheck
        NodeConfig n1 = new NodeConfig();
        n1.setNodeId("ns=2;s=NodeGood");
        n1.setDisplayName("NodeGood");
        n1.setQualityCheck(true);
        NodeConfig n2 = new NodeConfig();
        n2.setNodeId("ns=2;s=NodeBad");
        n2.setDisplayName("NodeBad");
        n2.setQualityCheck(true);

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

        ArgumentCaptor<UaSubscription.NotificationListener> listenerCaptor =
                ArgumentCaptor.forClass(UaSubscription.NotificationListener.class);
        verify(mockSub).addNotificationListener(listenerCaptor.capture());
        UaSubscription.NotificationListener notifListener = listenerCaptor.getValue();

        // 构造 Good + Bad 两个 DataValue
        StatusCode goodCode = mock(StatusCode.class);
        when(goodCode.isGood()).thenReturn(true);
        when(goodCode.isBad()).thenReturn(false);
        when(goodCode.getValue()).thenReturn(0L);

        StatusCode badCode = mock(StatusCode.class);
        when(badCode.isGood()).thenReturn(false);
        when(badCode.isBad()).thenReturn(true);
        when(badCode.getValue()).thenReturn(0x80000000L);

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
        when(dv2.getStatusCode()).thenReturn(badCode);
        when(dv2.getValue()).thenReturn(v2);
        when(dv2.getSourceTime()).thenReturn(new DateTime(now));
        when(dv2.getServerTime()).thenReturn(new DateTime(now));

        try (MockedStatic<QualityEvaluator> staticMock = mockStatic(QualityEvaluator.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            notifListener.onDataChangeNotification(
                    mockSub,
                    new ArrayList<>(List.of(mockItem1, mockItem2)),
                    new ArrayList<>(List.of(dv1, dv2)),
                    new DateTime(now));

            // 验证：每个数据点都调用了 logIfNeeded(quality, qualityCheck, nodeId)
            staticMock.verify(() -> QualityEvaluator.logIfNeeded(Quality.Good, true, "ns=2;s=NodeGood"));
            staticMock.verify(() -> QualityEvaluator.logIfNeeded(Quality.Bad, true, "ns=2;s=NodeBad"));
        }
    }
}
