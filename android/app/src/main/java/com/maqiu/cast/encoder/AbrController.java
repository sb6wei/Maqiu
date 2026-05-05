package com.maqiu.cast.encoder;

import android.media.MediaCodec;
import android.os.Bundle;

import com.maqiu.cast.Constants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class AbrController {
    private final MediaCodec encoder;
    private volatile boolean running;
    private int targetKbps;
    private Thread feedbackThread;
    private long lastUpdateMs;

    public AbrController(MediaCodec encoder, int initialKbps) {
        this.encoder = encoder;
        this.targetKbps = initialKbps;
    }

    public void start(int feedbackPort) {
        running = true;
        feedbackThread = new Thread(() -> listen(feedbackPort), "AbrFeedback");
        feedbackThread.start();
    }

    public void stop() {
        running = false;
        if (feedbackThread != null) {
            feedbackThread.interrupt();
            feedbackThread = null;
        }
    }

    public int targetKbps() {
        return targetKbps;
    }

    private void listen(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(1000);
            byte[] buffer = new byte[128];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (Exception e) {
                    continue;
                }
                String msg = new String(packet.getData(), 0, packet.getLength());
                if (msg.startsWith("LOSS|")) {
                    int loss = parseInt(msg.substring(5));
                    handleLoss(loss);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void handleLoss(int lossPercent) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < 1000) {
            return;
        }
        lastUpdateMs = now;
        int newTarget = targetKbps;
        if (lossPercent > 10) {
            newTarget = Math.max(Constants.MIN_BITRATE_KBPS, targetKbps * 80 / 100);
        } else if (lossPercent < 2) {
            newTarget = Math.min(Constants.MAX_BITRATE_KBPS, targetKbps + 500);
        }
        if (newTarget != targetKbps) {
            targetKbps = newTarget;
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, targetKbps * 1000);
            try {
                encoder.setParameters(params);
            } catch (Exception ignored) {
            }
        }
    }
}
