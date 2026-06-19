package com.opcua.forward.util;

import com.opcua.model.OpcUaDeviceData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderResolverTest {

    @Test
    void resolves_productId_deviceId_inTemplate() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-data-line-A",
                PlaceholderResolver.resolve("opcua-data-{productId}", data));
        assertEquals("opcua/line-A/furnace-1",
                PlaceholderResolver.resolve("opcua/{productId}/{deviceId}", data));
    }

    @Test
    void noPlaceholder_returnsTemplateAsIs() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-static-topic",
                PlaceholderResolver.resolve("opcua-static-topic", data));
    }

    @Test
    void unknownPlaceholder_isLeftUnchanged() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-{unknown}",
                PlaceholderResolver.resolve("opcua-{unknown}", data));
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                Collections.emptyList());
    }
}