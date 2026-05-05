package com.maqiu.cast.encoder;

import java.util.concurrent.ArrayBlockingQueue;

public class BufferPool {
    private final ArrayBlockingQueue<byte[]> pool;
    private final int bufferSize;

    public BufferPool(int poolSize, int bufferSize) {
        this.pool = new ArrayBlockingQueue<>(poolSize);
        this.bufferSize = bufferSize;
        for (int i = 0; i < poolSize; i++) {
            pool.offer(new byte[bufferSize]);
        }
    }

    public byte[] acquire() {
        byte[] buffer = pool.poll();
        if (buffer == null || buffer.length != bufferSize) {
            return new byte[bufferSize];
        }
        return buffer;
    }

    public void release(byte[] buffer) {
        if (buffer == null || buffer.length != bufferSize) {
            return;
        }
        pool.offer(buffer);
    }

    public int bufferSize() {
        return bufferSize;
    }
}
