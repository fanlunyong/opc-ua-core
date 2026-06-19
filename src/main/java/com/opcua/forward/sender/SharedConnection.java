package com.opcua.forward.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 引用计数包装的共享底层连接。
 *
 * <p>每个 sender 调用 {@link #acquire()} 持有一份引用，
 * shutdown 时调用 {@link #release()}；最后一个 release 触发底层 closer。</p>
 */
public final class SharedConnection<T> {

    private static final Logger logger = LoggerFactory.getLogger(SharedConnection.class);

    private final T underlying;
    private final Runnable closer;
    private final AtomicInteger refs = new AtomicInteger();

    public SharedConnection(T underlying, Runnable closer) {
        this.underlying = underlying;
        this.closer = closer;
    }

    public T get() { return underlying; }

    public void acquire() { refs.incrementAndGet(); }

    /** 计数归零时调用 closer；closer 异常被吞下并 WARN，不中断 release */
    public void release() {
        int after = refs.decrementAndGet();
        if (after < 0) {
            refs.incrementAndGet(); // 回滚
            throw new IllegalStateException("SharedConnection.release without acquire");
        }
        if (after == 0) {
            try { closer.run(); }
            catch (Exception e) { logger.warn("SharedConnection close error: {}", e.getMessage()); }
        }
    }

    public int refCount() { return refs.get(); }
}
