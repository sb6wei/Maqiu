package com.maqiu.cast.discovery;

import android.os.Build;

import com.maqiu.cast.Constants;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class DiscoveryResponder extends Thread {
    private volatile boolean running = true;

    @Override
    public void run() {
        try (DatagramSocket socket = new DatagramSocket(Constants.DISCOVERY_PORT)) {
            socket.setBroadcast(true);
            byte[] buffer = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength());
                if ("MAQIU_DISCOVER".equals(msg)) {
                    String response = "MAQIU_DEVICE|" + Build.MODEL + "|" +
                            Constants.RTP_PORT + "|" + Constants.FEEDBACK_PORT + "|" + Constants.CONTROL_PORT;
                    byte[] data = response.getBytes();
                    InetAddress address = packet.getAddress();
                    DatagramPacket reply = new DatagramPacket(data, data.length, address, Constants.DISCOVERY_PORT);
                    socket.send(reply);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }
}
