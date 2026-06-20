package com.opcua.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.api.dto.ForwardRuleDTO;
import com.opcua.config.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ConfigService.class, ForwardRuleController.class})
class ForwardRuleControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        for (var rule : configService.getAllRules()) {
            configService.removeRule(rule.getName());
        }
    }

    @Test
    void shouldAddAndGetRule() throws Exception {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("test-kafka");
        dto.setType("KAFKA");
        dto.setEnabled(true);
        dto.setConfig(Map.of(
                "bootstrapServers", "localhost:9092",
                "topic", "opcua-data"
        ));

        mockMvc.perform(post("/api/forward/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201));

        mockMvc.perform(get("/api/forward/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("test-kafka"));
    }

    @Test
    void shouldDeleteRule() throws Exception {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("to-delete");
        dto.setType("HTTP");
        dto.setConfig(Map.of("url", "http://localhost:8080"));

        mockMvc.perform(post("/api/forward/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/forward/rules/to-delete"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/forward/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldToggleRule() throws Exception {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("toggle-rule");
        dto.setType("KAFKA");
        dto.setEnabled(true);
        dto.setConfig(Map.of("bootstrapServers", "localhost:9092", "topic", "test"));

        mockMvc.perform(post("/api/forward/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/forward/rules/toggle-rule/disable"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/forward/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].enabled").value(false));

        mockMvc.perform(patch("/api/forward/rules/toggle-rule/enable"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/forward/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].enabled").value(true));
    }

    @Test
    void shouldRejectDuplicateRuleName() throws Exception {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("dup-rule");
        dto.setType("KAFKA");
        dto.setConfig(Map.of("bootstrapServers", "localhost:9092", "topic", "test"));

        mockMvc.perform(post("/api/forward/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/forward/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldUpdateRule() throws Exception {
        ForwardRuleDTO dto = new ForwardRuleDTO();
        dto.setName("update-rule");
        dto.setType("KAFKA");
        dto.setConfig(Map.of("bootstrapServers", "localhost:9092", "topic", "old-topic"));

        mockMvc.perform(post("/api/forward/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        dto.setConfig(Map.of("bootstrapServers", "localhost:9092", "topic", "new-topic"));
        mockMvc.perform(put("/api/forward/rules/update-rule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/forward/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].config.topic").value("new-topic"));
    }
}
