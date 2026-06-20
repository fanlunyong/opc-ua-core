package com.opcua.api.controller;

import com.opcua.api.dto.ApiResponse;
import com.opcua.api.dto.ForwardRuleDTO;
import com.opcua.config.ConfigService;
import com.opcua.forward.config.ForwardRule;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/forward/rules")
public class ForwardRuleController {

    private final ConfigService configService;

    public ForwardRuleController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ForwardRuleDTO>>> getAll() {
        List<ForwardRuleDTO> dtos = configService.getAllRules().stream()
                .map(ForwardRuleDTO::fromForwardRule)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    @GetMapping("/{name}")
    public ResponseEntity<ApiResponse<ForwardRuleDTO>> getByName(@PathVariable String name) {
        ForwardRule rule = configService.getRule(name);
        if (rule == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Rule not found: " + name));
        }
        return ResponseEntity.ok(ApiResponse.success(ForwardRuleDTO.fromForwardRule(rule)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> add(@RequestBody ForwardRuleDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "name is required"));
        }
        if (dto.getType() == null || dto.getType().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "type is required"));
        }
        ForwardRule rule = ForwardRuleDTO.toForwardRule(dto);
        configService.addRule(rule);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(201, "created", null));
    }

    @PutMapping("/{name}")
    public ResponseEntity<ApiResponse<Void>> update(@PathVariable String name,
                                                     @RequestBody ForwardRuleDTO dto) {
        if (configService.getRule(name) == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Rule not found: " + name));
        }
        dto.setName(name);
        ForwardRule rule = ForwardRuleDTO.toForwardRule(dto);
        configService.updateRule(name, rule);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        configService.removeRule(name);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{name}/enable")
    public ResponseEntity<ApiResponse<Void>> enable(@PathVariable String name) {
        configService.toggleRule(name, true);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PatchMapping("/{name}/disable")
    public ResponseEntity<ApiResponse<Void>> disable(@PathVariable String name) {
        configService.toggleRule(name, false);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
