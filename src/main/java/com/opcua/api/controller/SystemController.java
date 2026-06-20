package com.opcua.api.controller;

import com.opcua.api.dto.ApiResponse;
import com.opcua.config.ConfigService;
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
        Map<String, Object> healthData = new LinkedHashMap<>();
        healthData.put("status", "UP");
        healthData.put("deviceCount", configService.getDeviceCount());
        healthData.put("ruleCount", configService.getRuleCount());
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
                    var state = configService.getDeviceState(dc.getDeviceId());
                    info.put("status", state != null ? state.getState().name() : "UNKNOWN");
                    return info;
                })
                .toList();
        statusData.put("devices", deviceInfos);
        return ResponseEntity.ok(ApiResponse.success(statusData));
    }
}
