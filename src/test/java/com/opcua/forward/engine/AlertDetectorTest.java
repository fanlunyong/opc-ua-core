package com.opcua.forward.engine;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertDetectorTest {

    @Test
    void allGood_returnsFalse() {
        assertFalse(AlertDetector.hasBadOrUncertain(data(Quality.Good, Quality.Good)));
    }

    @Test
    void anyBad_returnsTrue() {
        assertTrue(AlertDetector.hasBadOrUncertain(data(Quality.Good, Quality.Bad)));
    }

    @Test
    void anyUncertain_returnsTrue() {
        assertTrue(AlertDetector.hasBadOrUncertain(data(Quality.Uncertain, Quality.Good)));
    }

    @Test
    void emptyData_returnsFalse() {
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "x"),
                List.of());
        assertFalse(AlertDetector.hasBadOrUncertain(d));
    }

    private OpcUaDeviceData data(Quality... qs) {
        OpcUaDataPoint[] arr = new OpcUaDataPoint[qs.length];
        for (int i = 0; i < qs.length; i++) {
            arr[i] = new OpcUaDataPoint("n" + i, "n", 1, "Int32",
                    qs[i], true, qs[i].name(), Instant.now(), Instant.now());
        }
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "x"),
                List.of(arr));
    }
}