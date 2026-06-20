package com.opcua.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.api.dto.ApiResponse;
import com.opcua.api.dto.DeviceConfigDTO;
import com.opcua.config.ConfigService;
import com.opcua.model.DeviceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ConfigService.class, DeviceController.class})
class DeviceControllerTest {

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        for (DeviceConfig dc : configService.getAllDevices()) {
            configService.removeDevice(dc.getDeviceId());
        }
    }

    @Test
    void shouldAddAndGetDevice() throws Exception {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("device-1");
        dto.setEndpointUrl("opc.tcp://localhost:4840");
        dto.setNodes(List.of(new DeviceConfigDTO.NodeDTO("ns=2;s=Temp", "Temperature", "Double")));

        // POST
        String postResp = mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(201))
                .andReturn().getResponse().getContentAsString();

        ApiResponse<?> parsed = objectMapper.readValue(postResp, ApiResponse.class);
        assertThat(parsed.getCode()).isEqualTo(201);

        // GET all
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("device-1"));

        // GET by id
        mockMvc.perform(get("/api/devices/device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("device-1"))
                .andExpect(jsonPath("$.data.endpointUrl").value("opc.tcp://localhost:4840"));
    }

    @Test
    void shouldReturn404ForMissingDevice() throws Exception {
        mockMvc.perform(get("/api/devices/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void shouldDeleteDevice() throws Exception {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("device-to-delete");
        dto.setEndpointUrl("opc.tcp://localhost:4840");

        mockMvc.perform(post("/api/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/devices/device-to-delete"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/devices/device-to-delete"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectInvalidDevice() throws Exception {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        // missing id and endpointUrl

        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectDuplicateDevice() throws Exception {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("dup-device");
        dto.setEndpointUrl("opc.tcp://localhost:4840");

        mockMvc.perform(post("/api/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        // 第二次 POST 同 ID 应返回 409
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldUpdateDevice() throws Exception {
        DeviceConfigDTO dto = new DeviceConfigDTO();
        dto.setId("device-update");
        dto.setEndpointUrl("opc.tcp://localhost:4840");
        dto.setName("Old Name");

        mockMvc.perform(post("/api/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        dto.setName("New Name");
        mockMvc.perform(put("/api/devices/device-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/devices/device-update"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("New Name"));
    }
}
