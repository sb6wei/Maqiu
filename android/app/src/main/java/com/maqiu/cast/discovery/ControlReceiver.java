package com.maqiu.cast.discovery;

import android.util.Log;

import com.maqiu.cast.Constants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class ControlReceiver extends Thread {
    public interface ControlListener {
        void onStartStream(String host, int rtpPort, int feedbackPort);
        void onStopStream();
    }

    private final ControlListener listener;
    private volatile boolean running = true;

    public ControlReceiver(ControlListener listener) {
        this.listener = listener;
    }

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket(Constants.CONTROL_PORT)) {
            byte[] buffer = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength());
                if (msg.startsWith("MAQIU_START|")) {
                    String[] parts = msg.split("\\|");
                    String host = packet.getAddress().getHostAddress();
                    int rtpPort = Constants.RTP_PORT;
                    int feedbackPort = Constants.FEEDBACK_PORT;
                    if (parts.length >= 4) {
                        host = parts[1];
                        rtpPort = parseInt(parts[2], rtpPort);
                        feedbackPort = parseInt(parts[3], feedbackPort);
                    }
                    listener.onStartStream(host, rtpPort, feedbackPort);
                } else if (msg.startsWith("MAQIU_STOP")) {
                    listener.onStopStream();
                }
            }
        } catch (Exception e) {
            Log.w("MaqiuControl", "Control receiver stopped", e);
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }
}
