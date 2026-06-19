package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sender 与共享连接注册表。
 *
 * <p>同一 ConnectionFingerprint 共享底层连接；同一 (fingerprint, target-key) 共享 Sender 实例。</p>
 */
public class SenderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SenderRegistry.class);

    private final SenderFactory factory;
    private final Map<ConnectionFingerprint, SharedConnection<?>> connections = new ConcurrentHashMap<>();
    private final Map<TargetKey, Sender> senders = new ConcurrentHashMap<>();
    private volatile boolean shutdown = false;

    public SenderRegistry(SenderFactory factory) {
        this.factory = factory;
    }

    public synchronized Sender getOrCreate(ForwardTarget target) {
        if (shutdown) throw new IllegalStateException("SenderRegistry already shut down");
        ConnectionFingerprint fp = ConnectionFingerprint.of(target);
        TargetKey key = TargetKey.of(target);

        Sender existing = senders.get(key);
        if (existing != null) return existing;

        SharedConnection<?> conn = connections.computeIfAbsent(fp, k -> factory.createConnection(k, target));
        conn.acquire();

        Sender s = factory.createSender(target, conn);
        senders.put(key, s);
        return s;
    }

    public void shutdown(Duration timeout) {
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
        }
        for (Sender s : senders.values()) {
            try { s.shutdown(timeout); }
            catch (Exception e) { logger.warn("Sender {} shutdown error: {}", s.getSenderId(), e.getMessage()); }
        }
        for (Map.Entry<ConnectionFingerprint, SharedConnection<?>> e : connections.entrySet()) {
            try {
                while (e.getValue().refCount() > 0) e.getValue().release();
            } catch (Exception ex) {
                logger.warn("Connection {} release error: {}", e.getKey(), ex.getMessage());
            }
        }
        senders.clear();
        connections.clear();
    }

    private record TargetKey(String type, String key) {
        static TargetKey of(ForwardTarget t) {
            String key = switch (t.getType()) {
                case "kafka" -> t.getBootstrapServers() + "|" + t.getTopic();
                case "mqtt" -> t.getBrokerUrl() + "|" + t.getClientId() + "|" + t.getTopic();
                case "influxdb" -> t.getUrl() + "|" + t.getOrg() + "|" + t.getBucket();
                case "http" -> Objects.requireNonNullElse(t.getUrl(), "");
                default -> throw new IllegalArgumentException("Unknown sender type: " + t.getType());
            };
            return new TargetKey(t.getType(), key);
        }
    }
}
