package com.opcua.health;

import com.opcua.api.OpcUaService;
import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceState;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OPC UA 健康检查指示器。
 *
 * <p>注册到 Spring Boot Actuator，依据所有设备的连接状态决定整体健康度：
 * 全部 CONNECTED 或无设备 → UP；任一非 CONNECTED → DOWN。</p>
 */
public class OpcUaHealthIndicator implements HealthIndicator {

    private final OpcUaService opcUaService;

    public OpcUaHealthIndicator(OpcUaService opcUaService) {
        this.opcUaService = opcUaService;
    }

    @Override
    public Health health() {
        Map<String, DeviceState> states = opcUaService.getDeviceStates();
        int total = states.size();
        long connected = states.values().stream()
                .filter(s -> s.getState() == ConnectionState.CONNECTED)
                .count();

        Health.Builder builder = (total == 0 || connected == total)
                ? Health.up()
                : Health.down();

        // 构建 per-device 详情：deviceId → { state, message }（spec health-check 9.1 要求）
        Map<String, Object> deviceDetails = new LinkedHashMap<>();
        for (Map.Entry<String, DeviceState> entry : states.entrySet()) {
            DeviceState s = entry.getValue();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("state", s.getState() != null ? s.getState().name() : "UNKNOWN");
            if (s.getMessage() != null) {
                info.put("message", s.getMessage());
            }
            if (s.getConnectedSince() != null) {
                info.put("connectedSince", s.getConnectedSince().toString());
            }
            if (s.getLastDataReceived() != null) {
                info.put("lastDataReceived", s.getLastDataReceived().toString());
            }
            deviceDetails.put(entry.getKey(), info);
        }

        return builder
                .withDetail("totalDevices", total)
                .withDetail("connectedDevices", (int) connected)
                .withDetail("devices", deviceDetails)
                .build();
    }
}
