package com.opcua.forward.util;

import com.opcua.model.OpcUaDeviceData;

/**
 * 转发模板占位符替换。当前支持 {productId} 和 {deviceId}。
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() { }

    public static String resolve(String template, OpcUaDeviceData data) {
        if (template == null || template.isEmpty()) return template;
        OpcUaDeviceData.SourceInfo s = data.getSource();
        String r = template;
        if (s.getProductId() != null) r = r.replace("{productId}", s.getProductId());
        if (s.getDeviceId() != null) r = r.replace("{deviceId}", s.getDeviceId());
        return r;
    }
}