package com.maqiu.cast.encoder;

import java.util.concurrent.ArrayBlockingQueue;

public class FramePool {
    private final ArrayBlockingQueue<EncodedFrame> pool;
    private final BufferPool bufferPool;

    public FramePool(int poolSize, BufferPool bufferPool) {
        this.pool = new ArrayBlockingQueue<>(poolSize);
        this.bufferPool = bufferPool;
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new EncodedFrame());
        }
    }

    public EncodedFrame acquire() {
        EncodedFrame frame = pool.poll();
        if (frame == null) {
            frame = new EncodedFrame();
        }
        frame.data = bufferPool.acquire();
        frame.reset();
        return frame;
    }

    public void release(EncodedFrame frame) {
        if (frame == null) {
            return;
        }
        bufferPool.release(frame.data);
        frame.data = null;
        frame.reset();
        pool.offer(frame);
    }
}
