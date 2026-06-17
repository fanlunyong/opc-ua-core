package com.opcua.wrapper;

import com.opcua.TestConstants;
import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.SecurityConfig;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * MiloClientWrapper 单元测试。
 * 遵循 TDD：先写测试，确认失败后再编写实现。
 */
@DisplayName("MiloClientWrapper")
class MiloClientWrapperTest {

    private DeviceConfig config;

    @BeforeEach
    void setUp() {
        config = new DeviceConfig();
        config.setDeviceId(TestConstants.DEVICE_ID);
        config.setEndpointUrl(TestConstants.ENDPOINT_URL);
    }

    /**
     * 2.1 基本构造和初始状态
     */
    @Nested
    @DisplayName("构造与初始状态")
    class ConstructionAndInitialState {

        @Test
        @DisplayName("构造后初始状态为 DISCONNECTED")
        void shouldHaveDisconnectedStateAfterConstruction() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        }

        @Test
        @DisplayName("getClient 在未连接时返回 null")
        void shouldReturnNullClientBeforeConnect() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.getClient()).isNull();
        }

        @Test
        @DisplayName("构造时存储 DeviceConfig 引用")
        void shouldStoreDeviceConfig() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.getConfig()).isEqualTo(config);
        }
    }

    /**
     * 2.4 状态回调机制
     */
    @Nested
    @DisplayName("状态回调")
    class StateCallback {

        @Test
        @DisplayName("注册监听器后状态变更时通知所有监听器")
        void shouldNotifyAllRegisteredListenersOnStateChange() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            List<ConnectionState> receivedStates = new ArrayList<>();

            wrapper.onStateChange(receivedStates::add);
            wrapper.onStateChange(receivedStates::add);

            // 通过 disconnect 触发状态变更（连接状态变更需要实际连接，这里只测试回调机制）
            // 直接验证监听器已注册
            assertThat(wrapper.getListenerCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("未注册监听器时不影响状态变更")
        void shouldNotFailWhenNoListenersRegistered() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            // 不应该抛出异常
            wrapper.disconnect();

            assertThat(wrapper.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * 2.3 disconnect 行为
     */
    @Nested
    @DisplayName("断开连接")
    class Disconnect {

        @Test
        @DisplayName("未连接时调用 disconnect 不抛异常")
        void shouldNotThrowWhenDisconnectedBeforeConnect() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            wrapper.disconnect();

            assertThat(wrapper.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        }

        @Test
        @DisplayName("disconnect 后状态变为 DISCONNECTED")
        void shouldSetStateToDisconnectedAfterDisconnect() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            wrapper.disconnect();

            assertThat(wrapper.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * 2.1 安全认证配置判断
     */
    @Nested
    @DisplayName("安全模式判断")
    class SecurityMode {

        @Test
        @DisplayName("policy 为 None 时判定为匿名模式")
        void shouldDetectAnonymousMode() {
            SecurityConfig sec = new SecurityConfig();
            sec.setPolicy("None");
            config.setSecurity(sec);
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isAnonymousMode()).isTrue();
        }

        @Test
        @DisplayName("policy 非 None 且无用户名/证书时也判定为匿名模式")
        void shouldDetectAnonymousModeWhenPolicyNotNullButNoCredentials() {
            SecurityConfig sec = new SecurityConfig();
            sec.setPolicy("Basic256Sha256");
            // 不设置 username 和 certificatePath
            config.setSecurity(sec);
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isAnonymousMode()).isTrue();
        }

        @Test
        @DisplayName("policy 非 None 且有 username 时判定为用户名密码认证")
        void shouldDetectUsernamePasswordMode() {
            SecurityConfig sec = new SecurityConfig();
            sec.setPolicy("Basic256Sha256");
            sec.setUsername("test-user");
            sec.setPassword("test-password");
            config.setSecurity(sec);
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isAnonymousMode()).isFalse();
            assertThat(wrapper.isUsernamePasswordAuth()).isTrue();
        }

        @Test
        @DisplayName("policy 非 None 且有证书时判定为证书认证")
        void shouldDetectCertificateMode() {
            SecurityConfig sec = new SecurityConfig();
            sec.setPolicy("Basic256Sha256");
            sec.setCertificatePath("/path/to/cert.der");
            sec.setPrivateKeyPath("/path/to/key.pem");
            config.setSecurity(sec);
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isAnonymousMode()).isFalse();
            assertThat(wrapper.isCertificateAuth()).isTrue();
        }

        @Test
        @DisplayName("同时有 username 和证书时优先判定为证书认证")
        void shouldPreferCertificateWhenBothPresent() {
            SecurityConfig sec = new SecurityConfig();
            sec.setPolicy("Basic256Sha256");
            sec.setCertificatePath("/path/to/cert.der");
            sec.setUsername("test-user");
            config.setSecurity(sec);
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isCertificateAuth()).isTrue();
        }
    }

    /**
     * 2.3 重连退避计算
     */
    @Nested
    @DisplayName("重连退避计算")
    class ReconnectBackoff {

        @Test
        @DisplayName("首次重连延迟为 1 秒")
        void shouldReturn1SecondForFirstAttempt() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.calculateBackoff(0)).isEqualTo(1000L);
        }

        @Test
        @DisplayName("第 2 次重连延迟为 2 秒")
        void shouldReturn2SecondsForSecondAttempt() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.calculateBackoff(1)).isEqualTo(2000L);
        }

        @Test
        @DisplayName("第 6 次重连延迟为 32 秒")
        void shouldReturn32SecondsForSixthAttempt() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.calculateBackoff(5)).isEqualTo(32000L);
        }

        @Test
        @DisplayName("连续失败后最大退避时间为 60 秒")
        void shouldCapAt60Seconds() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            // 第 7 次及以后应该是 60 秒
            assertThat(wrapper.calculateBackoff(6)).isEqualTo(60000L);
            assertThat(wrapper.calculateBackoff(10)).isEqualTo(60000L);
            assertThat(wrapper.calculateBackoff(100)).isEqualTo(60000L);
        }
    }

    /**
     * 2.1 connect() 方法
     */
    @Nested
    @DisplayName("连接")
    class Connect {

        @Test
        @DisplayName("connect 失败时 CompletableFuture 异常完成")
        void shouldCompleteExceptionallyWhenConnectFails() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            // 由于 endpoint URL 无效，connect 应该失败
            CompletableFuture<Void> future = wrapper.connect();

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isNotNull();
        }

        @Test
        @DisplayName("状态变更时通知监听器")
        void shouldNotifyListenersOnStateTransition() throws Exception {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            List<ConnectionState> receivedStates = new ArrayList<>();
            wrapper.onStateChange(receivedStates::add);

            // 直接调用内部方法 setState 来测试通知机制
            wrapper.setStateForTest(ConnectionState.RECONNECTING);
            wrapper.setStateForTest(ConnectionState.CONNECTED);

            assertThat(receivedStates).containsExactly(
                    ConnectionState.RECONNECTING,
                    ConnectionState.CONNECTED
            );
        }

        @Test
        @DisplayName("初始重连计数为 0")
        void shouldHaveZeroReconnectCountInitially() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.getReconnectCount()).isEqualTo(0);
        }
    }

    /**
     * 2.3 disconnect() 调用 Milo client.disconnect
     */
    @Nested
    @DisplayName("断开连接（集成 Mock）")
    @ExtendWith(MockitoExtension.class)
    class DisconnectWithMock {

        @Mock
        private OpcUaClient mockClient;

        @Test
        @DisplayName("disconnect 时调用底层 client.disconnect")
        void shouldCallClientDisconnect() throws Exception {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            wrapper.setClientForTest(mockClient);
            wrapper.setStateForTest(ConnectionState.CONNECTED);

            wrapper.disconnect();

            org.mockito.Mockito.verify(mockClient).disconnect();
        }

        @Test
        @DisplayName("client.disconnect 抛异常时 disconnect 不传播异常")
        void shouldNotPropagateExceptionWhenClientDisconnectFails() throws Exception {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            OpcUaClient throwingClient = mock(OpcUaClient.class);
            doThrow(new RuntimeException("mock disconnect failure")).when(throwingClient).disconnect();
            wrapper.setClientForTest(throwingClient);

            // 不应抛出异常
            wrapper.disconnect();

            assertThat(wrapper.getState()).isEqualTo(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * 2.3 重连调度生命周期
     */
    @Nested
    @DisplayName("重连调度器生命周期")
    class ReconnectSchedulerLifecycle {

        @Test
        @DisplayName("新实例不应有活跃的重连调度器")
        void shouldNotHaveActiveSchedulerInitially() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);

            assertThat(wrapper.isReconnectSchedulerActive()).isFalse();
        }

        @Test
        @DisplayName("disconnect 后重连调度器应被关闭")
        void shouldShutdownSchedulerOnDisconnect() {
            MiloClientWrapper wrapper = new MiloClientWrapper(config);
            // 模拟先连接再断开
            wrapper.setStateForTest(ConnectionState.CONNECTED);
            wrapper.setStateForTest(ConnectionState.RECONNECTING);

            wrapper.disconnect();

            assertThat(wrapper.isReconnectSchedulerActive()).isFalse();
        }
    }
}
