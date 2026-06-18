package com.opcua.forward.sender;

import com.opcua.model.OpcUaDeviceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sender 抽象基类 — 队列 + worker drain + 生命周期。
 * 子类实现 {@link #doSend(OpcUaDeviceData)} 与 {@link #doClose()}。
 */
public abstract class AbstractSender implements Sender {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractSender.class);

    private final String senderId;
    private final int workerThreads;
    protected final LinkedBlockingQueue<OpcUaDeviceData> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(true);

    protected AbstractSender(String senderId, int queueCapacity, int workerThreads) {
        this.senderId = senderId;
        this.workerThreads = Math.max(1, workerThreads);
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
    }

    /** 子类构造完成后调用一次，启动 worker 线程 */
    protected final void start() {
        for (int i = 0; i < workerThreads; i++) {
            Thread t = new Thread(this::drainLoop, "sender-" + senderId + "-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    @Override public String getSenderId() { return senderId; }

    @Override
    public void enqueue(OpcUaDeviceData data) {
        if (!accepting.get()) return;
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
            onDropOldest(data);
        }
    }

    /** drop-oldest 钩子；Task 8 增加聚合 WARN 实现 */
    protected void onDropOldest(OpcUaDeviceData data) { /* default noop */ }

    @Override public void stopAccepting() { accepting.set(false); }

    @Override
    public int awaitDrain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!queue.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) break;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return queue.size();
    }

    @Override
    public void shutdown(Duration timeout) {
        stopAccepting();
        int leftover = awaitDrain(timeout);
        if (leftover > 0) {
            logger.warn("Sender {} shutdown timeout, {} messages dropped", senderId, leftover);
        }
        running.set(false);
        for (Thread t : workers) t.interrupt();
        try {
            doClose();
        } catch (Exception e) {
            logger.warn("Sender {} doClose error: {}", senderId, e.getMessage());
        }
    }

    protected abstract void doSend(OpcUaDeviceData data);
    protected abstract void doClose();

    private void drainLoop() {
        while (running.get()) {
            try {
                OpcUaDeviceData data = queue.take();
                try {
                    doSend(data);
                } catch (Exception e) {
                    logger.warn("Sender {} doSend error: {}", senderId, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) break;
            }
        }
    }
}
