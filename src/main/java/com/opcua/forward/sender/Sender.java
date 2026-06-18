package com.opcua.forward.sender;

import com.opcua.model.OpcUaDeviceData;

import java.time.Duration;

/**
 * 异步 Sender 抽象。线程安全。
 */
public interface Sender {

    /** 用于日志/聚合的标识 */
    String getSenderId();

    /** 入队（O(1)，drop-oldest）；stopAccepting 后入队会被静默丢弃 */
    void enqueue(OpcUaDeviceData data);

    /** 停止接收新数据（不停 worker） */
    void stopAccepting();

    /** 等待队列 drain；超时返回剩余消息数（>0 表示未排空） */
    int awaitDrain(Duration timeout);

    /** 完整关停：stopAccepting → awaitDrain → close（worker） */
    void shutdown(Duration timeout);
}
