package dev.local.videoinject;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Google ML Kit face finder (bundled model, offline). Much better than Android's old FaceDetector. */
final class MlFace {

    static final class Box {
        float cx, cy, size;
    }

    private MlFace() {}

    /**
     * Finds the biggest face in {@code bmp} when the picture is turned clockwise by {@code rotation}.
     * Returns null if there is none. Throws if ML Kit itself is not usable. Never call on the main thread.
     */
    static Box find(FaceDetector det, Bitmap bmp, int rotation) throws Exception {
        List<Face> faces = Tasks.await(det.process(InputImage.fromBitmap(bmp, rotation)), 20, TimeUnit.SECONDS);
        if (faces == null || faces.isEmpty()) return null;
        boolean odd = rotation == 90 || rotation == 270;
        float uw = odd ? bmp.getHeight() : bmp.getWidth();
        float uh = odd ? bmp.getWidth() : bmp.getHeight();
        Face best = null;
        int bestArea = 0;
        for (Face f : faces) {
            Rect r = f.getBoundingBox();
            int a = r.width() * r.height();
            if (a > bestArea) {
                bestArea = a;
                best = f;
            }
        }
        if (best == null) return null;
        Rect r = best.getBoundingBox();
        Box b = new Box();
        b.cx = (r.left + r.right) / 2f / uw;
        b.cy = (r.top + r.bottom) / 2f / uh;
        b.size = Math.min(1f, Math.max(r.width(), r.height()) / uw);
        return b;
    }

    static FaceDetector create() {
        FaceDetectorOptions o = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.05f)
                .build();
        return FaceDetection.getClient(o);
    }
}
