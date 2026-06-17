package com.opcua.model;

import com.opcua.TestConstants;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpcUaDeviceData 构造与数据完整性测试。
 */
class OpcUaDeviceDataTest {

    @Test
    void shouldConstructWithAllFields() {
        Instant now = Instant.now();
        OpcUaDataPoint point = new OpcUaDataPoint(
                TestConstants.NODE_ID,
                TestConstants.DISPLAY_NAME,
                42.5,
                "Double",
                Quality.Good,
                true,
                "0x00000000",
                now,
                now
        );

        OpcUaDeviceData deviceData = new OpcUaDeviceData(
                now,
                new OpcUaDeviceData.SourceInfo(
                        TestConstants.PRODUCT_ID,
                        TestConstants.DEVICE_ID,
                        TestConstants.ENDPOINT_URL
                ),
                List.of(point)
        );

        assertThat(deviceData.getTimestamp()).isEqualTo(now);
        assertThat(deviceData.getSource().getProductId()).isEqualTo(TestConstants.PRODUCT_ID);
        assertThat(deviceData.getSource().getDeviceId()).isEqualTo(TestConstants.DEVICE_ID);
        assertThat(deviceData.getSource().getEndpointUrl()).isEqualTo(TestConstants.ENDPOINT_URL);
        assertThat(deviceData.getData()).hasSize(1);

        OpcUaDataPoint actualPoint = deviceData.getData().get(0);
        assertThat(actualPoint.getNodeId()).isEqualTo(TestConstants.NODE_ID);
        assertThat(actualPoint.getDisplayName()).isEqualTo(TestConstants.DISPLAY_NAME);
        assertThat(actualPoint.getValue()).isEqualTo(42.5);
        assertThat(actualPoint.getDataType()).isEqualTo("Double");
        assertThat(actualPoint.getQuality()).isEqualTo(Quality.Good);
        assertThat(actualPoint.getStatusCode()).isEqualTo("0x00000000");
        assertThat(actualPoint.getSourceTimestamp()).isEqualTo(now);
        assertThat(actualPoint.getServerTimestamp()).isEqualTo(now);
    }

    @Test
    void shouldSupportNullValue() {
        Instant now = Instant.now();
        OpcUaDataPoint point = new OpcUaDataPoint(
                TestConstants.NODE_ID,
                TestConstants.DISPLAY_NAME,
                null,
                "String",
                Quality.Uncertain,
                false,
                "0x80000000",
                now,
                now
        );

        OpcUaDeviceData deviceData = new OpcUaDeviceData(
                now,
                new OpcUaDeviceData.SourceInfo("p1", "d1", "opc.tcp://localhost:4840"),
                List.of(point)
        );

        assertThat(deviceData.getData().get(0).getValue()).isNull();
        assertThat(deviceData.getData().get(0).getQuality()).isEqualTo(Quality.Uncertain);
    }
}
