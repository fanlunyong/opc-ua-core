package com.opcua.forward.sender.influxdb;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class InfluxDBSender extends AbstractSender {

    private final SharedConnection<WriteApi> connection;
    private final String bucket;
    private final String org;

    public InfluxDBSender(String senderId, ForwardTarget target, SharedConnection<WriteApi> connection) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.bucket = target.getBucket();
        this.org = target.getOrg();
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        if (data.getData() == null || data.getData().isEmpty()) return;
        List<Point> points = new ArrayList<>(data.getData().size());
        OpcUaDeviceData.SourceInfo s = data.getSource();
        for (OpcUaDataPoint p : data.getData()) {
            // 时间戳 fallback 链：sourceTimestamp → serverTimestamp → batch timestamp → now
            Instant ts = p.getSourceTimestamp();
            if (ts == null) ts = p.getServerTimestamp();
            if (ts == null) ts = data.getTimestamp();
            if (ts == null) ts = Instant.now();

            Point pt = Point.measurement(p.getDisplayName() != null ? p.getDisplayName() : p.getNodeId())
                    .addTag("productId", safe(s.getProductId()))
                    .addTag("deviceId", safe(s.getDeviceId()))
                    .addTag("nodeId", safe(p.getNodeId()))
                    .addTag("quality", p.getQuality() == null ? "" : p.getQuality().name())
                    .addTag("statusCode", safe(p.getStatusCode()))
                    .time(ts, WritePrecision.NS);
            // 保留 server 时间戳作为 field（便于排查 source/server 时钟漂移）
            if (p.getServerTimestamp() != null) {
                pt.addField("serverTimestampNs", p.getServerTimestamp().toEpochMilli() * 1_000_000L);
            }
            addValueField(pt, p.getValue());
            points.add(pt);
        }
        connection.get().writePoints(bucket, org, points);
    }

    private static void addValueField(Point pt, Object v) {
        if (v == null) {
            pt.addField("value", "");
            return;
        }
        if (v instanceof Number n) pt.addField("value", n.doubleValue());
        else if (v instanceof Boolean b) pt.addField("value", b);
        else pt.addField("value", v.toString());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override
    protected void doClose() {
        connection.release();
    }
}