package com.opcua;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 测试常量定义。
 */
public final class TestConstants {

    private TestConstants() {
        // 工具类，禁止实例化
    }

    public static final String DEVICE_ID = "test-device-001";
    public static final String PRODUCT_ID = "test-product";
    public static final String ENDPOINT_URL = "opc.tcp://localhost:12685/milo";
    public static final String NODE_ID = "ns=2;s=Dynamic/RandomDouble";
    public static final String DISPLAY_NAME = "RandomDouble";

    public static final Instant TEST_TIMESTAMP = Instant.parse("2026-06-17T12:00:00Z")
            .truncatedTo(ChronoUnit.MILLIS);
}
