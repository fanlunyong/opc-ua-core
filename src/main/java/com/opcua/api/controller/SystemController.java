package com.opcua.api.controller;

import com.opcua.api.dto.ApiResponse;
import com.opcua.config.ConfigService;
import com.opcua.model.DeviceState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {

    private final ConfigService configService;

    public SystemController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/api/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        List<DeviceState> deviceStates = configService.getAllDevices().stream()
                .map(dc -> configService.getDeviceState(dc.getDeviceId()))
                .filter(java.util.Objects::nonNull)
                .toList();

        long connected = deviceStates.stream()
                .filter(s -> s.getState() == com.opcua.model.ConnectionState.CONNECTED)
                .count();
        long disconnected = deviceStates.stream()
                .filter(s -> s.getState() == com.opcua.model.ConnectionState.DISCONNECTED)
                .count();

        String status = disconnected > 0 ? "DEGRADED" : "UP";

        Map<String, Object> healthData = new LinkedHashMap<>();
        healthData.put("status", status);
        healthData.put("deviceCount", configService.getDeviceCount());
        healthData.put("connectedDevices", connected);
        healthData.put("disconnectedDevices", disconnected);
        healthData.put("ruleCount", configService.getRuleCount());

        List<Map<String, Object>> deviceDetails = configService.getAllDevices().stream()
                .map(dc -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", dc.getDeviceId());
                    DeviceState state = configService.getDeviceState(dc.getDeviceId());
                    info.put("status", state != null ? state.getState().name() : "UNKNOWN");
                    return info;
                })
                .toList();
        healthData.put("devices", deviceDetails);

        return ResponseEntity.ok(ApiResponse.success(healthData));
    }

    @GetMapping("/api/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> statusData = new LinkedHashMap<>();
        statusData.put("deviceCount", configService.getDeviceCount());
        statusData.put("ruleCount", configService.getRuleCount());

        List<Map<String, Object>> deviceInfos = configService.getAllDevices().stream()
                .map(dc -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", dc.getDeviceId());
                    info.put("endpointUrl", dc.getEndpointUrl());
                    DeviceState state = configService.getDeviceState(dc.getDeviceId());
                    info.put("status", state != null ? state.getState().name() : "UNKNOWN");
                    return info;
                })
                .toList();
        statusData.put("devices", deviceInfos);

        // Sender 配置摘要：按 target 类型统计配置的转发通道
        // 注：实际 Sender 连通状态需要 Change 2 扩展 Sender 接口（isConnected），
        // 当前仅报告配置层面的 target 类型分布
        Map<String, Long> senderTypes = new LinkedHashMap<>();
        configService.getAllRules().stream()
                .flatMap(r -> r.getTargets() != null ? r.getTargets().stream() : java.util.stream.Stream.empty())
                .forEach(t -> senderTypes.merge(
                        t.getType() != null ? t.getType().toUpperCase() : "UNKNOWN", 1L, Long::sum));
        statusData.put("configuredSenderTypes", senderTypes);

        return ResponseEntity.ok(ApiResponse.success(statusData));
    }
}
