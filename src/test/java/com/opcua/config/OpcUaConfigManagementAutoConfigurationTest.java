package com.opcua.config;

import com.opcua.api.controller.DeviceController;
import com.opcua.api.controller.ForwardRuleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OpcUaConfigManagementAutoConfigurationTest {

    @Autowired(required = false)
    private ConfigService configService;

    @Autowired(required = false)
    private ConfigPersistenceService configPersistenceService;

    @Autowired(required = false)
    private DeviceController deviceController;

    @Autowired(required = false)
    private ForwardRuleController forwardRuleController;

    @Autowired(required = false)
    private GlobalExceptionHandler globalExceptionHandler;

    @Test
    void shouldLoadConfigServiceBean() {
        assertThat(configService).isNotNull();
    }

    @Test
    void shouldLoadConfigPersistenceServiceBean() {
        assertThat(configPersistenceService).isNotNull();
    }

    @Test
    void shouldLoadDeviceControllerBean() {
        assertThat(deviceController).isNotNull();
    }

    @Test
    void shouldLoadForwardRuleControllerBean() {
        assertThat(forwardRuleController).isNotNull();
    }

    @Test
    void shouldLoadGlobalExceptionHandlerBean() {
        assertThat(globalExceptionHandler).isNotNull();
    }
}
