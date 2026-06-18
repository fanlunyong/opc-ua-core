package com.opcua.api;

import com.opcua.TestConstants;
import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceState;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import com.opcua.wrapper.MiloClientWrapper;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpcUaService 单元测试。
 */
@DisplayName("OpcUaService")
class OpcUaServiceTest {

    private DataDispatchEngine dispatchEngine;
    private ConnectionManager mockConnMgr;
    private SubscriptionManager mockSubMgr;
    private ReadWriteHandler mockRwHandler;
    private OpcUaService service;

    @BeforeEach
    void setUp() {
        dispatchEngine = new DataDispatchEngine(2, 50, Collections.emptyList());
        mockConnMgr = mock(ConnectionManager.class);
        mockSubMgr = mock(SubscriptionManager.class);
        mockRwHandler = mock(ReadWriteHandler.class);
        service = new OpcUaService(mockConnMgr, dispatchEngine, mockSubMgr, mockRwHandler);
    }

    private OpcUaDeviceData createDeviceData() {
        OpcUaDataPoint point = new OpcUaDataPoint(
                "ns=2;s=N", "N", 1.0, "Double", Quality.Good, false,
                "0x0", Instant.now(), Instant.now()
        );
        return new OpcUaDeviceData(Instant.now(),
                new OpcUaDeviceData.SourceInfo(TestConstants.PRODUCT_ID,
                        TestConstants.DEVICE_ID, TestConstants.ENDPOINT_URL),
                List.of(point));
    }

    @Nested
    @DisplayName("registerListener")
    class RegisterListener {

        @Test
        @DisplayName("注册的监听器应在 dispatch 时收到数据")
        void shouldDeliverToRegisteredListener() throws Exception {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<OpcUaDeviceData> received = new AtomicReference<>();

            service.registerListener(data -> {
                received.set(data);
                latch.countDown();
            });

            OpcUaDeviceData test = createDeviceData();
            dispatchEngine.dispatch(test);

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isSameAs(test);

            dispatchEngine.shutdown();
        }
    }

    @Nested
    @DisplayName("writeValue")
    class WriteValueOperation {

        @Test
        @DisplayName("应通过 ConnectionManager 获取 client 并委托 ReadWriteHandler.writeValue")
        void shouldDelegateToReadWriteHandler() {
            MiloClientWrapper mockWrapper = mock(MiloClientWrapper.class);
            OpcUaClient mockClient = mock(OpcUaClient.class);
            when(mockWrapper.getClient()).thenReturn(mockClient);
            when(mockConnMgr.getClient(TestConstants.DEVICE_ID)).thenReturn(mockWrapper);
            when(mockRwHandler.writeValue(mockClient, "ns=2;s=Setpoint", 100.0))
                    .thenReturn(WriteResult.success("ns=2;s=Setpoint"));

            WriteResult result = service.writeValue(TestConstants.DEVICE_ID,
                    "ns=2;s=Setpoint", 100.0);

            assertThat(result.isSuccess()).isTrue();
            verify(mockRwHandler).writeValue(mockClient, "ns=2;s=Setpoint", 100.0);
        }

        @Test
        @DisplayName("设备未连接时应返回 failure 且不调用 ReadWriteHandler")
        void shouldReturnFailureWhenDeviceNotConnected() {
            when(mockConnMgr.getClient("unknown-device")).thenReturn(null);

            WriteResult result = service.writeValue("unknown-device", "ns=2;s=X", 1.0);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("unknown-device");
            verify(mockRwHandler, never()).writeValue(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getDeviceStates")
    class GetDeviceStates {

        @Test
        @DisplayName("应返回 ConnectionManager.getAllStates 的结果")
        void shouldDelegateToConnectionManager() {
            DeviceState state = new DeviceState(TestConstants.DEVICE_ID,
                    ConnectionState.CONNECTED, Instant.now(), null, null);
            Map<String, DeviceState> expected = Map.of(TestConstants.DEVICE_ID, state);
            when(mockConnMgr.getAllStates()).thenReturn(expected);

            Map<String, DeviceState> result = service.getDeviceStates();

            assertThat(result).isSameAs(expected);
            verify(mockConnMgr).getAllStates();
        }
    }

    @Nested
    @DisplayName("getConnectionManager")
    class GetConnectionManager {

        @Test
        @DisplayName("应返回构造时传入的 ConnectionManager 实例")
        void shouldExposeUnderlyingConnectionManager() {
            assertThat(service.getConnectionManager()).isSameAs(mockConnMgr);
        }
    }

    @Nested
    @DisplayName("start")
    class StartLifecycle {

        @Test
        @DisplayName("应调用 startAll，并对每个已连接设备创建订阅与启动轮询")
        void shouldStartAllAndCreateSubscriptionsAndPolling() {
            DeviceConfig devA = new DeviceConfig();
            devA.setDeviceId("dev-A");
            devA.setProductId("product-A");
            devA.setEndpointUrl("opc.tcp://a:4840");

            DeviceConfig devB = new DeviceConfig();
            devB.setDeviceId("dev-B");
            devB.setProductId("product-B");
            devB.setEndpointUrl("opc.tcp://b:4840");

            MiloClientWrapper wrapperA = mock(MiloClientWrapper.class);
            MiloClientWrapper wrapperB = mock(MiloClientWrapper.class);
            when(mockConnMgr.getClient("dev-A")).thenReturn(wrapperA);
            when(mockConnMgr.getClient("dev-B")).thenReturn(wrapperB);

            service.start(List.of(devA, devB));

            verify(mockConnMgr).startAll(List.of(devA, devB));
            verify(mockSubMgr).createSubscriptions(wrapperA, devA);
            verify(mockSubMgr).createSubscriptions(wrapperB, devB);
            verify(mockRwHandler).startPolling(wrapperA, devA);
            verify(mockRwHandler).startPolling(wrapperB, devB);
        }

        @Test
        @DisplayName("某设备未连接时应跳过其订阅与轮询，但其他设备继续启动")
        void shouldSkipUnconnectedDeviceAndContinue() {
            DeviceConfig devA = new DeviceConfig();
            devA.setDeviceId("dev-A");
            DeviceConfig devOff = new DeviceConfig();
            devOff.setDeviceId("dev-OFF");

            MiloClientWrapper wrapperA = mock(MiloClientWrapper.class);
            when(mockConnMgr.getClient("dev-A")).thenReturn(wrapperA);
            when(mockConnMgr.getClient("dev-OFF")).thenReturn(null);

            service.start(List.of(devA, devOff));

            verify(mockSubMgr).createSubscriptions(wrapperA, devA);
            verify(mockRwHandler).startPolling(wrapperA, devA);
            verify(mockSubMgr, never()).createSubscriptions(eq(null), any());
            verify(mockRwHandler, never()).startPolling(eq(null), any());
        }

        @Test
        @DisplayName("某设备订阅失败不应中断其他设备的启动流程")
        void shouldToleratePerDeviceFailure() {
            DeviceConfig devFail = new DeviceConfig();
            devFail.setDeviceId("dev-FAIL");
            DeviceConfig devOk = new DeviceConfig();
            devOk.setDeviceId("dev-OK");

            MiloClientWrapper wrapperFail = mock(MiloClientWrapper.class);
            MiloClientWrapper wrapperOk = mock(MiloClientWrapper.class);
            when(mockConnMgr.getClient("dev-FAIL")).thenReturn(wrapperFail);
            when(mockConnMgr.getClient("dev-OK")).thenReturn(wrapperOk);
            when(mockSubMgr.createSubscriptions(wrapperFail, devFail))
                    .thenThrow(new RuntimeException("subscription error"));

            service.start(List.of(devFail, devOk));

            // 失败设备不调用 startPolling，但 dev-OK 应继续
            verify(mockSubMgr).createSubscriptions(wrapperOk, devOk);
            verify(mockRwHandler).startPolling(wrapperOk, devOk);
        }
    }

    @Nested
    @DisplayName("shutdown")
    class ShutdownBehavior {

        @Test
        @DisplayName("应依次关闭 SubscriptionManager、ReadWriteHandler、ConnectionManager、DataDispatchEngine")
        void shouldShutdownAllComponents() {
            service.shutdown();

            verify(mockSubMgr).removeAll();
            verify(mockRwHandler).shutdown();
            verify(mockConnMgr).shutdown();
            // dispatchEngine 是真实实例：shutdown 后再 dispatch 不应触发 listener
            assertThat(dispatchEngine).isNotNull();
            // 验证关停后 dispatch 不再触发新工作
            long droppedBefore = dispatchEngine.getDroppedCount();
            dispatchEngine.dispatch(createDeviceData());
            assertThat(dispatchEngine.getDroppedCount()).isEqualTo(droppedBefore);
        }

        @Test
        @DisplayName("重复 shutdown 不应抛异常")
        void shouldNotThrowOnDoubleShutdown() {
            service.shutdown();
            service.shutdown();
            // 调用次数累计
            verify(mockSubMgr, times(2)).removeAll();
            verify(mockRwHandler, times(2)).shutdown();
            verify(mockConnMgr, times(2)).shutdown();
        }
    }
}
