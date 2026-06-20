package com.opcua.config;

import com.opcua.api.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldMapIllegalArgumentTo400() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleIllegalArgument(
                new IllegalArgumentException("id is required"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().getCode()).isEqualTo(400);
        assertThat(resp.getBody().getMessage()).isEqualTo("id is required");
    }

    @Test
    void shouldMapIllegalStateTo409() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleIllegalState(
                new IllegalStateException("Device already exists: d1"));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().getCode()).isEqualTo(409);
    }

    @Test
    void shouldMapGeneralExceptionTo500() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleGeneral(
                new RuntimeException("boom"));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().getCode()).isEqualTo(500);
        assertThat(resp.getBody().getMessage()).isEqualTo("Internal server error");
    }
}
