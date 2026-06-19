package com.opcua.forward.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ForwardPropertiesBindingTest.TestConfig.class)
@TestPropertySource(locations = "classpath:forward-binding-test.yml",
                    factory = YamlPropertySourceFactory.class)
class ForwardPropertiesBindingTest {

    @EnableConfigurationProperties(ForwardProperties.class)
    static class TestConfig { }

    @Autowired
    ForwardProperties properties;

    @Test
    void bindsRootDefaultsAndShutdownTimeout() {
        assertTrue(properties.isEnabled());
        assertEquals(Duration.ofSeconds(3), properties.getShutdownTimeout());
        assertEquals(1, properties.getRules().size());
    }

    @Test
    void bindsRuleMatchAndTargets() {
        ForwardRule rule = properties.getRules().get(0);
        assertEquals("rule-A", rule.getName());
        assertEquals("line-A", rule.getMatch().getProductId());
        assertEquals("furnace-1", rule.getMatch().getDeviceId());

        assertEquals(1, rule.getTargets().size());
        ForwardTarget t = rule.getTargets().get(0);
        assertEquals("kafka", t.getType());
        assertTrue(t.isEnabled());
        assertEquals("kafka:9092", t.getBootstrapServers());
        assertEquals("data-{productId}", t.getTopic());
        assertEquals(500, t.getQueueCapacity());
    }

    @Test
    void bindsAlertConfig() {
        AlertConfig alerts = properties.getRules().get(0).getAlerts();
        assertNotNull(alerts);
        assertTrue(alerts.isEnabled());
        assertEquals(1, alerts.getTargets().size());
        assertEquals("http", alerts.getTargets().get(0).getType());
        assertEquals("http://alert/notify", alerts.getTargets().get(0).getUrl());
        assertEquals(3000, alerts.getTargets().get(0).getTimeoutMillis());
    }
}
