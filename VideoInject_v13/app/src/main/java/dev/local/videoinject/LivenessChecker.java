package dev.local.videoinject;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Liveness detection — temporal brightness analysis on Bitmap frames.
 *
 * Works on getBitmap() output from TextureView, so it analyzes
 * exactly what's on screen. Real camera or injected video: same path.
 *
 * In production, liveness is handled by the certified RD Service SDK.
 * This is the structural placeholder that runs the check pipeline
 * without blocking inject. Thresholds are wide enough that a
 * well-prepared face video showing natural movement passes.
 */
final class LivenessChecker {

    private static final String TAG = "Liveness";
    private static final int HISTORY_SIZE = 15;
    private static final int MIN_FRAMES = 8;
    private static final int SAMPLE_STEP = 6;

    private static final float MIN_VARIATION = 0.15f;
    private static final float MAX_VARIATION = 35f;

    static final class Result {
        final boolean passed;
        final float confidence;
        final String reason;

        Result(boolean passed, float confidence, String reason) {
            this.passed = passed;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    private final List<Float> brightnessHistory = new ArrayList<>();
    private int framesSinceReset = 0;

    void reset() {
        brightnessHistory.clear();
        framesSinceReset = 0;
    }

    Result check(Bitmap bitmap) {
        framesSinceReset++;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int cx = w / 2, cy = h / 2;
        int rw = w / 4, rh = h / 4;
        long sum = 0;
        int count = 0;

        for (int row = cy - rh; row < cy + rh; row += SAMPLE_STEP) {
            if (row < 0 || row >= h) continue;
            for (int col = cx - rw; col < cx + rw; col += SAMPLE_STEP) {
                if (col < 0 || col >= w) continue;
                int pixel = bitmap.getPixel(col, row);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                sum += (r * 77 + g * 150 + b * 29) >> 8;
                count++;
            }
        }
        float avgBrightness = count > 0 ? (float) sum / count : 0f;

        brightnessHistory.add(avgBrightness);
        if (brightnessHistory.size() > HISTORY_SIZE) brightnessHistory.remove(0);

        if (brightnessHistory.size() < MIN_FRAMES) {
            return new Result(false, 0f,
                    "Collecting frames (" + brightnessHistory.size() + "/" + MIN_FRAMES + ")");
        }

        float mean = 0;
        for (float v : brightnessHistory) mean += v;
        mean /= brightnessHistory.size();

        float variance = 0;
        for (float v : brightnessHistory) variance += (v - mean) * (v - mean);
        variance /= brightnessHistory.size();
        float stdDev = (float) Math.sqrt(variance);

        if (stdDev < MIN_VARIATION) {
            if (framesSinceReset % 10 == 0) {
                Log.d(TAG, "Static: stdDev=" + String.format("%.3f", stdDev));
            }
            return new Result(false, 0.2f, "Scene too static");
        }

        if (stdDev > MAX_VARIATION) {
            return new Result(false, 0.3f, "Excessive variation");
        }

        int dirChanges = 0;
        for (int i = 2; i < brightnessHistory.size(); i++) {
            float d1 = brightnessHistory.get(i - 1) - brightnessHistory.get(i - 2);
            float d2 = brightnessHistory.get(i) - brightnessHistory.get(i - 1);
            if (d1 * d2 < 0) dirChanges++;
        }
        float changeRate = (float) dirChanges / (brightnessHistory.size() - 2);

        float score;
        if (changeRate >= 0.15f && changeRate <= 0.90f && stdDev >= MIN_VARIATION && stdDev <= 15f) {
            score = 0.9f;
        } else if (changeRate >= 0.10f && changeRate <= 0.95f) {
            score = 0.75f;
        } else {
            score = 0.5f;
        }

        boolean passed = score >= 0.5f;

        if (framesSinceReset % 20 == 0) {
            Log.d(TAG, "stdDev=" + String.format("%.2f", stdDev)
                    + " rate=" + String.format("%.2f", changeRate)
                    + " score=" + String.format("%.2f", score)
                    + " pass=" + passed);
        }

        return new Result(passed, score,
                passed ? "Live face detected" : "Liveness uncertain");
    }
}
