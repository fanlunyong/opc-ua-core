package com.opcua.forward.sender.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HttpSenderTest {

    @Test
    void doSend_postsJsonToConfiguredUrl() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        SharedConnection<RestTemplate> conn = new SharedConnection<>(rest, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("http");
        target.setUrl("http://alert/notify");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        HttpSender sender = new HttpSender("http-1", target, conn,
                new ObjectMapper().findAndRegisterModules());
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));

            ArgumentCaptor<URI> uriCap = ArgumentCaptor.forClass(URI.class);
            ArgumentCaptor<HttpEntity<?>> entCap = ArgumentCaptor.forClass(HttpEntity.class);
            verify(rest, timeout(500)).exchange(
                    uriCap.capture(), eq(HttpMethod.POST), entCap.capture(), eq(String.class));

            assertEquals("http://alert/notify", uriCap.getValue().toString());
            assertEquals(MediaType.APPLICATION_JSON, entCap.getValue().getHeaders().getContentType());
            String body = entCap.getValue().getBody().toString();
            assertTrue(body.contains("\"productId\":\"line-A\""));
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