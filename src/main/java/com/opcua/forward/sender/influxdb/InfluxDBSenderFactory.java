package com.opcua.forward.sender.influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;

import java.util.concurrent.atomic.AtomicLong;

public class InfluxDBSenderFactory implements SenderFactory {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        InfluxDBClient client = InfluxDBClientFactory.create(
                target.getUrl(), target.getToken().toCharArray(), target.getOrg(), target.getBucket());
        WriteApi writeApi = client.makeWriteApi();
        return new SharedConnection<>(writeApi, () -> {
            try { writeApi.close(); } catch (Exception ignored) { }
            try { client.close(); } catch (Exception ignored) { }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<WriteApi> conn = (SharedConnection<WriteApi>) connection;
        return new InfluxDBSender("influxdb-" + counter.incrementAndGet(), target, conn);
    }
}