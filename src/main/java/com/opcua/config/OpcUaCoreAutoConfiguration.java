package com.opcua.config;

import com.opcua.api.OpcUaService;
import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import com.opcua.health.OpcUaHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * OPC UA 核心采集层自动配置。
 *
 * <p>仅在 {@code opcua.enabled=true}（默认）时激活。装配
 * ConnectionManager、DataDispatchEngine、SubscriptionManager、
 * ReadWriteHandler、OpcUaService 与 OpcUaHealthIndicator。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "opcua", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OpcUaProperties.class)
public class OpcUaCoreAutoConfiguration {

    @Bean
    public ConnectionManager connectionManager() {
        return new ConnectionManager();
    }

    @Bean
    public DataDispatchEngine dataDispatchEngine(OpcUaProperties properties) {
        OpcUaProperties.DispatchConfig dispatch = properties.getDispatch();
        return new DataDispatchEngine(
                dispatch.getBucketCount(),
                dispatch.getQueueCapacity(),
                Collections.emptyList()
        );
    }

    @Bean
    public SubscriptionManager subscriptionManager(DataDispatchEngine dispatchEngine) {
        return new SubscriptionManager(dispatchEngine);
    }

    @Bean
    public ReadWriteHandler readWriteHandler(DataDispatchEngine dispatchEngine) {
        return new ReadWriteHandler(dispatchEngine);
    }

    @Bean
    public OpcUaService opcUaService(ConnectionManager connectionManager,
                                     DataDispatchEngine dispatchEngine,
                                     SubscriptionManager subscriptionManager,
                                     ReadWriteHandler readWriteHandler) {
        return new OpcUaService(connectionManager, dispatchEngine,
                subscriptionManager, readWriteHandler);
    }

    @Bean
    public OpcUaHealthIndicator opcUaHealthIndicator(OpcUaService opcUaService) {
        return new OpcUaHealthIndicator(opcUaService);
    }
}
