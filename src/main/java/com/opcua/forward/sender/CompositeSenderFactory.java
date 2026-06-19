package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

import java.util.Map;

/**
 * 按 ForwardTarget.type 分发到注册的 4 类 SenderFactory。
 */
public class CompositeSenderFactory implements SenderFactory {

    private final Map<String, SenderFactory> factories;

    public CompositeSenderFactory(Map<String, SenderFactory> factories) {
        this.factories = factories;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        return resolve(target.getType()).createConnection(fp, target);
    }

    @Override
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        return resolve(target.getType()).createSender(target, connection);
    }

    private SenderFactory resolve(String type) {
        SenderFactory f = factories.get(type);
        if (f == null) throw new IllegalArgumentException("No SenderFactory for type: " + type);
        return f;
    }
}