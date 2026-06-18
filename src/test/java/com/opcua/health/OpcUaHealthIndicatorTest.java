package com.opcua.health;

import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceState;
import com.opcua.api.OpcUaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OpcUaHealthIndicator 单元测试。
 */
@DisplayName("OpcUaHealthIndicator")
class OpcUaHealthIndicatorTest {

    private OpcUaService createMockService(Map<String, DeviceState> states) {
        OpcUaService service = mock(OpcUaService.class);
        when(service.getDeviceStates()).thenReturn(states);
        return service;
    }

    @Nested
    @DisplayName("健康状态判定")
    class HealthStatus {

        @Test
        @DisplayName("所有设备已连接 → UP")
        void allConnectedShouldBeUp() {
            Map<String, DeviceState> states = Map.of(
                    "dev1", new DeviceState("dev1", ConnectionState.CONNECTED, Instant.now(), null, null),
                    "dev2", new DeviceState("dev2", ConnectionState.CONNECTED, Instant.now(), null, null)
            );
            OpcUaHealthIndicator indicator = new OpcUaHealthIndicator(createMockService(states));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("无设备 → UP")
        void noDevicesShouldBeUp() {
            OpcUaHealthIndicator indicator = new OpcUaHealthIndicator(
                    createMockService(Map.of()));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
        }

        @Test
        @DisplayName("任一设备未连接 → DOWN")
        void anyDisconnectedShouldBeDown() {
            Map<String, DeviceState> states = Map.of(
                    "dev1", new DeviceState("dev1", ConnectionState.CONNECTED, Instant.now(), null, null),
                    "dev2", new DeviceState("dev2", ConnectionState.DISCONNECTED, null, null, null)
            );
            OpcUaHealthIndicator indicator = new OpcUaHealthIndicator(createMockService(states));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("健康状况包含连接数详情和 per-device 状态")
        void healthShouldIncludeDetails() {
            Map<String, DeviceState> states = Map.of(
                    "dev1", new DeviceState("dev1", ConnectionState.CONNECTED, Instant.now(), null, null),
                    "dev2", new DeviceState("dev2", ConnectionState.CONNECTED, Instant.now(), null, null)
            );
            OpcUaHealthIndicator indicator = new OpcUaHealthIndicator(createMockService(states));
            Health health = indicator.health();
            assertThat(health.getDetails())
                    .containsEntry("totalDevices", 2)
                    .containsEntry("connectedDevices", 2)
                    .containsKey("devices");
            @SuppressWarnings("unchecked")
            Map<String, Object> devices = (Map<String, Object>) health.getDetails().get("devices");
            assertThat(devices).containsKeys("dev1", "dev2");
        }

        @Test
        @DisplayName("DOWN 时应在 devices 详情中列出异常设备及其状态")
        void downShouldExposeFailingDeviceState() {
            Map<String, DeviceState> states = Map.of(
                    "dev-OK", new DeviceState("dev-OK", ConnectionState.CONNECTED, Instant.now(), null, null),
                    "dev-OFF", new DeviceState("dev-OFF", ConnectionState.DISCONNECTED, null, null, "endpoint timeout")
            );
            OpcUaHealthIndicator indicator = new OpcUaHealthIndicator(createMockService(states));
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            @SuppressWarnings("unchecked")
            Map<String, Object> devices = (Map<String, Object>) health.getDetails().get("devices");
            @SuppressWarnings("unchecked")
            Map<String, Object> offDetails = (Map<String, Object>) devices.get("dev-OFF");
            assertThat(offDetails).containsEntry("state", "DISCONNECTED");
            assertThat(offDetails).containsEntry("message", "endpoint timeout");
        }
    }
}