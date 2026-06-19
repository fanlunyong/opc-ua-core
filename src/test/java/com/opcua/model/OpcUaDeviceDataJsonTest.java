package com.opcua.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.TestConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpcUaDeviceData JSON 序列化测试（覆盖 OpenSpec 7.2）。
 */
@DisplayName("OpcUaDeviceData JSON")
class OpcUaDeviceDataJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @Test
    @DisplayName("应序列化为包含 timestamp、source、data[] 的 JSON")
    void shouldSerializeToExpectedShape() throws Exception {
        OpcUaDataPoint p1 = new OpcUaDataPoint(
                "ns=2;s=Temperature", "温度",
                42.5, "Float", Quality.Good, true,
                "0x0",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z")
        );
        OpcUaDataPoint p2 = new OpcUaDataPoint(
                "ns=2;s=Pressure", "压力",
                1013.25, "Float", Quality.Good, true,
                "0x0",
                Instant.parse("2026-06-18T10:00:00Z"),
                Instant.parse("2026-06-18T10:00:01Z")
        );

        OpcUaDeviceData.SourceInfo source = new OpcUaDeviceData.SourceInfo(
                TestConstants.PRODUCT_ID, TestConstants.DEVICE_ID, TestConstants.ENDPOINT_URL);

        OpcUaDeviceData data = new OpcUaDeviceData(
                Instant.parse("2026-06-18T10:00:00Z"), source, List.of(p1, p2));

        String json = objectMapper.writeValueAsString(data);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("timestamp")).isTrue();
        assertThat(node.has("source")).isTrue();
        assertThat(node.has("data")).isTrue();
        assertThat(node.get("source").get("deviceId").asText()).isEqualTo(TestConstants.DEVICE_ID);
        assertThat(node.get("source").get("productId").asText()).isEqualTo(TestConstants.PRODUCT_ID);
        assertThat(node.get("data").isArray()).isTrue();
        assertThat(node.get("data")).hasSize(2);
        assertThat(node.get("data").get(0).get("value").asDouble()).isEqualTo(42.5);
        assertThat(node.get("data").get(0).get("displayName").asText()).isEqualTo("温度");
        assertThat(node.get("data").get(0).get("quality").asText()).isEqualTo("Good");
        assertThat(node.get("data").get(1).get("value").asDouble()).isEqualTo(1013.25);
    }

    @Test
    @DisplayName("空 data[] 也应正确序列化")
    void shouldHandleEmptyData() throws Exception {
        OpcUaDeviceData.SourceInfo source = new OpcUaDeviceData.SourceInfo(
                TestConstants.PRODUCT_ID, TestConstants.DEVICE_ID, TestConstants.ENDPOINT_URL);
        OpcUaDeviceData data = new OpcUaDeviceData(Instant.now(), source, List.of());

        String json = objectMapper.writeValueAsString(data);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("data").isArray()).isTrue();
        assertThat(node.get("data")).hasSize(0);
    }
}
