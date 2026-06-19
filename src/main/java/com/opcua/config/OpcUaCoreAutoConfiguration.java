package com.opcua.config;

import com.opcua.api.OpcUaService;
import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import com.opcua.health.OpcUaHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * OPC UA 核心采集层自动配置。
 *
 * <p>仅在 {@code opcua.enabled=true}（默认）时激活。所有 Bean 标注
 * {@code @ConditionalOnMissingBean}，允许上层应用替换默认实现。</p>
 *
 * <p>关停顺序由 {@link OpcUaService#shutdown()} 编排，因此其他底层 Bean
 * 显式禁用 destroyMethod 推断（{@code destroyMethod = ""}），避免容器
 * 关闭时重复或乱序关停。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "opcua", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OpcUaProperties.class)
public class OpcUaCoreAutoConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public ConnectionManager connectionManager() {
        return new ConnectionManager();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public DataDispatchEngine dataDispatchEngine(OpcUaProperties properties) {
        OpcUaProperties.DispatchConfig dispatch = properties.getDispatch();
        return new DataDispatchEngine(
                dispatch.getBucketCount(),
                dispatch.getQueueCapacity(),
                Collections.emptyList()
        );
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public SubscriptionManager subscriptionManager(DataDispatchEngine dispatchEngine) {
        return new SubscriptionManager(dispatchEngine);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public ReadWriteHandler readWriteHandler(DataDispatchEngine dispatchEngine) {
        return new ReadWriteHandler(dispatchEngine);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public OpcUaService opcUaService(ConnectionManager connectionManager,
                                     DataDispatchEngine dispatchEngine,
                                     SubscriptionManager subscriptionManager,
                                     ReadWriteHandler readWriteHandler) {
        return new OpcUaService(connectionManager, dispatchEngine,
                subscriptionManager, readWriteHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    public OpcUaHealthIndicator opcUaHealthIndicator(OpcUaService opcUaService) {
        return new OpcUaHealthIndicator(opcUaService);
    }
}
