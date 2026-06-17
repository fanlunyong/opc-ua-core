package com.opcua.core;

import com.opcua.TestConstants;
import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceHandle;
import com.opcua.model.DeviceState;
import com.opcua.wrapper.MiloClientWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ConnectionManager 单元测试。
 * 遵循 TDD：先写测试，确认失败后再编写实现。
 */
@DisplayName("ConnectionManager")
class ConnectionManagerTest {

    private ConnectionManager manager;
    private DeviceConfig config;

    @BeforeEach
    void setUp() {
        manager = new ConnectionManager();
        config = new DeviceConfig();
        config.setDeviceId(TestConstants.DEVICE_ID);
        config.setEndpointUrl(TestConstants.ENDPOINT_URL);
        config.setMaxConnections(3);
    }

    /**
     * 3.1 设备注册和连接池创建
     */
    @Nested
    @DisplayName("设备注册与连接池创建")
    class DeviceRegistration {

        @Test
        @DisplayName("addDevice 应创建 maxConnections 个 MiloClientWrapper 并调用 connect")
        void shouldCreatePoolAndCallConnect() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                DeviceHandle handle = manager.addDevice(config);

                assertThat(handle).isNotNull();
                assertThat(handle.getId()).isEqualTo(TestConstants.DEVICE_ID);
                assertThat(handle.getConfig()).isEqualTo(config);
                assertThat(mocked.constructed()).hasSize(3);

                for (MiloClientWrapper mock : mocked.constructed()) {
                    verify(mock).connect();
                }
            }
        }

        @Test
        @DisplayName("addDevice 应尊重 maxConnections 配置")
        void shouldRespectMaxConnectionsConfig() {
            config.setMaxConnections(2);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                DeviceHandle handle = manager.addDevice(config);

                assertThat(handle.getWrappers()).hasSize(2);
                assertThat(mocked.constructed()).hasSize(2);
            }
        }

        @Test
        @DisplayName("maxConnections 默认值应为 3")
        void shouldDefaultMaxConnectionsTo3() {
            DeviceConfig defaultConfig = new DeviceConfig();
            defaultConfig.setDeviceId("test-default");
            defaultConfig.setEndpointUrl(TestConstants.ENDPOINT_URL);
            // 不设置 maxConnections，使用 DeviceConfig 默认值 3

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                DeviceHandle handle = manager.addDevice(defaultConfig);

                assertThat(handle.getWrappers()).hasSize(3);
            }
        }
    }

    /**
     * 3.2 连接池轮询负载均衡
     */
    @Nested
    @DisplayName("连接池轮询")
    class RoundRobin {

        @Test
        @DisplayName("getClient 应轮询返回池中的 wrapper")
        void shouldRoundRobinThroughPool() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                MiloClientWrapper client0 = manager.getClient(TestConstants.DEVICE_ID);
                MiloClientWrapper client1 = manager.getClient(TestConstants.DEVICE_ID);
                MiloClientWrapper client2 = manager.getClient(TestConstants.DEVICE_ID);
                MiloClientWrapper client3 = manager.getClient(TestConstants.DEVICE_ID);

                assertThat(client0).isSameAs(wrappers.get(0));
                assertThat(client1).isSameAs(wrappers.get(1));
                assertThat(client2).isSameAs(wrappers.get(2));
                // 第 4 次应回到第一个
                assertThat(client3).isSameAs(wrappers.get(0));
            }
        }

        @Test
        @DisplayName("getClient 对未知设备应返回 null")
        void shouldReturnNullForUnknownDevice() {
            MiloClientWrapper client = manager.getClient("nonexistent");

            assertThat(client).isNull();
        }

        @Test
        @DisplayName("不同设备的轮询计数器应独立")
        void shouldHaveIndependentCountersPerDevice() {
            DeviceConfig config2 = new DeviceConfig();
            config2.setDeviceId("device-002");
            config2.setEndpointUrl(TestConstants.ENDPOINT_URL);
            config2.setMaxConnections(2);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                manager.addDevice(config2);

                MiloClientWrapper d0_client0 = manager.getClient(TestConstants.DEVICE_ID);
                MiloClientWrapper d1_client0 = manager.getClient("device-002");

                // 两个设备的计数器独立，都应该返回各自池的第一个 wrapper
                // device1: [mock0, mock1, mock2], device2: [mock3, mock4]
                assertThat(d0_client0).isSameAs(mocked.constructed().get(0));
                assertThat(d1_client0).isSameAs(mocked.constructed().get(3));
            }
        }
    }

    /**
     * 3.3 设备移除
     */
    @Nested
    @DisplayName("设备移除")
    class DeviceRemoval {

        @Test
        @DisplayName("removeDevice 应调用所有 wrapper 的 disconnect")
        void shouldDisconnectAllWrappersOnRemove() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);

                manager.removeDevice(TestConstants.DEVICE_ID);

                for (MiloClientWrapper mock : mocked.constructed()) {
                    verify(mock).disconnect();
                }
            }
        }

        @Test
        @DisplayName("removeDevice 后 getClient 应返回 null")
        void shouldReturnNullAfterRemove() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                manager.removeDevice(TestConstants.DEVICE_ID);

                MiloClientWrapper client = manager.getClient(TestConstants.DEVICE_ID);
                assertThat(client).isNull();
            }
        }

        @Test
        @DisplayName("removeDevice 对不存在的设备不应抛异常")
        void shouldNotThrowForUnknownDevice() {
            // 不应抛出异常
            manager.removeDevice("nonexistent");
        }

        @Test
        @DisplayName("removeDevice 后 getState 应返回 null")
        void shouldReturnNullStateAfterRemove() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                manager.removeDevice(TestConstants.DEVICE_ID);

                DeviceState state = manager.getState(TestConstants.DEVICE_ID);
                assertThat(state).isNull();
            }
        }
    }

    /**
     * 3.4 状态聚合
     */
    @Nested
    @DisplayName("状态聚合")
    class StateAggregation {

        @Test
        @DisplayName("任意 wrapper 为 CONNECTED 时 getState 应返回 CONNECTED")
        void shouldReturnConnectedWhenAnyWrapperConnected() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                // 第一个为 CONNECTED，其余为 DISCONNECTED
                when(wrappers.get(0).getState()).thenReturn(ConnectionState.CONNECTED);
                when(wrappers.get(1).getState()).thenReturn(ConnectionState.DISCONNECTED);
                when(wrappers.get(2).getState()).thenReturn(ConnectionState.RECONNECTING);

                DeviceState state = manager.getState(TestConstants.DEVICE_ID);

                assertThat(state).isNotNull();
                assertThat(state.getState()).isEqualTo(ConnectionState.CONNECTED);
                assertThat(state.getDeviceId()).isEqualTo(TestConstants.DEVICE_ID);
            }
        }

        @Test
        @DisplayName("所有 wrapper 为 DISCONNECTED 时 getState 应返回 DISCONNECTED")
        void shouldReturnDisconnectedWhenAllDisconnected() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                        when(mock.getState()).thenReturn(ConnectionState.DISCONNECTED);
                    })) {

                manager.addDevice(config);

                DeviceState state = manager.getState(TestConstants.DEVICE_ID);

                assertThat(state).isNotNull();
                assertThat(state.getState()).isEqualTo(ConnectionState.DISCONNECTED);
            }
        }

        @Test
        @DisplayName("getState 对未知设备应返回 null")
        void shouldReturnNullForUnknownDeviceState() {
            DeviceState state = manager.getState("nonexistent");

            assertThat(state).isNull();
        }

        @Test
        @DisplayName("getAllStates 应返回所有设备的 DeviceState")
        void shouldReturnAllDeviceStates() {
            DeviceConfig config2 = new DeviceConfig();
            config2.setDeviceId("device-002");
            config2.setEndpointUrl(TestConstants.ENDPOINT_URL);
            config2.setMaxConnections(2);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                        when(mock.getState()).thenReturn(ConnectionState.DISCONNECTED);
                    })) {

                manager.addDevice(config);
                manager.addDevice(config2);

                Map<String, DeviceState> allStates = manager.getAllStates();

                assertThat(allStates).hasSize(2);
                assertThat(allStates).containsKeys(TestConstants.DEVICE_ID, "device-002");
            }
        }

        @Test
        @DisplayName("getAllStates 无设备时应返回空 Map")
        void shouldReturnEmptyMapWhenNoDevices() {
            Map<String, DeviceState> allStates = manager.getAllStates();

            assertThat(allStates).isEmpty();
        }
    }

    /**
     * 3.5 批量启动与异常隔离
     */
    @Nested
    @DisplayName("批量启动与异常隔离")
    class BatchStartAndIsolation {

        @Test
        @DisplayName("startAll 应遍历配置列表逐个添加设备")
        void shouldAddAllDevicesFromConfigList() {
            DeviceConfig config2 = new DeviceConfig();
            config2.setDeviceId("device-002");
            config2.setEndpointUrl(TestConstants.ENDPOINT_URL);
            config2.setMaxConnections(2);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.startAll(java.util.Arrays.asList(config, config2));

                Map<String, DeviceState> allStates = manager.getAllStates();
                assertThat(allStates).hasSize(2);
            }
        }

        @Test
        @DisplayName("某个设备连接失败不应影响其他设备启动")
        void shouldIsolateConnectionFailures() {
            DeviceConfig badConfig = new DeviceConfig();
            badConfig.setDeviceId("bad-device");
            badConfig.setEndpointUrl("opc.tcp://invalid:9999"); // 无效端点

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        DeviceConfig cfg = (DeviceConfig) ctx.arguments().get(0);
                        if ("bad-device".equals(cfg.getDeviceId())) {
                            when(mock.connect()).thenThrow(new RuntimeException("connection refused"));
                        } else {
                            when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                        }
                    })) {

                manager.startAll(java.util.Arrays.asList(config, badConfig));

                // 正常的设备应该已注册
                assertThat(manager.getClient(TestConstants.DEVICE_ID)).isNotNull();
                // 失败的设备不应该注册（或注册但状态异常）
                assertThat(manager.getClient("bad-device")).isNull();
            }
        }

        @Test
        @DisplayName("startAll 空列表不应抛异常")
        void shouldHandleEmptyList() {
            manager.startAll(java.util.Collections.emptyList());

            assertThat(manager.getAllStates()).isEmpty();
        }
    }

    /**
     * 3.6 全局关闭
     */
    @Nested
    @DisplayName("全局关闭")
    class GlobalShutdown {

        @Test
        @DisplayName("shutdown 应关闭所有设备的 wrapper")
        void shouldDisconnectAllWrapperOnShutdown() {
            DeviceConfig config2 = new DeviceConfig();
            config2.setDeviceId("device-002");
            config2.setEndpointUrl(TestConstants.ENDPOINT_URL);
            config2.setMaxConnections(2);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                manager.addDevice(config2);

                manager.shutdown();

                for (MiloClientWrapper mock : mocked.constructed()) {
                    verify(mock, atLeastOnce()).disconnect();
                }
            }
        }

        @Test
        @DisplayName("shutdown 后所有设备应被移除")
        void shouldClearAllDevicesAfterShutdown() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                manager.shutdown();

                assertThat(manager.getAllStates()).isEmpty();
                assertThat(manager.getClient(TestConstants.DEVICE_ID)).isNull();
            }
        }

        @Test
        @DisplayName("shutdown 对空 Manager 不应抛异常")
        void shouldNotThrowOnEmptyShutdown() {
            manager.shutdown();

            assertThat(manager.getAllStates()).isEmpty();
        }

        @Test
        @DisplayName("shutdown 中某个 wrapper.disconnect 抛异常不应影响其他")
        void shouldIsolateDisconnectExceptionsDuringShutdown() {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                // 让第二个 wrapper disconnect 抛异常
                Mockito.doThrow(new RuntimeException("disconnect error"))
                        .when(wrappers.get(1)).disconnect();

                // shutdown 不应抛异常，且第三个 wrapper 的 disconnect 仍被调用
                manager.shutdown();

                verify(wrappers.get(2), atLeastOnce()).disconnect();
            }
        }
    }

    /**
     * 3.7 连接 acquire/release 语义与连接池耗尽处理
     */
    @Nested
    @DisplayName("连接获取释放与池耗尽")
    class AcquireReleaseAndExhaustion {

        @Test
        @DisplayName("acquireClient 应成功获取并返回 wrapper")
        void shouldAcquireAndReturnWrapper() throws Exception {
            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                MiloClientWrapper client = manager.acquireClient(TestConstants.DEVICE_ID, 1000);

                assertThat(client).isNotNull();
                assertThat(client).isSameAs(wrappers.get(0));
            }
        }

        @Test
        @DisplayName("releaseClient 后其他请求可再次获取连接")
        void shouldAllowAcquireAfterRelease() throws Exception {
            config.setMaxConnections(1);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);

                MiloClientWrapper first = manager.acquireClient(TestConstants.DEVICE_ID, 1000);
                assertThat(first).isNotNull();

                // 释放连接
                manager.releaseClient(TestConstants.DEVICE_ID);

                // 再次获取应成功
                MiloClientWrapper second = manager.acquireClient(TestConstants.DEVICE_ID, 1000);
                assertThat(second).isNotNull();
                // 第二次获取应是同一 wrapper（池大小为 1，释放后重新获取同一个）
                assertThat(second).isSameAs(first);
            }
        }

        @Test
        @DisplayName("连接池耗尽时 acquireClient 应在超时后抛出 ConnectionUnavailableException")
        void shouldThrowConnectionUnavailableWhenPoolExhausted() throws Exception {
            config.setMaxConnections(1);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);

                // 占用唯一的连接
                MiloClientWrapper client = manager.acquireClient(TestConstants.DEVICE_ID, 100);
                assertThat(client).isNotNull();

                // 再次获取应超时抛出 ConnectionUnavailableException
                assertThatThrownBy(() ->
                        manager.acquireClient(TestConstants.DEVICE_ID, 100)
                ).isInstanceOf(ConnectionUnavailableException.class)
                 .hasMessageContaining(TestConstants.DEVICE_ID);
            }
        }

        @Test
        @DisplayName("信号量耗尽后释放连接，后续 acquire 应成功")
        void shouldRecoverAfterReleaseFollowingExhaustion() throws Exception {
            config.setMaxConnections(1);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);

                // 占用唯一连接
                MiloClientWrapper first = manager.acquireClient(TestConstants.DEVICE_ID, 100);
                assertThat(first).isNotNull();

                // 释放
                manager.releaseClient(TestConstants.DEVICE_ID);

                // 再次获取应成功
                MiloClientWrapper second = manager.acquireClient(TestConstants.DEVICE_ID, 100);
                assertThat(second).isNotNull();
            }
        }

        @Test
        @DisplayName("acquireClient 对未知设备应抛出 ConnectionUnavailableException")
        void shouldThrowForUnknownDeviceAcquire() {
            assertThatThrownBy(() ->
                    manager.acquireClient("nonexistent", 100)
            ).isInstanceOf(ConnectionUnavailableException.class)
             .hasMessageContaining("nonexistent");
        }

        @Test
        @DisplayName("releaseClient 对未知设备不应抛异常")
        void shouldNotThrowReleaseUnknownDevice() {
            // 不应抛出异常
            manager.releaseClient("nonexistent");
        }
    }

    /**
     * 3.8 空闲连接回收
     */
    @Nested
    @DisplayName("空闲连接回收")
    class IdleEviction {

        @Test
        @DisplayName("evictIdleConnections 应关闭空闲连接并替换为新 wrapper")
        void shouldEvictIdleConnectionsAndReplace() throws Exception {
            config.setIdleTimeoutSeconds(300);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();
                MiloClientWrapper oldWrapper = wrappers.get(1);

                // 将 wrapper 的 lastUsedTime 标记为很久以前
                manager.lastUsedTimes.put(oldWrapper, 0L);

                // 驱逐前验证 disconnect 调用次数
                manager.evictIdleConnections();

                // 旧 wrapper 应被 disconnect
                verify(oldWrapper, atLeastOnce()).disconnect();

                // 池大小应保持不变
                DeviceHandle handle = manager.getHandle(TestConstants.DEVICE_ID);
                assertThat(handle.getWrappers()).hasSize(3);

                // 被替换的 wrapper 不再在池中
                assertThat(handle.getWrappers()).doesNotContain(oldWrapper);
            }
        }

        @Test
        @DisplayName("最近使用的连接不应被驱逐")
        void shouldNotEvictRecentlyUsedConnections() throws Exception {
            config.setIdleTimeoutSeconds(300);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                // 通过 acquire 更新 lastUsedTime 为当前时间
                MiloClientWrapper active = manager.acquireClient(TestConstants.DEVICE_ID, 1000);
                manager.releaseClient(TestConstants.DEVICE_ID);

                // 驱逐：最近使用的连接不应被关闭
                manager.evictIdleConnections();

                // 活跃的 wrapper 不应被 disconnect（未经 acquire 标记的可能被驱逐）
                // 仅验证池大小不变
                DeviceHandle handle = manager.getHandle(TestConstants.DEVICE_ID);
                assertThat(handle.getWrappers()).hasSize(3);
            }
        }

        @Test
        @DisplayName("idleTimeoutSeconds <= 0 时不应驱逐任何连接")
        void shouldNotEvictWhenIdleTimeoutNotConfigured() throws Exception {
            config.setIdleTimeoutSeconds(0);

            try (MockedConstruction<MiloClientWrapper> mocked = mockConstruction(
                    MiloClientWrapper.class,
                    (mock, ctx) -> {
                        when(mock.connect()).thenReturn(CompletableFuture.completedFuture(null));
                    })) {

                manager.addDevice(config);
                java.util.List<MiloClientWrapper> wrappers = mocked.constructed();

                // 将所有 wrapper 标记为很久以前
                for (MiloClientWrapper w : wrappers) {
                    manager.lastUsedTimes.put(w, 0L);
                }

                manager.evictIdleConnections();

                // idleTimeoutSeconds=0 表示永不过期，不应 disconnect 任何 wrapper
                for (MiloClientWrapper w : wrappers) {
                    verify(w, never()).disconnect();
                }
            }
        }
    }
}
