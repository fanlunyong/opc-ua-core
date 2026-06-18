package com.opcua.core;

import com.opcua.model.Quality;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据质量评估器。
 *
 * <p>负责将 OPC UA StatusCode 映射为统一的 {@link Quality} 枚举，
 * 并根据 qualityCheck 开关决定是否记录非 Good 数据的告警日志。</p>
 */
public final class QualityEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(QualityEvaluator.class);

    private QualityEvaluator() {
        // 工具类不实例化
    }

    /**
     * 将 StatusCode 映射为 Quality。null 视为 Uncertain。
     */
    public static Quality evaluate(StatusCode statusCode) {
        if (statusCode == null) {
            return Quality.Uncertain;
        }
        if (statusCode.isGood()) {
            return Quality.Good;
        }
        if (statusCode.isBad()) {
            return Quality.Bad;
        }
        return Quality.Uncertain;
    }

    /**
     * 当 qualityCheck=true 且 quality 非 Good 时输出 WARN 日志。
     */
    public static void logIfNeeded(Quality quality, boolean qualityCheck, String nodeId) {
        if (qualityCheck && quality != Quality.Good) {
            logger.warn("数据质量异常: nodeId={}, quality={}", nodeId, quality);
        }
    }
}
