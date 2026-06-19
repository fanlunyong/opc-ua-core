package com.opcua.forward.engine;

import com.opcua.forward.config.MatchCondition;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

/**
 * 转发规则匹配器。所有方法纯 CPU，禁 I/O。
 */
public final class RuleMatcher {

    private RuleMatcher() { }

    public static boolean matches(MatchCondition match, OpcUaDeviceData data) {
        if (match == null) return true;
        OpcUaDeviceData.SourceInfo s = data.getSource();

        if (notNullAndDifferent(match.getProductId(), s.getProductId())) return false;
        if (notNullAndDifferent(match.getDeviceId(), s.getDeviceId())) return false;

        if (match.getNodeId() != null && !match.getNodeId().isEmpty()) {
            boolean any = false;
            if (data.getData() != null) {
                for (OpcUaDataPoint p : data.getData()) {
                    if (match.getNodeId().equals(p.getNodeId())) { any = true; break; }
                }
            }
            if (!any) return false;
        }
        return true;
    }

    private static boolean notNullAndDifferent(String required, String actual) {
        return required != null && !required.isEmpty() && !required.equals(actual);
    }
}