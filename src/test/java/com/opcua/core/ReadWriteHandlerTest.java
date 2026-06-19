package com.opcua.core;

import com.opcua.TestConstants;
import com.opcua.api.OpcUaDataListener;
import com.opcua.api.WriteResult;
import com.opcua.model.DeviceConfig;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.PollingNodeConfig;
import com.opcua.wrapper.MiloClientWrapper;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReadWriteHandler 单元测试。
 */
@DisplayName("ReadWriteHandler")
class ReadWriteHandlerTest {

    private DataDispatchEngine dispatchEngine;
    private ReadWriteHandler handler;
    private OpcUaClient mockClient;
    private MiloClientWrapper mockWrapper;
    private DeviceConfig config;

    @BeforeEach
    void setUp() {
        dispatchEngine = new DataDispatchEngine(2, 50, Collections.emptyList());
        handler = new ReadWriteHandler(dispatchEngine);

        mockClient = mock(OpcUaClient.class);
        mockWrapper = mock(MiloClientWrapper.class);
        when(mockWrapper.getClient()).thenReturn(mockClient);

        config = new DeviceConfig();
        config.setDeviceId(TestConstants.DEVICE_ID);
        config.setProductId(TestConstants.PRODUCT_ID);
        config.setEndpointUrl(TestConstants.ENDPOINT_URL);
        when(mockWrapper.getConfig()).thenReturn(config);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
        dispatchEngine.shutdown();
    }

    private DataValue mockGoodDataValue(Object value) {
        DataValue dv = mock(DataValue.class);
        StatusCode sc = mock(StatusCode.class);
        when(sc.isGood()).thenReturn(true);
        when(sc.isBad()).thenReturn(false);
        when(sc.getValue()).thenReturn(0L);
        Variant variant = mock(Variant.class);
        when(variant.getValue()).thenReturn(value);
        when(dv.getStatusCode()).thenReturn(sc);
        when(dv.getValue()).thenReturn(variant);
        Date now = new Date();
        when(dv.getSourceTime()).thenReturn(new DateTime(now));
        when(dv.getServerTime()).thenReturn(new DateTime(now));
        return dv;
    }

    @Nested
    @DisplayName("writeValue 写入操作")
    class WriteValueOperation {

        @Test
        @DisplayName("写入成功时应返回 success")
        void shouldReturnSuccessOnGoodStatusCode() {
            StatusCode goodCode = mock(StatusCode.class);
            when(goodCode.isGood()).thenReturn(true);
            when(mockClient.writeValue(any(NodeId.class), any(DataValue.class)))
                    .thenReturn(CompletableFuture.completedFuture(goodCode));

            WriteResult result = handler.writeValue(mockClient, "ns=2;s=Setpoint", 100.0);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getNodeId()).isEqualTo("ns=2;s=Setpoint");
            assertThat(result.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("写入返回 Bad StatusCode 时应返回 failure")
        void shouldReturnFailureOnBadStatusCode() {
            StatusCode badCode = mock(StatusCode.class);
            when(badCode.isGood()).thenReturn(false);
            when(badCode.toString()).thenReturn("Bad_AccessDenied");
            when(mockClient.writeValue(any(NodeId.class), any(DataValue.class)))
                    .thenReturn(CompletableFuture.completedFuture(badCode));

            WriteResult result = handler.writeValue(mockClient, "ns=2;s=Setpoint", 100.0);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getNodeId()).isEqualTo("ns=2;s=Setpoint");
            assertThat(result.getErrorMessage()).contains("Bad_AccessDenied");
        }

        @Test
        @DisplayName("写入抛异常时应返回 failure 并携带错误信息")
        void shouldReturnFailureOnException() {
            CompletableFuture<StatusCode> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("connection lost"));
            when(mockClient.writeValue(any(NodeId.class), any(DataValue.class)))
                    .thenReturn(failed);

            WriteResult result = handler.writeValue(mockClient, "ns=2;s=Setpoint", 100.0);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("connection lost");
        }

        @Test
        @DisplayName("无效 nodeId 应返回 failure")
        void shouldReturnFailureOnInvalidNodeId() {
            WriteResult result = handler.writeValue(mockClient, "not-a-valid-nodeid", 1.0);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("pollNode 轮询读取")
    class PollNodeOperation {

        @Test
        @DisplayName("读取成功后应通过 dispatchEngine 分发 OpcUaDeviceData")
        void shouldReadAndDispatch() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> received = new AtomicReference<>();

            OpcUaDataListener listener = data -> {
                received.set(data);
                latch.countDown();
            };

            dispatchEngine.shutdown();
            dispatchEngine = new DataDispatchEngine(2, 50, List.of(listener));
            handler.shutdown();
            handler = new ReadWriteHandler(dispatchEngine);

            DataValue dv = mockGoodDataValue(42.0);
            when(mockClient.readValue(anyDouble(), any(TimestampsToReturn.class), any(NodeId.class)))
                    .thenReturn(CompletableFuture.completedFuture(dv));

            PollingNodeConfig nodeConfig = new PollingNodeConfig();
            nodeConfig.setNodeId("ns=2;s=Counter");
            nodeConfig.setDisplayName("Counter");

            handler.pollNode(mockClient, TestConstants.DEVICE_ID, TestConstants.PRODUCT_ID,
                    TestConstants.ENDPOINT_URL, nodeConfig);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            OpcUaDeviceData deviceData = received.get();
            assertThat(deviceData).isNotNull();
            assertThat(deviceData.getSource().getDeviceId()).isEqualTo(TestConstants.DEVICE_ID);
            assertThat(deviceData.getData()).hasSize(1);
            assertThat(deviceData.getData().get(0).getValue()).isEqualTo(42.0);
            assertThat(deviceData.getData().get(0).getDisplayName()).isEqualTo("Counter");
        }

        @Test
        @DisplayName("读取异常时不应抛出，仅记录日志")
        void shouldTolerateReadException() {
            CompletableFuture<DataValue> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("timeout"));
            when(mockClient.readValue(anyDouble(), any(TimestampsToReturn.class), any(NodeId.class)))
                    .thenReturn(failed);

            PollingNodeConfig nodeConfig = new PollingNodeConfig();
            nodeConfig.setNodeId("ns=2;s=Counter");

            // 不应抛异常
            handler.pollNode(mockClient, TestConstants.DEVICE_ID, TestConstants.PRODUCT_ID,
                    TestConstants.ENDPOINT_URL, nodeConfig);
        }

        @Test
        @DisplayName("无效 nodeId 不应抛异常")
        void shouldTolerateInvalidNodeId() {
            PollingNodeConfig nodeConfig = new PollingNodeConfig();
            nodeConfig.setNodeId("not-valid");

            handler.pollNode(mockClient, TestConstants.DEVICE_ID, TestConstants.PRODUCT_ID,
                    TestConstants.ENDPOINT_URL, nodeConfig);
        }
    }

    @Nested
    @DisplayName("startPolling 调度生命周期")
    class StartPollingLifecycle {

        @Test
        @DisplayName("配置为空 polling 时应不抛异常")
        void shouldHandleEmptyPollingConfig() {
            config.setPolling(Collections.emptyList());
            handler.startPolling(mockWrapper, config);
            // 无任务被注册，stopPolling 应可正常调用
            handler.stopPolling(TestConstants.DEVICE_ID);
        }

        @Test
        @DisplayName("startPolling 后 stopPolling 应取消所有任务")
        void shouldCancelTasksOnStop() {
            PollingNodeConfig n1 = new PollingNodeConfig();
            n1.setNodeId("ns=2;s=N1");
            n1.setInterval(10000);
            PollingNodeConfig n2 = new PollingNodeConfig();
            n2.setNodeId("ns=2;s=N2");
            n2.setInterval(10000);
            config.setPolling(List.of(n1, n2));

            DataValue dv = mockGoodDataValue(1.0);
            when(mockClient.readValue(anyDouble(), any(TimestampsToReturn.class), any(NodeId.class)))
                    .thenReturn(CompletableFuture.completedFuture(dv));

            handler.startPolling(mockWrapper, config);
            handler.stopPolling(TestConstants.DEVICE_ID);
            // 不应抛异常；同 deviceId 第二次 stop 也安全
            handler.stopPolling(TestConstants.DEVICE_ID);
        }
    }
}
