package com.opcua.api.dto;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void shouldCreateSuccessResponse() {
        ApiResponse<String> resp = ApiResponse.success("hello");

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getMessage()).isEqualTo("success");
        assertThat(resp.getData()).isEqualTo("hello");
        assertThat(resp.getTimestamp()).isPositive();
    }

    @Test
    void shouldCreateErrorResponse() {
        ApiResponse<Void> resp = ApiResponse.error(400, "deviceId is required");

        assertThat(resp.getCode()).isEqualTo(400);
        assertThat(resp.getMessage()).isEqualTo("deviceId is required");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void shouldCreateResponseWithCustomCode() {
        ApiResponse<Integer> resp = ApiResponse.of(201, "created", 42);

        assertThat(resp.getCode()).isEqualTo(201);
        assertThat(resp.getData()).isEqualTo(42);
    }
}
