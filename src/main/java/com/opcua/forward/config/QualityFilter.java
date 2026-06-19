package com.opcua.forward.config;

import com.opcua.model.Quality;

/**
 * 数据点质量过滤策略，应用于 ForwardingEngine.onDataReceived 路由前。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>dropBad=true（默认）→ Bad 质量数据不被转发</li>
 *   <li>dropUncertain=true → Uncertain 质量数据不被转发</li>
 *   <li>Good 质量数据始终转发</li>
 * </ul>
 *
 * <p>OPC UA 协议的 StatusCode 子状态信息（如 BadConnectionClosed、
 * UncertainLastUsableValue）通过 OpcUaDataPoint.statusCode hex 字段保留，
 * 由具体 Sender 在序列化时透传给下游消费者。</p>
 */
public class QualityFilter {

    private boolean dropBad = true;
    private boolean dropUncertain = false;

    public static QualityFilter dropBadOnly() {
        QualityFilter f = new QualityFilter();
        f.dropBad = true;
        f.dropUncertain = false;
        return f;
    }

    public static QualityFilter passAll() {
        QualityFilter f = new QualityFilter();
        f.dropBad = false;
        f.dropUncertain = false;
        return f;
    }

    /** Returns true 表示该数据点应被丢弃（不路由到 sender）。 */
    public boolean shouldDrop(Quality quality) {
        if (quality == null) return dropUncertain;  // null 视为 Uncertain
        return switch (quality) {
            case Good -> false;
            case Bad -> dropBad;
            case Uncertain -> dropUncertain;
        };
    }

    public boolean isDropBad() { return dropBad; }
    public void setDropBad(boolean v) { this.dropBad = v; }
    public boolean isDropUncertain() { return dropUncertain; }
    public void setDropUncertain(boolean v) { this.dropUncertain = v; }
}
