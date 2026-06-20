package com.opcua.config;

import com.opcua.api.controller.DeviceController;
import com.opcua.api.controller.ForwardRuleController;
import com.opcua.core.ConnectionManager;
import com.opcua.forward.engine.ForwardingEngine;
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

    /**
     * 在 ConfigService、ConnectionManager、ForwardingEngine 都创建后，
     * 注入依赖并从持久化文件恢复配置、注册 ForwardingEngine 为监听器。
     * ConnectionManager 和 ForwardingEngine 通过 ObjectProvider 可选注入，
     * 以兼容 opcua.enabled=false 或 opcua.forward.enabled=false 的场景。
     */
    @Bean
    public ConfigServiceInitializer configServiceInitializer(ConfigService configService,
                                              ConfigPersistenceService persistenceService,
                                              ObjectProvider<ConnectionManager> connectionManagerProvider,
                                              ObjectProvider<ForwardingEngine> forwardingEngineProvider) {
        ConnectionManager connectionManager = connectionManagerProvider.getIfAvailable();
        if (connectionManager != null) {
            configService.setConnectionManager(connectionManager);
        }
        configService.setPersistenceService(persistenceService);
        configService.loadFromPersistence();
        ForwardingEngine forwardingEngine = forwardingEngineProvider.getIfAvailable();
        if (forwardingEngine != null) {
            configService.registerListener(forwardingEngine);
        }
        return new ConfigServiceInitializer();
    }

    /** 占位 Bean，仅用于触发 configServiceInitializer 的初始化逻辑。 */
    public static class ConfigServiceInitializer {
    }
}
