package com.opcua.forward.engine;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;

public final class AlertDetector {

    private AlertDetector() { }

    public static boolean hasBadOrUncertain(OpcUaDeviceData data) {
        if (data == null || data.getData() == null) return false;
        for (OpcUaDataPoint p : data.getData()) {
            Quality q = p.getQuality();
            if (q == Quality.Bad || q == Quality.Uncertain) return true;
        }
        return false;
    }
}