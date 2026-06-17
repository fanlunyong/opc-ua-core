package com.opcua.wrapper;

import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.SecurityConfig;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.api.UaSession;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Eclipse Milo OpcUaClient 封装层。
 * 提供统一的生命周期管理、安全认证和断线重连。
 *
 * <p>重连状态机:
 * <pre>
 *   CONNECTED --(断开)--▶ RECONNECTING --(成功)--▶ CONNECTED
 *       ▲                    │
 *       │                    │(连续失败, 退避时间递增)
 *       │                    ▼
 *       │              RECONNECTING (等待 backoff ms 后重试)
 *       │                    │
 *       └────────────────────┘(成功)
 *
 *   任意状态 --(主动 disconnect)--> DISCONNECTED (不再重连)
 * </pre>
 */
public class MiloClientWrapper {

    private static final Logger logger = LoggerFactory.getLogger(MiloClientWrapper.class);

    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final long MAX_BACKOFF_MS = 60000L;

    private final DeviceConfig config;
    private volatile ConnectionState state;
    private volatile OpcUaClient client;
    private final List<Consumer<ConnectionState>> stateListeners = new CopyOnWriteArrayList<>();

    // 重连相关
    private volatile ScheduledExecutorService reconnectScheduler;
    private volatile ScheduledFuture<?> reconnectFuture;
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private volatile int consecutiveFailures = 0;

    /**
     * 构造函数，初始化内部状态为 DISCONNECTED。
     *
     * @param config 设备配置
     */
    public MiloClientWrapper(DeviceConfig config) {
        this.config = config;
        this.state = ConnectionState.DISCONNECTED;
    }

    // === 公开方法 ===

    /**
     * 获取当前连接状态。
     */
    public ConnectionState getState() {
        return state;
    }

    /**
     * 获取底层 OpcUaClient 实例。
     * 仅在连接建立后非 null。
     */
    public OpcUaClient getClient() {
        return client;
    }

    /**
     * 获取设备配置。
     */
    public DeviceConfig getConfig() {
        return config;
    }

    /**
     * 获取累计重连次数（用于监控指标暴露）。
     */
    public int getReconnectCount() {
        return reconnectCount.get();
    }

    /**
     * 注册状态变更监听器。
     *
     * @param listener 状态变更回调
     */
    public void onStateChange(Consumer<ConnectionState> listener) {
        stateListeners.add(listener);
    }

    /**
     * 获取已注册的监听器数量（测试用）。
     */
    int getListenerCount() {
        return stateListeners.size();
    }

    /**
     * 建立 OPC UA 连接。
     *
     * @return 连接完成的 CompletableFuture，失败时异常完成
     */
    public CompletableFuture<Void> connect() {
        if (state == ConnectionState.CONNECTED) {
            logger.debug("已是连接状态，跳过: deviceId={}", config.getDeviceId());
            return CompletableFuture.completedFuture(null);
        }

        logger.info("开始建立连接: deviceId={}, endpointUrl={}", config.getDeviceId(), config.getEndpointUrl());

        try {
            this.client = buildClient();
        } catch (Exception e) {
            logger.error("构建 OpcUaClient 失败: deviceId={}", config.getDeviceId(), e);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }

        return client.connect()
                .thenAccept(ignored -> {
                    setState(ConnectionState.CONNECTED);
                    consecutiveFailures = 0;
                    logger.info("连接成功: deviceId={}, endpointUrl={}",
                            config.getDeviceId(), config.getEndpointUrl());

                    // 注册会话活动监听器
                    client.addSessionActivityListener(new SessionActivityListener() {
                        @Override
                        public void onSessionActive(UaSession session) {
                            logger.debug("会话活跃: deviceId={}, session={}", config.getDeviceId(), session.getSessionName());
                        }

                        @Override
                        public void onSessionInactive(UaSession session) {
                            logger.warn("会话失效: deviceId={}, session={}", config.getDeviceId(), session.getSessionName());
                            onConnectionLost();
                        }
                    });
                });
    }

    /**
     * 断开连接，取消重连。
     * 异常被捕获，不允许逃逸。
     */
    public void disconnect() {
        logger.info("主动断开连接: deviceId={}", config.getDeviceId());
        cancelReconnect();
        setState(ConnectionState.DISCONNECTED);

        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                logger.warn("断开连接时发生异常: deviceId={}", config.getDeviceId(), e);
            }
        }
    }

    // === 安全模式判断 ===

    /**
     * 判断是否为匿名（无安全）模式。
     */
    public boolean isAnonymousMode() {
        SecurityConfig sec = config.getSecurity();
        if (sec == null) {
            return true;
        }
        String policy = sec.getPolicy();
        if (policy == null || "None".equalsIgnoreCase(policy)) {
            return true;
        }
        // policy 不为 None 但既没有用户名也没有证书 → 仍然视为匿名
        if (!isUsernamePasswordAuth() && !isCertificateAuth()) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否使用用户名密码认证。
     */
    public boolean isUsernamePasswordAuth() {
        SecurityConfig sec = config.getSecurity();
        if (sec == null) {
            return false;
        }
        String policy = sec.getPolicy();
        if (policy == null || "None".equalsIgnoreCase(policy)) {
            return false;
        }
        return sec.getUsername() != null && !sec.getUsername().isBlank();
    }

    /**
     * 判断是否使用证书认证。
     */
    public boolean isCertificateAuth() {
        SecurityConfig sec = config.getSecurity();
        if (sec == null) {
            return false;
        }
        String policy = sec.getPolicy();
        if (policy == null || "None".equalsIgnoreCase(policy)) {
            return false;
        }
        return sec.getCertificatePath() != null && !sec.getCertificatePath().isBlank();
    }

    // === 重连退避计算 ===

    /**
     * 根据重连失败次数计算下次退避时间（毫秒）。
     * 指数退避: 1s → 2s → 4s → 8s → 16s → 32s → 最大 60s
     *
     * @param attemptCount 已失败次数（0-based：0=第一次重试）
     * @return 退避时间（毫秒）
     */
    long calculateBackoff(int attemptCount) {
        long backoff = INITIAL_BACKOFF_MS * (1L << attemptCount);
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    /**
     * 判断重连调度器是否活跃（测试用）。
     */
    boolean isReconnectSchedulerActive() {
        return reconnectScheduler != null && !reconnectScheduler.isShutdown();
    }

    // === 测试辅助方法（package-private） ===

    /**
     * 设置连接状态（测试用）。
     */
    void setStateForTest(ConnectionState newState) {
        setState(newState);
    }

    /**
     * 设置底层客户端实例（测试用）。
     */
    void setClientForTest(OpcUaClient client) {
        this.client = client;
    }

    // === 内部方法 ===

    /**
     * 安全地设置状态并通知监听器。
     */
    private void setState(ConnectionState newState) {
        ConnectionState oldState = this.state;
        this.state = newState;
        if (oldState != newState) {
            logger.info("连接状态变更: deviceId={}, {} → {}",
                    config.getDeviceId(), oldState, newState);
            notifyListeners(newState);
        }
    }

    /**
     * 构建 OpcUaClient 实例。
     * 根据 SecurityConfig 配置安全策略：
     * - policy == "None" 或 null → 匿名连接
     * - policy != "None" 且 username != null → 用户名密码认证
     * - policy != "None" 且 certificatePath != null → 证书认证
     */
    private OpcUaClient buildClient() throws Exception {
        String endpointUrl = config.getEndpointUrl();
        String deviceId = config.getDeviceId();

        // 确定 IdentityProvider
        IdentityProvider identityProvider = buildIdentityProvider();

        // 确定安全策略 URI
        String securityPolicyUri = resolveSecurityPolicyUri();

        // 端点选择函数：匹配安全策略，修复跨网段地址问题
        Function<List<EndpointDescription>, Optional<EndpointDescription>> selectEndpoint =
                endpoints -> {
                    Optional<EndpointDescription> matched = endpoints.stream()
                            .filter(e -> {
                                String policyUri = e.getSecurityPolicyUri();
                                return securityPolicyUri.equals(policyUri);
                            })
                            .findFirst();

                    // 修复服务器可能返回内网地址的问题
                    return matched.map(ep -> new EndpointDescription(
                            endpointUrl,
                            ep.getServer(),
                            ep.getServerCertificate(),
                            ep.getSecurityMode(),
                            ep.getSecurityPolicyUri(),
                            ep.getUserIdentityTokens(),
                            ep.getTransportProfileUri(),
                            ep.getSecurityLevel()
                    ));
                };

        // 使用三合一 API 构建客户端
        return OpcUaClient.create(
                endpointUrl,
                selectEndpoint,
                configBuilder ->
                        configBuilder
                                .setApplicationName(LocalizedText.english("OPC UA Core Collector"))
                                .setApplicationUri("urn:com:opcua:core:collector")
                                .setIdentityProvider(identityProvider)
                                .setSessionTimeout(UInteger.valueOf(config.getSessionTimeout() * 1000))
                                .setRequestTimeout(UInteger.valueOf(10000))
                                .build()
        );
    }

    /**
     * 根据 SecurityConfig 构建 IdentityProvider。
     */
    private IdentityProvider buildIdentityProvider() {
        SecurityConfig sec = config.getSecurity();

        if (isAnonymousMode()) {
            logger.info("使用匿名模式连接: deviceId={}", config.getDeviceId());
            return new AnonymousProvider();
        }

        if (isCertificateAuth()) {
            logger.info("使用证书认证: deviceId={}, cert={}",
                    config.getDeviceId(), sec.getCertificatePath());
            // 证书认证：TODO 在后续任务中实现 X509IdentityProvider 的证书加载
            // 当前阶段暂不支持证书认证，回退到匿名模式
            logger.warn("证书认证尚未完全实现，回退到匿名模式: deviceId={}", config.getDeviceId());
            return new AnonymousProvider();
        }

        if (isUsernamePasswordAuth()) {
            logger.info("使用用户名密码认证: deviceId={}, username={}",
                    config.getDeviceId(), sec.getUsername());
            return new UsernameProvider(sec.getUsername(), sec.getPassword());
        }

        // 兜底：匿名模式
        return new AnonymousProvider();
    }

    /**
     * 根据 SecurityConfig.policy 解析对应的安全策略 URI。
     */
    private String resolveSecurityPolicyUri() {
        SecurityConfig sec = config.getSecurity();
        if (sec == null || sec.getPolicy() == null || "None".equalsIgnoreCase(sec.getPolicy())) {
            return SecurityPolicy.None.getUri();
        }

        String policy = sec.getPolicy();
        // 标准 OPC UA 安全策略映射
        switch (policy) {
            case "Basic128Rsa15":
                return SecurityPolicy.Basic128Rsa15.getUri();
            case "Basic256":
                return SecurityPolicy.Basic256.getUri();
            case "Basic256Sha256":
                return SecurityPolicy.Basic256Sha256.getUri();
            case "Aes128Sha256RsaOaep":
                return SecurityPolicy.Aes128_Sha256_RsaOaep.getUri();
            case "Aes256Sha256RsaPss":
                return SecurityPolicy.Aes256_Sha256_RsaPss.getUri();
            default:
                logger.warn("未知安全策略: {}, 回退到 None", policy);
                return SecurityPolicy.None.getUri();
        }
    }

    /**
     * 连接丢失时触发重连流程。
     */
    private void onConnectionLost() {
        ConnectionState current = state;
        if (current == ConnectionState.DISCONNECTED) {
            // 主动断开，不重连
            return;
        }

        logger.warn("连接丢失，进入重连流程: deviceId={}", config.getDeviceId());
        setState(ConnectionState.RECONNECTING);
        scheduleReconnect();
    }

    /**
     * 调度重连任务。
     */
    private void scheduleReconnect() {
        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "opcua-reconnect-" + config.getDeviceId());
                t.setDaemon(true);
                return t;
            });
        }

        long delay = calculateBackoff(consecutiveFailures);
        logger.info("计划重连: deviceId={}, 第{}次尝试, 延迟{}ms",
                config.getDeviceId(), consecutiveFailures + 1, delay);

        reconnectFuture = reconnectScheduler.schedule(this::attemptReconnect, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 执行重连尝试。
     */
    private void attemptReconnect() {
        if (state == ConnectionState.DISCONNECTED) {
            logger.debug("重连已取消（主动断开）: deviceId={}", config.getDeviceId());
            return;
        }

        logger.info("尝试重连: deviceId={}, 第{}次", config.getDeviceId(), consecutiveFailures + 1);
        reconnectCount.incrementAndGet();

        try {
            if (this.client != null) {
                try {
                    this.client.disconnect();
                } catch (Exception ignored) {
                    // 忽略旧连接断开时的异常
                }
            }

            this.client = buildClient();

            this.client.connect()
                    .thenAccept(ignored -> {
                        setState(ConnectionState.CONNECTED);
                        consecutiveFailures = 0;
                        logger.info("重连成功: deviceId={}", config.getDeviceId());

                        // 重新注册会话活动监听器
                        this.client.addSessionActivityListener(new SessionActivityListener() {
                            @Override
                            public void onSessionActive(UaSession session) {
                                logger.debug("会话活跃: deviceId={}, session={}", config.getDeviceId(), session.getSessionName());
                            }

                            @Override
                            public void onSessionInactive(UaSession session) {
                                logger.warn("会话失效: deviceId={}, session={}", config.getDeviceId(), session.getSessionName());
                                onConnectionLost();
                            }
                        });
                    })
                    .exceptionally(ex -> {
                        logger.error("重连失败: deviceId={}, 第{}次, error={}",
                                config.getDeviceId(), consecutiveFailures + 1, ex.getMessage());
                        consecutiveFailures++;
                        // 保持 RECONNECTING 状态，继续调度下次重连
                        if (state != ConnectionState.DISCONNECTED) {
                            scheduleReconnect();
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.error("重连时构建客户端失败: deviceId={}, error={}", config.getDeviceId(), e.getMessage());
            consecutiveFailures++;
            if (state != ConnectionState.DISCONNECTED) {
                scheduleReconnect();
            }
        }
    }

    /**
     * 取消重连调度器。
     */
    private void cancelReconnect() {
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }

        if (reconnectScheduler != null && !reconnectScheduler.isShutdown()) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }
    }

    /**
     * 通知所有已注册的状态监听器。
     */
    private void notifyListeners(ConnectionState newState) {
        for (Consumer<ConnectionState> listener : stateListeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                logger.warn("状态监听器回调异常: deviceId={}", config.getDeviceId(), e);
            }
        }
    }
}
