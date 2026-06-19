package com.opcua.forward.config;

import com.opcua.api.OpcUaService;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.forward.sender.CompositeSenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpcUaForwardAutoConfigurationTest {

    @Configuration
    static class StubOpcUaCore {
        @Bean OpcUaService opcUaService() { return mock(OpcUaService.class); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubOpcUaCore.class)
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OpcUaForwardAutoConfiguration.class));

    @Test
    void enabledByDefault_registersBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ForwardProperties.class);
            assertThat(ctx).hasSingleBean(SenderRegistry.class);
            assertThat(ctx).hasSingleBean(CompositeSenderFactory.class);
            assertThat(ctx).hasSingleBean(ForwardingEngine.class);
        });
    }

    @Test
    void disabledExplicitly_skipsRegistration() {
        runner.withPropertyValues("opcua.forward.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ForwardingEngine.class);
            assertThat(ctx).doesNotHaveBean(SenderRegistry.class);
        });
    }
}