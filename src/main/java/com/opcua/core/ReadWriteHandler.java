package com.opcua.core;

import com.opcua.api.WriteResult;
import com.opcua.model.DeviceConfig;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.PollingNodeConfig;
import com.opcua.model.Quality;
import com.opcua.wrapper.MiloClientWrapper;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据轮询与写入处理器。
 *
 * <p>负责按配置间隔定期读取节点值并经 {@link DataDispatchEngine} 分发，
 * 以及处理写入请求。单节点轮询失败不会影响其他节点。</p>
 */
public class ReadWriteHandler {

    private static final Logger logger = LoggerFactory.getLogger(ReadWriteHandler.class);

    private final DataDispatchEngine dispatchEngine;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, List<ScheduledFuture<?>>> deviceTasks = new ConcurrentHashMap<>();

    public ReadWriteHandler(DataDispatchEngine dispatchEngine) {
        this.dispatchEngine = dispatchEngine;
        AtomicInteger counter = new AtomicInteger();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "opcua-poll-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 为指定设备启动轮询：每个 PollingNodeConfig 对应一个独立的定时任务。
     */
    public void startPolling(MiloClientWrapper wrapper, DeviceConfig config) {
        List<PollingNodeConfig> nodes = config.getPolling();
        if (nodes == null || nodes.isEmpty()) {
            logger.info("无轮询节点配置: deviceId={}", config.getDeviceId());
            return;
        }

        OpcUaClient client = wrapper.getClient();
        String deviceId = config.getDeviceId();
        String productId = config.getProductId();
        String endpointUrl = config.getEndpointUrl();

        List<ScheduledFuture<?>> futures = new ArrayList<>(nodes.size());
        for (PollingNodeConfig nodeConfig : nodes) {
            int interval = nodeConfig.getInterval();
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                    () -> pollNode(client, deviceId, productId, endpointUrl, nodeConfig),
                    0L,
                    Math.max(1, interval),
                    TimeUnit.MILLISECONDS
            );
            futures.add(future);
        }
        deviceTasks.put(deviceId, futures);
        logger.info("启动轮询: deviceId={}, 节点数={}", deviceId, nodes.size());
    }

    /**
     * 停止指定设备的所有轮询任务。
     */
    public void stopPolling(String deviceId) {
        List<ScheduledFuture<?>> futures = deviceTasks.remove(deviceId);
        if (futures == null) {
            return;
        }
        for (ScheduledFuture<?> future : futures) {
            future.cancel(false);
        }
        logger.info("停止轮询: deviceId={}, 任务数={}", deviceId, futures.size());
    }

    /**
     * 读取单个节点并通过 dispatchEngine 分发。
     * 异常时记录日志，不抛出，以保证其他节点轮询不中断。
     */
    void pollNode(OpcUaClient client, String deviceId, String productId, String endpointUrl,
                  PollingNodeConfig nodeConfig) {
        try {
            NodeId nodeId = NodeId.parse(nodeConfig.getNodeId());
            DataValue dataValue = client.readValue(0, TimestampsToReturn.Both, nodeId).get();

            OpcUaDataPoint dataPoint = buildDataPoint(nodeConfig, dataValue);
            QualityEvaluator.logIfNeeded(dataPoint.getQuality(), nodeConfig.isQualityCheck(),
                    nodeConfig.getNodeId());

            OpcUaDeviceData.SourceInfo sourceInfo = new OpcUaDeviceData.SourceInfo(
                    productId, deviceId, endpointUrl);
            Instant timestamp = dataPoint.getSourceTimestamp() != null
                    ? dataPoint.getSourceTimestamp()
                    : Instant.now();

            OpcUaDeviceData deviceData = new OpcUaDeviceData(
                    timestamp, sourceInfo, Collections.singletonList(dataPoint));
            dispatchEngine.dispatch(deviceData);
        } catch (Exception e) {
            logger.error("轮询节点失败: deviceId={}, nodeId={}, error={}",
                    deviceId, nodeConfig.getNodeId(), e.getMessage());
        }
    }

    /**
     * 写入指定节点的值。
     */
    public WriteResult writeValue(OpcUaClient client, String nodeIdStr, Object value) {
        try {
            NodeId nodeId = NodeId.parse(nodeIdStr);
            DataValue dataValue = new DataValue(new Variant(value));
            StatusCode statusCode = client.writeValue(nodeId, dataValue).get();
            if (statusCode.isGood()) {
                return WriteResult.success(nodeIdStr);
            }
            return WriteResult.failure(nodeIdStr, "写入失败: statusCode=" + statusCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WriteResult.failure(nodeIdStr, "写入被中断: " + e.getMessage());
        } catch (Exception e) {
            return WriteResult.failure(nodeIdStr, "写入异常: "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    /**
     * 关闭调度器，取消所有进行中的任务。
     */
    public void shutdown() {
        deviceTasks.clear();
        scheduler.shutdownNow();
    }

    /**
     * 将 PollingNodeConfig + DataValue 转换为 OpcUaDataPoint，
     * 复用 QualityEvaluator 与 DataMapper 的解析策略。
     */
    private static OpcUaDataPoint buildDataPoint(PollingNodeConfig nodeConfig, DataValue dataValue) {
        StatusCode statusCode = dataValue.getStatusCode();
        Quality quality = QualityEvaluator.evaluate(statusCode);

        String statusCodeHex = statusCode != null
                ? "0x" + Long.toHexString(statusCode.getValue())
                : null;

        Object value = null;
        if (dataValue.getValue() != null) {
            value = dataValue.getValue().getValue();
        }

        String displayName = DataMapper.resolveDisplayName(nodeConfig.getDisplayName(),
                nodeConfig.getNodeId());
        String dataType = DataMapper.resolveDataType(nodeConfig.getDataType(), value);

        Instant sourceTs = null;
        if (dataValue.getSourceTime() != null && dataValue.getSourceTime().getJavaDate() != null) {
            sourceTs = dataValue.getSourceTime().getJavaDate().toInstant();
        }
        Instant serverTs = null;
        if (dataValue.getServerTime() != null && dataValue.getServerTime().getJavaDate() != null) {
            serverTs = dataValue.getServerTime().getJavaDate().toInstant();
        }

        return new OpcUaDataPoint(
                nodeConfig.getNodeId(),
                displayName,
                value,
                dataType,
                quality,
                nodeConfig.isQualityCheck(),
                statusCodeHex,
                sourceTs,
                serverTs
        );
    }
}
