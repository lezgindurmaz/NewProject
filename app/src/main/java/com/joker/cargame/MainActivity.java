package com.joker.cargame;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements GameView.OnGameEventListener, SurfaceHolder.Callback {

    private GameView gameView;
    private LinearLayout prankLayout;
    private ProgressBar prankProgress;
    private TextView prankStatus, prankMessage;
    private View cameraContainer;
    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private Vibrator vibrator;
    private Handler handler = new Handler();
    private int progressStatus = 0;

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        gameView = findViewById(R.id.gameView);
        prankLayout = findViewById(R.id.prankLayout);
        prankProgress = findViewById(R.id.prankProgress);
        prankStatus = findViewById(R.id.prankStatus);
        prankMessage = findViewById(R.id.prankMessage);
        cameraContainer = findViewById(R.id.cameraContainer);
        cameraPreview = findViewById(R.id.cameraPreview);

        gameView.setOnGameEventListener(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        requestPermissions();

        // Hide navigation bar and status bar
        hideSystemUI();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void requestPermissions() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        };

        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onPrankTrigger() {
        runOnUiThread(() -> {
            gameView.setVisibility(View.GONE);
            prankLayout.setVisibility(View.VISIBLE);
            startPrankSequence();
        });
    }

    private void startPrankSequence() {
        // Start fake progress
        new Thread(() -> {
            while (progressStatus < 100) {
                progressStatus += 1;
                handler.post(() -> {
                    prankProgress.setProgress(progressStatus);
                    prankStatus.setText("Dosyalar şifreleniyor: " + progressStatus + "%");

                    if (progressStatus == 15) {
                        prankMessage.setText("Konum servisleri aktif edildi...\nGPS verileri alınıyor.");
                    }
                    if (progressStatus == 30) {
                        Toast.makeText(MainActivity.this, "Kamera Erişimi Sağlandı", Toast.LENGTH_SHORT).show();
                        showCameraPreview();
                        prankMessage.setText("Yüz tanıma algoritması çalıştırılıyor...");
                    }
                    if (progressStatus == 50) {
                        prankMessage.setText("IP Adresi: 192.168.1.42 üzerinden bağlantı kuruldu.");
                    }
                    if (progressStatus == 70) {
                        prankMessage.setText("Tüm mesajlar ve galeri kopyalanıyor...");
                    }
                    if (progressStatus == 85) {
                        prankMessage.setText("Sistem dosyaları silinmeye başlandı!");
                    }

                    if (progressStatus % 10 == 0) {
                        vibrate(500);
                    }
                });
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            handler.post(() -> {
                prankStatus.setText("TÜM DOSYALAR ŞİFRELENDİ VE BULUTA YÜKLENDİ.");
                vibrate(2000);
            });
        }).start();
    }

    private void showCameraPreview() {
        cameraContainer.setVisibility(View.VISIBLE);
        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);
    }

    private void vibrate(long duration) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(duration);
        }
    }

    @Override
    public void onBackPressed() {
        // Do nothing to prevent exit
        Toast.makeText(this, "SİSTEM KİLİTLENDİ. ÇIKIŞ YAPILAMAZ.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                camera = Camera.open();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }
}
