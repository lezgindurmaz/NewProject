package com.joker.cargame;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
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

public class MainActivity extends AppCompatActivity implements GameView.OnGameEventListener, SurfaceHolder.Callback {

    private GameView gameView;
    private LinearLayout prankLayout;
    private View jumpscareLayout;
    private ProgressBar prankProgress;
    private TextView prankStatus, prankMessage, jumpscareText;
    private View cameraContainer;
    private SurfaceView cameraPreview;
    private SurfaceHolder surfaceHolder;
    private Camera camera;
    private Vibrator vibrator;
    private Handler handler = new Handler();
    private int progressStatus = 0;
    private boolean shouldShowCamera = false;

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        gameView = findViewById(R.id.gameView);
        prankLayout = findViewById(R.id.prankLayout);
        jumpscareLayout = findViewById(R.id.jumpscareLayout);
        prankProgress = findViewById(R.id.prankProgress);
        prankStatus = findViewById(R.id.prankStatus);
        prankMessage = findViewById(R.id.prankMessage);
        cameraContainer = findViewById(R.id.cameraContainer);
        cameraPreview = findViewById(R.id.cameraPreview);
        jumpscareText = findViewById(R.id.jumpscareText);

        gameView.setOnGameEventListener(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        surfaceHolder = cameraPreview.getHolder();
        surfaceHolder.addCallback(this);

        requestPermissions();
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

    @Override
    public void onGameOver() {
        triggerJumpscare();
    }

    private void startPrankSequence() {
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
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            handler.post(() -> {
                prankStatus.setText("TÜM DOSYALAR ŞİFRELENDİ VE BULUTA YÜKLENDİ.");
                vibrate(2000);
                handler.postDelayed(this::triggerJumpscare, 1000);
            });
        }).start();
    }

    private void triggerJumpscare() {
        runOnUiThread(() -> {
            gameView.setVisibility(View.GONE);
            prankLayout.setVisibility(View.GONE);
            jumpscareLayout.setVisibility(View.VISIBLE);

            new Thread(() -> {
                String[] scaryTexts = {"HACKED", "IZLENIYORSUN", "GEÇMİŞ OLSUN", "RUHUNU SATIN ALDIM"};
                for (int i = 0; i < 40; i++) {
                    final int color = (i % 2 == 0) ? Color.RED : (i % 3 == 0 ? Color.WHITE : Color.BLACK);
                    final String text = scaryTexts[i % scaryTexts.length];
                    handler.post(() -> {
                        jumpscareLayout.setBackgroundColor(color);
                        jumpscareText.setText(text);
                        jumpscareText.setTextColor(color == Color.RED ? Color.BLACK : Color.RED);
                    });
                    vibrate(50);
                    try { Thread.sleep(70); } catch (InterruptedException e) {}
                }
                handler.post(() -> {
                    finishAffinity();
                    System.exit(0);
                });
            }).start();
        });
    }

    private void showCameraPreview() {
        runOnUiThread(() -> {
            shouldShowCamera = true;
            cameraContainer.setVisibility(View.VISIBLE);
            if (surfaceHolder.getSurface().isValid()) {
                startCamera(surfaceHolder);
            }
        });
    }

    private void vibrate(long duration) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(duration);
        }
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "SİSTEM KİLİTLENDİ. ÇIKIŞ YAPILAMAZ.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (shouldShowCamera) {
            startCamera(holder);
        }
    }

    private void startCamera(SurfaceHolder holder) {
        if (camera != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                camera = openFrontCamera();
                if (camera != null) {
                    camera.setDisplayOrientation(90);
                    camera.setPreviewDisplay(holder);
                    camera.startPreview();
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Kamera hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private Camera openFrontCamera() {
        int cameraCount = Camera.getNumberOfCameras();
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0; i < cameraCount; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return Camera.open(i);
            }
        }
        return Camera.open(0);
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
