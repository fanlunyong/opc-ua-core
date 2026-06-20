package com.opcua.api.controller;

import com.opcua.api.dto.ApiResponse;
import com.opcua.api.dto.DeviceConfigDTO;
import com.opcua.config.ConfigService;
import com.opcua.model.ConnectionState;
import com.opcua.model.DeviceConfig;
import com.opcua.model.DeviceState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final ConfigService configService;

    public DeviceController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceConfigDTO>>> getAll() {
        List<DeviceConfigDTO> dtos = configService.getAllDevices().stream()
                .map(dc -> {
                    DeviceState state = configService.getDeviceState(dc.getDeviceId());
                    ConnectionState cs = state != null ? state.getState() : null;
                    return DeviceConfigDTO.fromDeviceConfig(dc, cs);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceConfigDTO>> getById(@PathVariable String id) {
        DeviceConfig config = configService.getDevice(id);
        if (config == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Device not found: " + id));
        }
        DeviceState state = configService.getDeviceState(id);
        ConnectionState cs = state != null ? state.getState() : null;
        return ResponseEntity.ok(ApiResponse.success(DeviceConfigDTO.fromDeviceConfig(config, cs)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> add(@RequestBody DeviceConfigDTO dto) {
        if (dto.getId() == null || dto.getId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "id is required"));
        }
        if (dto.getEndpointUrl() == null || dto.getEndpointUrl().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "endpointUrl is required"));
        }
        DeviceConfig config = DeviceConfigDTO.toDeviceConfig(dto);
        configService.addDevice(config);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> update(@PathVariable String id,
                                                     @RequestBody DeviceConfigDTO dto) {
        if (configService.getDevice(id) == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Device not found: " + id));
        }
        dto.setId(id);
        DeviceConfig config = DeviceConfigDTO.toDeviceConfig(dto);
        configService.updateDevice(id, config);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        configService.removeDevice(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
