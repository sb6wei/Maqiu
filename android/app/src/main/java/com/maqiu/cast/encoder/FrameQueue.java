package com.maqiu.cast.encoder;

import java.util.ArrayDeque;

public class FrameQueue {
    private final ArrayDeque<EncodedFrame> queue = new ArrayDeque<>();
    private final int capacity;
    private final FramePool framePool;

    public FrameQueue(int capacity, FramePool framePool) {
        this.capacity = capacity;
        this.framePool = framePool;
    }

    public synchronized void offer(EncodedFrame frame) {
        if (queue.size() >= capacity) {
            EncodedFrame dropped = queue.pollFirst();
            framePool.release(dropped);
        }
        queue.offerLast(frame);
        notifyAll();
    }

    public synchronized EncodedFrame take(long timeoutMs) throws InterruptedException {
        if (queue.isEmpty()) {
            wait(timeoutMs);
        }
        return queue.pollFirst();
    }

    public synchronized void clear() {
        while (!queue.isEmpty()) {
            framePool.release(queue.pollFirst());
        }
    }
}
