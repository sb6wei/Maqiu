package com.maqiu.cast;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    private int projectionResultCode = Activity.RESULT_CANCELED;
    private Intent projectionData;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button btnGrant = findViewById(R.id.btnGrant);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);

        btnGrant.setOnClickListener(v -> requestPermission());
        btnStart.setOnClickListener(v -> startService());
        btnStop.setOnClickListener(v -> stopService());
    }

    private void requestPermission() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    private void startService() {
        if (projectionData == null || projectionResultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "Please grant screen capture permission first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, CastService.class);
        intent.setAction(CastService.ACTION_START_SERVICE);
        intent.putExtra(CastService.EXTRA_RESULT_CODE, projectionResultCode);
        intent.putExtra(CastService.EXTRA_RESULT_DATA, projectionData);
        startForegroundService(intent);
        statusText.setText("Status: service started, waiting for receiver");
    }

    private void stopService() {
        Intent intent = new Intent(this, CastService.class);
        intent.setAction(CastService.ACTION_STOP_SERVICE);
        startService(intent);
        statusText.setText("Status: service stopped");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK) {
                projectionResultCode = resultCode;
                projectionData = data;
                statusText.setText("Status: permission granted");
            } else {
                statusText.setText("Status: permission denied");
            }
        }
    }
}
