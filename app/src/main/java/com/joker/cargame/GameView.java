package com.joker.cargame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class GameView extends View {
    private Paint playerPaint, obstaclePaint, roadPaint, textPaint, grassPaint, linePaint;
    private int playerX, playerY, playerWidth, playerHeight;
    private int obstacleWidth, obstacleHeight;
    private int screenWidth, screenHeight;
    private List<Rect> obstacles;
    private int score = 50; // Increased to prevent immediate game over
    private int level = 1;
    private final int MAX_LEVEL = 10;
    private boolean prankTriggered = false;
    private boolean isGameOver = false;
    private Random random;
    private long startTime;
    private OnGameEventListener listener;
    private int offset = 0;
    private int speed = 15;

    private int roadWidth;
    private int roadLeft;
    private int laneWidth;
    private int numLanes;

    public interface OnGameEventListener {
        void onPrankTrigger();
        void onGameOver();
    }

    public void setOnGameEventListener(OnGameEventListener listener) {
        this.listener = listener;
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        playerPaint = new Paint();
        playerPaint.setColor(Color.parseColor("#E91E63"));

        obstaclePaint = new Paint();
        obstaclePaint.setColor(Color.parseColor("#3F51B5"));

        roadPaint = new Paint();
        roadPaint.setColor(Color.parseColor("#424242"));

        grassPaint = new Paint();
        grassPaint.setColor(Color.parseColor("#4CAF50"));

        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(5);

        textPaint = new Paint();
        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(50);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(5, 2, 2, Color.BLACK);

        obstacles = new ArrayList<>();
        random = new Random();
        startTime = System.currentTimeMillis();

        playerWidth = 100;
        playerHeight = 180;
        obstacleWidth = 100;
        obstacleHeight = 180;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        screenWidth = w;
        screenHeight = h;
        roadWidth = (int) (screenWidth * 0.9);
        roadLeft = (screenWidth - roadWidth) / 2;

        numLanes = roadWidth / (obstacleWidth + 20);
        if (numLanes < 3) numLanes = 3;
        laneWidth = roadWidth / numLanes;

        playerX = screenWidth / 2 - playerWidth / 2;
        playerY = screenHeight - playerHeight - 200;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (prankTriggered || isGameOver) return;

        // Draw Grass
        canvas.drawRect(0, 0, screenWidth, screenHeight, grassPaint);

        // Draw Road
        canvas.drawRect(roadLeft, 0, roadLeft + roadWidth, screenHeight, roadPaint);

        // Draw Animated Lane markings
        offset += speed;
        if (offset > 200) offset = 0;

        for (int i = -200 + offset; i < screenHeight; i += 200) {
            canvas.drawRect(screenWidth/2 - 10, i, screenWidth/2 + 10, i + 100, linePaint);
        }

        // Draw Player Car
        drawCar(canvas, playerX, playerY, playerWidth, playerHeight, playerPaint);

        // Draw and Update Obstacles
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Rect obstacle = obstacles.get(i);
            drawCar(canvas, obstacle.left, obstacle.top, obstacleWidth, obstacleHeight, obstaclePaint);

            obstacle.top += speed;
            obstacle.bottom += speed;

            if (Rect.intersects(obstacle, new Rect(playerX, playerY, playerX + playerWidth, playerY + playerHeight))) {
                obstacles.remove(i);
                score -= 10;
                vibrate(300);
                if (score < 0) {
                    isGameOver = true;
                    if (listener != null) listener.onGameOver();
                    return;
                }
            } else if (obstacle.top > screenHeight) {
                obstacles.remove(i);
                score++;
                if (score > 0 && score % 10 == 0 && level < MAX_LEVEL) {
                    level++;
                    speed += 2;
                }
            }
        }

        // Improved Obstacle Spawning
        if (random.nextInt(100) < (5 + level)) {
            spawnObstacle();
        }

        // UI
        canvas.drawText("Score: " + score, 30, 80, textPaint);
        canvas.drawText("Level: " + level, 30, 150, textPaint);

        long elapsed = System.currentTimeMillis() - startTime;
        long remaining = Math.max(0, 120000 - elapsed);
        int seconds = (int) (remaining / 1000);
        canvas.drawText("Time: " + seconds + "s", screenWidth - 250, 80, textPaint);

        if (elapsed > 120000 && !prankTriggered) {
            prankTriggered = true;
            if (listener != null) {
                listener.onPrankTrigger();
            }
        }

        invalidate();
    }

    private void spawnObstacle() {
        // Ensure some vertical gap for playability
        if (!obstacles.isEmpty()) {
            Rect last = obstacles.get(obstacles.size() - 1);
            int minGap = 350 - (level * 10);
            if (last.top < minGap) return;
        }

        // Randomly pick which lane to leave open (the safe lane)
        int safeLane = random.nextInt(numLanes);

        int lastBlockedLane = -2; // Keep track of last blocked lane in this row
        for (int lane = 0; lane < numLanes; lane++) {
            // Logic: Don't block if it's the safe lane OR if the previous lane was just blocked (prevent side-by-side)
            if (lane != safeLane && lane != lastBlockedLane + 1) {
                // Randomly decide to block this lane in this row
                if (random.nextFloat() < 0.7f) {
                    int x = roadLeft + lane * laneWidth + (laneWidth - obstacleWidth) / 2;
                    obstacles.add(new Rect(x, -obstacleHeight - 50, x + obstacleWidth, -50));
                    lastBlockedLane = lane;
                }
            }
        }
    }

    private void drawCar(Canvas canvas, int x, int y, int w, int h, Paint p) {
        // Body
        canvas.drawRect(x, y, x + w, y + h, p);

        // Windows (proportional)
        Paint windowPaint = new Paint();
        windowPaint.setColor(Color.LTGRAY);
        int margin = w / 10;
        canvas.drawRect(x + margin, y + h / 6, x + w - margin, y + h / 3, windowPaint);
        canvas.drawRect(x + margin, y + 2 * h / 3, x + w - margin, y + 5 * h / 6, windowPaint);

        // Tires (proportional)
        Paint tirePaint = new Paint();
        tirePaint.setColor(Color.BLACK);
        int tireWidth = w / 10;
        int tireHeight = h / 5;
        canvas.drawRect(x - tireWidth, y + h / 10, x, y + h / 10 + tireHeight, tirePaint);
        canvas.drawRect(x + w, y + h / 10, x + w + tireWidth, y + h / 10 + tireHeight, tirePaint);
        canvas.drawRect(x - tireWidth, y + h - h / 10 - tireHeight, x, y + h - h / 10, tirePaint);
        canvas.drawRect(x + w, y + h - h / 10 - tireHeight, x + w + tireWidth, y + h - h / 10, tirePaint);
    }

    private void vibrate(long duration) {
        Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            v.vibrate(duration);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_DOWN) {
            playerX = (int) event.getX() - playerWidth / 2;
            if (playerX < roadLeft) playerX = roadLeft;
            if (playerX > roadLeft + roadWidth - playerWidth) playerX = roadLeft + roadWidth - playerWidth;
        }
        return true;
    }
}
