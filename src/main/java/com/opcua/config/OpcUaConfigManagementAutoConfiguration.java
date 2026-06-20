package com.opcua.config;

import com.opcua.api.controller.DeviceController;
import com.opcua.api.controller.ForwardRuleController;
import com.opcua.api.controller.SystemController;
import com.opcua.core.ConnectionManager;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.model.DeviceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OPC UA 配置管理模块自动配置。
 *
 * <p>注册 ConfigService、ConfigPersistenceService、GlobalExceptionHandler、
 * DeviceController、ForwardRuleController 等 Bean，并将 ConfigService 与
 * ConnectionManager、ForwardingEngine、持久化层串联。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "opcua.config-management", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OpcUaConfigManagementAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(OpcUaConfigManagementAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ConfigService configService() {
        return new ConfigService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigPersistenceService configPersistenceService(
            @Value("${opcua.config-management.persistence-path:config/opcua-runtime.yml}") String persistencePath) {
        Path path = Paths.get(persistencePath);
        return new ConfigPersistenceService(path);
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeviceController deviceController(ConfigService configService) {
        return new DeviceController(configService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ForwardRuleController forwardRuleController(ConfigService configService) {
        return new ForwardRuleController(configService);
    }

    @Bean
    @ConditionalOnMissingBean
    public SystemController systemController(ConfigService configService) {
        return new SystemController(configService);
    }

    /**
     * 在 ConfigService、ConnectionManager、ForwardingEngine 都创建后，
     * 注入依赖、注册监听器、从持久化文件恢复配置并同步到运行时组件。
     *
     * <p>ConnectionManager 通过适配器桥接 ConfigChangeEvent（避免 core → config
     * 包依赖）。ForwardingEngine 直接实现 ConfigChangeListener。两者均通过
     * ObjectProvider 可选注入，兼容 opcua.enabled=false 或
     * opcua.forward.enabled=false 的场景。</p>
     *
     * <p>启动恢复顺序：先注册 listeners，再 loadFromPersistence + syncToListeners，
     * 确保持久化的设备/规则通过事件同步到 ConnectionManager 和 ForwardingEngine。
     * 适配器和 ForwardingEngine 均做重复检查，避免与 YAML 初始配置重复。</p>
     */
    @Bean
    public ConfigServiceInitializer configServiceInitializer(ConfigService configService,
                                              ConfigPersistenceService persistenceService,
                                              ObjectProvider<ConnectionManager> connectionManagerProvider,
                                              ObjectProvider<ForwardingEngine> forwardingEngineProvider) {
        ConnectionManager connectionManager = connectionManagerProvider.getIfAvailable();
        ForwardingEngine forwardingEngine = forwardingEngineProvider.getIfAvailable();

        if (connectionManager != null) {
            configService.setConnectionManager(connectionManager);
            configService.registerListener(event -> {
                switch (event.getType()) {
                    case DEVICE_ADDED -> {
                        DeviceConfig dc = (DeviceConfig) event.getPayload();
                        if (connectionManager.getState(event.getTargetId()) == null) {
                            connectionManager.addDevice(dc);
                        }
                    }
                    case DEVICE_REMOVED -> connectionManager.removeDevice(event.getTargetId());
                    case DEVICE_UPDATED -> {
                        DeviceConfig dc = (DeviceConfig) event.getPayload();
                        connectionManager.updateDevice(event.getTargetId(), dc);
                    }
                }
            });
        }

        if (forwardingEngine != null) {
            configService.registerListener(forwardingEngine);
        }

        configService.setPersistenceService(persistenceService);
        configService.loadFromPersistence();
        configService.syncToListeners();
        logger.info("ConfigService initialized: {} devices, {} rules",
                configService.getDeviceCount(), configService.getRuleCount());
        return new ConfigServiceInitializer();
    }

    /** 占位 Bean，仅用于触发 configServiceInitializer 的初始化逻辑。 */
    public static class ConfigServiceInitializer {
    }
}
