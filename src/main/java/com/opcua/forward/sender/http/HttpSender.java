package com.opcua.forward.sender.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

public class HttpSender extends AbstractSender {

    private final SharedConnection<RestTemplate> connection;
    private final String urlTemplate;
    private final ObjectMapper objectMapper;

    public HttpSender(String senderId, ForwardTarget target,
                      SharedConnection<RestTemplate> connection, ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.urlTemplate = target.getUrl();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String url = PlaceholderResolver.resolve(urlTemplate, data);
        String body;
        try {
            body = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON error: {}", getSenderId(), e.getMessage());
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        try {
            connection.get().exchange(URI.create(url), HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            logger.warn("Sender {} POST {} error: {}", getSenderId(), url, e.getMessage());
        }
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}