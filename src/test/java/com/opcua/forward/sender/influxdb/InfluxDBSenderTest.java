package com.opcua.forward.sender.influxdb;

import com.influxdb.client.WriteApi;
import com.influxdb.client.write.Point;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InfluxDBSenderTest {

    @Test
    void doSend_convertsAllDataPointsToWritePoints() throws Exception {
        WriteApi writeApi = mock(WriteApi.class);
        SharedConnection<WriteApi> conn = new SharedConnection<>(writeApi, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("influxdb");
        target.setUrl("http://i:8086");
        target.setOrg("o");
        target.setBucket("b");
        target.setToken("t");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        InfluxDBSender sender = new InfluxDBSender("influx-1", target, conn);
        try {
            Instant ts = Instant.parse("2026-06-18T00:00:00Z");
            OpcUaDeviceData data = new OpcUaDeviceData(
                    ts,
                    new OpcUaDeviceData.SourceInfo("line-A", "furnace-1", "opc.tcp://x"),
                    List.of(
                            point("ns=2;s=Temp", "Temperature", 80.5, Quality.Good, ts),
                            point("ns=2;s=Press", "Pressure", 1.2, Quality.Bad, ts)
                    )
            );
            sender.enqueue(data);

            verify(writeApi, timeout(500).times(1))
                    .writePoints(eq("b"), eq("o"), any(List.class));

            ArgumentCaptor<List<Point>> captor = ArgumentCaptor.forClass(List.class);
            verify(writeApi).writePoints(anyString(), anyString(), captor.capture());
            List<Point> points = captor.getValue();
            assertEquals(2, points.size());
            // Point 详细字段在 toLineProtocol 校验
            String l0 = points.get(0).toLineProtocol();
            assertTrue(l0.startsWith("Temperature,"));
            assertTrue(l0.contains("productId=line-A"));
            assertTrue(l0.contains("deviceId=furnace-1"));
            assertTrue(l0.contains("nodeId=ns\\=2;s\\=Temp"));
            assertTrue(l0.contains("quality=Good"), "l0=" + l0);
            assertTrue(l0.contains("value=80.5"));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDataPoint point(String nodeId, String name, Object v, Quality q, Instant ts) {
        return new OpcUaDataPoint(nodeId, name, v, "Double", q, true, q.name(), ts, ts);
    }
}