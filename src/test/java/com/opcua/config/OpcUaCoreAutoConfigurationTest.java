package com.opcua.config;

import com.opcua.api.OpcUaService;
import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import com.opcua.health.OpcUaHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpcUaCoreAutoConfiguration 单元测试。
 */
@DisplayName("OpcUaCoreAutoConfiguration")
class OpcUaCoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpcUaCoreAutoConfiguration.class));

    @Test
    @DisplayName("默认启用时应装配所有核心 Bean")
    void shouldRegisterAllBeansWhenEnabled() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ConnectionManager.class);
            assertThat(ctx).hasSingleBean(DataDispatchEngine.class);
            assertThat(ctx).hasSingleBean(SubscriptionManager.class);
            assertThat(ctx).hasSingleBean(ReadWriteHandler.class);
            assertThat(ctx).hasSingleBean(OpcUaService.class);
            assertThat(ctx).hasSingleBean(OpcUaHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("opcua.enabled=false 时不应装配核心 Bean")
    void shouldNotRegisterBeansWhenDisabled() {
        runner.withPropertyValues("opcua.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(OpcUaService.class);
            assertThat(ctx).doesNotHaveBean(OpcUaHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("OpcUaProperties 应被绑定")
    void shouldBindOpcUaProperties() {
        runner.withPropertyValues(
                "opcua.dispatch.bucket-count=8",
                "opcua.dispatch.queue-capacity=200"
        ).run(ctx -> {
            OpcUaProperties props = ctx.getBean(OpcUaProperties.class);
            assertThat(props.getDispatch().getBucketCount()).isEqualTo(8);
            assertThat(props.getDispatch().getQueueCapacity()).isEqualTo(200);
        });
    }
}
