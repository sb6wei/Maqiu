package com.maqiu.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.maqiu.cast.discovery.ControlReceiver;
import com.maqiu.cast.discovery.DiscoveryResponder;
import com.maqiu.cast.encoder.EncoderController;

public class CastService extends Service implements ControlReceiver.ControlListener {
    public static final String ACTION_START_SERVICE = "com.maqiu.cast.action.START";
    public static final String ACTION_STOP_SERVICE = "com.maqiu.cast.action.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "MaqiuCast";
    private static final int NOTIFICATION_ID = 1001;

    private MediaProjection projection;
    private EncoderController encoderController;
    private DiscoveryResponder discoveryResponder;
    private ControlReceiver controlReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_START_SERVICE.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startForeground(NOTIFICATION_ID, buildNotification());
            startProjection(resultCode, data);
            startDiscovery();
            startControl();
        } else if (ACTION_STOP_SERVICE.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            shutdown();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        if (projection != null || data == null) {
            return;
        }
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);
        encoderController = new EncoderController(this, projection);
    }

    private void startDiscovery() {
        if (discoveryResponder == null) {
            discoveryResponder = new DiscoveryResponder();
            discoveryResponder.start();
        }
    }

    private void startControl() {
        if (controlReceiver == null) {
            controlReceiver = new ControlReceiver(this);
            controlReceiver.start();
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Maqiu Cast")
                .setContentText("Waiting for receiver")
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Maqiu Cast", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void shutdown() {
        if (controlReceiver != null) {
            controlReceiver.shutdown();
            controlReceiver = null;
        }
        if (discoveryResponder != null) {
            discoveryResponder.shutdown();
            discoveryResponder = null;
        }
        if (encoderController != null) {
            encoderController.stopStreaming();
            encoderController = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStartStream(String host, int rtpPort, int feedbackPort) {
        if (encoderController != null) {
            encoderController.startStreaming(host, rtpPort, feedbackPort);
        }
    }

    @Override
    public void onStopStream() {
        if (encoderController != null) {
            encoderController.stopStreaming();
        }
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }
}
