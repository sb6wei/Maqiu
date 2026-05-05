package com.maqiu.cast.encoder;

public class Pacer {
    private volatile int targetKbps;
    private long nextSendNs;

    public Pacer(int initialKbps) {
        this.targetKbps = initialKbps;
    }

    public void setTargetKbps(int kbps) {
        this.targetKbps = Math.max(1000, kbps);
    }

    public void pace(int bytes) {
        if (bytes <= 0) {
            return;
        }
        long now = System.nanoTime();
        if (nextSendNs == 0) {
            nextSendNs = now;
        }
        long bits = (long) bytes * 8L;
        long durationNs = bits * 1_000_000_000L / (targetKbps * 1000L);
        nextSendNs += durationNs;
        long sleepNs = nextSendNs - now;
        if (sleepNs > 0) {
            try {
                Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
