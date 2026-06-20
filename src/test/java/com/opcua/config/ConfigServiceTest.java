package com.opcua.config;

import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.model.DeviceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {

    @TempDir
    Path tempDir;

    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new ConfigService();
    }

    @Test
    void shouldAddAndRetrieveDevice() {
        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("device-1");
        config.setEndpointUrl("opc.tcp://localhost:4840");

        configService.addDevice(config);

        assertThat(configService.getDevice("device-1")).isNotNull();
        assertThat(configService.getAllDevices()).hasSize(1);
        assertThat(configService.getDeviceCount()).isEqualTo(1);
    }

    @Test
    void shouldThrowOnDuplicateDevice() {
        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("device-1");
        config.setEndpointUrl("opc.tcp://localhost:4840");

        configService.addDevice(config);

        assertThatThrownBy(() -> configService.addDevice(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void shouldFireEventOnDeviceAdd() {
        java.util.concurrent.atomic.AtomicInteger eventCount = new java.util.concurrent.atomic.AtomicInteger(0);
        configService.registerListener(event -> eventCount.incrementAndGet());

        DeviceConfig config = new DeviceConfig();
        config.setDeviceId("device-1");
        config.setEndpointUrl("opc.tcp://localhost:4840");
        configService.addDevice(config);

        assertThat(eventCount.get()).isEqualTo(1);
    }

    @Test
    void shouldAddAndRemoveRule() {
        ForwardRule rule = new ForwardRule();
        rule.setName("rule-1");
        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        rule.setTargets(List.of(target));

        configService.addRule(rule);
        assertThat(configService.getAllRules()).hasSize(1);

        configService.removeRule("rule-1");
        assertThat(configService.getAllRules()).isEmpty();
    }

    @Test
    void shouldToggleRule() {
        ForwardRule rule = new ForwardRule();
        rule.setName("rule-1");
        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setEnabled(true);
        rule.setTargets(List.of(target));

        configService.addRule(rule);
        configService.toggleRule("rule-1", false);

        assertThat(configService.getRule("rule-1").getTargets().get(0).isEnabled()).isFalse();
    }

    @Test
    void shouldBeThreadSafeForConcurrentAccess() throws Exception {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    DeviceConfig config = new DeviceConfig();
                    config.setDeviceId("device-" + idx);
                    config.setEndpointUrl("opc.tcp://localhost:" + (4840 + idx));
                    configService.addDevice(config);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(configService.getDeviceCount()).isEqualTo(threadCount);
    }

    @Test
    void shouldPersistAndRecoverFromYaml() {
        Path configFile = tempDir.resolve("runtime.yml");
        ConfigPersistenceService persistence = new ConfigPersistenceService(configFile);
        configService.setPersistenceService(persistence);

        DeviceConfig dc = new DeviceConfig();
        dc.setDeviceId("persisted-device");
        dc.setEndpointUrl("opc.tcp://localhost:4840");
        configService.addDevice(dc);

        // 验证持久化文件已写入
        assertThat(configFile).exists();

        // 新 ConfigService 从持久化恢复
        ConfigService recovered = new ConfigService();
        recovered.setPersistenceService(persistence);
        recovered.loadFromPersistence();

        assertThat(recovered.getDeviceCount()).isEqualTo(1);
        assertThat(recovered.getDevice("persisted-device")).isNotNull();
    }
}
