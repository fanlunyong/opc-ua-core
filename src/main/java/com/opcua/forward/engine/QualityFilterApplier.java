package com.opcua.forward.engine;

import com.opcua.forward.config.QualityFilter;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 ForwardRule 的 QualityFilter 过滤数据点。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>若 filter 为 null，按 dropBadOnly 默认（兼容历史规则）</li>
 *   <li>若 filter.shouldDrop() 全部返回 false，原样返回（无 GC 开销）</li>
 *   <li>若全部数据点被丢弃，返回 null —— 调用方应跳过 enqueue</li>
 *   <li>否则以原 sourceInfo + 过滤后 dataPoints 构造新的 OpcUaDeviceData</li>
 * </ul>
 */
public final class QualityFilterApplier {

    private QualityFilterApplier() {}

    public static OpcUaDeviceData apply(OpcUaDeviceData data, QualityFilter filter) {
        QualityFilter f = filter != null ? filter : QualityFilter.dropBadOnly();

        // 快路径：filter 不丢任何东西
        if (!f.isDropBad() && !f.isDropUncertain()) {
            return data;
        }

        List<OpcUaDataPoint> kept = new ArrayList<>(data.getData().size());
        for (OpcUaDataPoint p : data.getData()) {
            if (!f.shouldDrop(p.getQuality())) {
                kept.add(p);
            }
        }

        if (kept.isEmpty()) return null;
        if (kept.size() == data.getData().size()) return data;  // 没有真正丢弃

        return new OpcUaDeviceData(data.getTimestamp(), data.getSource(), kept);
    }
}