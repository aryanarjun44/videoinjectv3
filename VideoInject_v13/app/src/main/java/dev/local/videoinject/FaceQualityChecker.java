package dev.local.videoinject;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

/**
 * Face quality and alignment checker — works on Bitmap (ARGB).
 *
 * Takes getBitmap() output from TextureView, so it processes
 * exactly what's on screen — real camera or injected video, no difference.
 *
 * Checks:
 * 1. Skin-tone detection to find face region (RGB → YCbCr)
 * 2. Alignment — face centered in oval, correct size
 * 3. Quality score from brightness, contrast, alignment, fill
 *
 * Score 0-100 maps to qScore in PidData Resp.
 */
final class FaceQualityChecker {

    private static final String TAG = "FaceQuality";
    private static final float MIN_FACE_RATIO = 0.25f;
    private static final float MAX_FACE_RATIO = 0.75f;
    private static final float CENTER_TOLERANCE = 0.15f;
    private static final int SAMPLE_STEP = 4;

    static final class Result {
        final boolean faceFound;
        final boolean aligned;
        final int score;
        final boolean tooClose;
        final boolean tooFar;
        final boolean tooBright;
        final boolean tooDark;
        final Rect faceRect;

        Result(boolean faceFound, boolean aligned, int score,
               boolean tooClose, boolean tooFar, boolean tooBright, boolean tooDark, Rect faceRect) {
            this.faceFound = faceFound;
            this.aligned = aligned;
            this.score = score;
            this.tooClose = tooClose;
            this.tooFar = tooFar;
            this.tooBright = tooBright;
            this.tooDark = tooDark;
            this.faceRect = faceRect;
        }
    }

    private int frameCount = 0;

    Result check(Bitmap bitmap) {
        frameCount++;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        long ySum = 0;
        int yMin = 255, yMax = 0;
        int sampleCount = 0;

        int skinPixels = 0;
        int skinMinX = w, skinMaxX = 0;
        int skinMinY = h, skinMaxY = 0;
        long skinSumX = 0, skinSumY = 0;

        for (int row = 0; row < h; row += SAMPLE_STEP) {
            for (int col = 0; col < w; col += SAMPLE_STEP) {
                int pixel = bitmap.getPixel(col, row);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                ySum += y;
                if (y < yMin) yMin = y;
                if (y > yMax) yMax = y;
                sampleCount++;

                if (y < 60) continue;

                int cb = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int cr = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;

                if (cb >= 100 && cb <= 130 && cr >= 133 && cr <= 173) {
                    skinPixels++;
                    if (col < skinMinX) skinMinX = col;
                    if (col > skinMaxX) skinMaxX = col;
                    if (row < skinMinY) skinMinY = row;
                    if (row > skinMaxY) skinMaxY = row;
                    skinSumX += col;
                    skinSumY += row;
                }
            }
        }

        int avgBrightness = sampleCount > 0 ? (int) (ySum / sampleCount) : 0;
        int contrast = yMax - yMin;
        float skinRatio = (float) skinPixels / sampleCount;

        if (skinRatio < 0.05f || skinPixels < 50) {
            return new Result(false, false, 0, false, false,
                    avgBrightness > 220, avgBrightness < 40, null);
        }

        float faceW = skinMaxX - skinMinX;
        float faceH = skinMaxY - skinMinY;
        float faceCenterX = (float) skinSumX / skinPixels;
        float faceCenterY = (float) skinSumY / skinPixels;
        Rect faceRect = new Rect(skinMinX, skinMinY, skinMaxX, skinMaxY);

        float faceRatioW = faceW / w;
        boolean tooClose = faceRatioW > MAX_FACE_RATIO;
        boolean tooFar = faceRatioW < MIN_FACE_RATIO;
        float centerOffX = Math.abs(faceCenterX / w - 0.5f);
        float centerOffY = Math.abs(faceCenterY / h - 0.45f);
        boolean centered = centerOffX < CENTER_TOLERANCE && centerOffY < CENTER_TOLERANCE;
        boolean sizeOk = !tooClose && !tooFar;
        boolean aligned = centered && sizeOk;

        int score = 50;

        if (avgBrightness >= 100 && avgBrightness <= 180) score += 15;
        else if (avgBrightness >= 70 && avgBrightness <= 220) score += 8;
        else score -= 10;

        if (contrast > 120) score += 12;
        else if (contrast > 80) score += 6;
        else score -= 5;

        if (centered) score += 10;
        if (sizeOk) score += 8;

        float faceFill = (float) skinPixels * SAMPLE_STEP * SAMPLE_STEP / (faceW * faceH + 1);
        if (faceFill > 0.15f) score += 5;

        score = Math.max(0, Math.min(100, score));

        if (frameCount % 15 == 0) {
            Log.d(TAG, "brightness=" + avgBrightness + " contrast=" + contrast
                    + " skin=" + Math.round(skinRatio * 100) + "%"
                    + " face=" + Math.round(faceRatioW * 100) + "%w centered=" + centered
                    + " score=" + score);
        }

        return new Result(true, aligned, score, tooClose, tooFar,
                avgBrightness > 220, avgBrightness < 40, faceRect);
    }
}
