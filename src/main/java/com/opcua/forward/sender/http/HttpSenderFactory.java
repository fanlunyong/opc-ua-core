package com.opcua.forward.sender.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class HttpSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public HttpSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        long timeoutMillis = target.getTimeoutMillis() > 0 ? target.getTimeoutMillis() : 5000L;
        RestTemplate rt = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(timeoutMillis))
                .setReadTimeout(Duration.ofMillis(timeoutMillis))
                .build();
        return new SharedConnection<>(rt, () -> { });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<RestTemplate> conn = (SharedConnection<RestTemplate>) connection;
        return new HttpSender("http-" + counter.incrementAndGet(), target, conn, objectMapper);
    }
}