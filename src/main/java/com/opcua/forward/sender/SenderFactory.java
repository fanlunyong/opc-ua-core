package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

/**
 * Sender 与底层连接的工厂。Plan B 引入 4 个具体实现。
 */
public interface SenderFactory {

    /** 为 fingerprint 创建底层连接（HTTP 可返回 dummy SharedConnection<Void>） */
    SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target);

    /** 为 target 创建 Sender 实例（使用已 acquire 的共享连接） */
    Sender createSender(ForwardTarget target, SharedConnection<?> connection);
}
