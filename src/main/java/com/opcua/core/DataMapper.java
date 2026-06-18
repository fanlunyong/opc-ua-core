package com.opcua.core;

/**
 * 数据映射工具类。
 *
 * <p>负责 displayName 与 dataType 的解析回退策略：
 * <ul>
 *   <li>displayName：优先用配置值，缺失时回退到 nodeId</li>
 *   <li>dataType：优先用配置值，缺失时从实际值的 Class.simpleName 推断</li>
 * </ul>
 */
public final class DataMapper {

    private DataMapper() {
        // 工具类不实例化
    }

    /**
     * 解析 displayName：配置非空则使用，否则回退到 nodeId。
     */
    public static String resolveDisplayName(String configuredDisplayName, String nodeId) {
        if (configuredDisplayName != null && !configuredDisplayName.isEmpty()) {
            return configuredDisplayName;
        }
        return nodeId;
    }

    /**
     * 解析 dataType：配置非空则使用，否则从值类型推断（值为 null 时返回 null）。
     */
    public static String resolveDataType(String configuredDataType, Object value) {
        if (configuredDataType != null && !configuredDataType.isEmpty()) {
            return configuredDataType;
        }
        if (value == null) {
            return null;
        }
        return value.getClass().getSimpleName();
    }
}
