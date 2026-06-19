package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaSenderTest {

    @Test
    void doSend_resolvesTopic_andSendsJsonValue() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        SharedConnection<KafkaProducer<String, String>> conn =
                new SharedConnection<>(producer, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setBootstrapServers("k:9092");
        target.setTopic("opcua-data-{productId}");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KafkaSender sender = new KafkaSender("kafka-1", target, conn, mapper);
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));
            // 等待 worker 处理（最多 500ms）
            verify(producer, timeout(500).times(1)).send(any(ProducerRecord.class));

            ArgumentCaptor<ProducerRecord<String, String>> rec = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(producer).send(rec.capture());

            assertEquals("opcua-data-line-A", rec.getValue().topic());
            assertEquals("furnace-1", rec.getValue().key());
            String json = rec.getValue().value();
            assertTrue(json.contains("\"productId\":\"line-A\""));
            assertTrue(json.contains("\"deviceId\":\"furnace-1\""));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                List.of(p));
    }
}