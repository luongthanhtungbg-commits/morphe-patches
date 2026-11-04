/*
 * Kids mode for Morphe (custom fork).
 *
 * Licensed under GPLv3, same as the rest of this repository.
 * See the included NOTICE file for GPLv3 Section 7 terms.
 */

package app.morphe.extension.youtube.patches.kids;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.FaceDetector;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Collections;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Estimates how far the child's face is from the screen using the front camera.
 * <p>
 * Frames are analysed on the device in memory only. Nothing is saved or sent anywhere.
 * The camera runs only while a video is playing in kids mode, at about two frames per second.
 */
final class KidsDistanceMonitor {

    interface Listener {
        /**
         * Called on the main thread.
         *
         * @param distanceCm Estimated distance, or a negative value if no face was found.
         */
        void onMeasurement(float distanceCm);
    }

    private static final int PERMISSION_REQUEST_CODE = 0x4B44; // "KD"
    private static final long FRAME_INTERVAL_MS = 500;
    /** Camera stops if playback has not ticked for this long, unless kept alive. */
    private static final long IDLE_STOP_MS = 5_000;
    private static final long WATCHDOG_INTERVAL_MS = 1_000;
    /** Battery saver: the camera checks for this long... */
    private static final long SAVER_ACTIVE_MS = 4_000;
    /** ...then turns off for this long. */
    private static final long SAVER_PAUSE_MS = 8_000;
    /** A close measurement keeps the camera on until it is confirmed or cleared. */
    private static final long SUSPICIOUS_HOLD_MS = 3_000;
    private static final int TARGET_WIDTH = 320;
    private static final float MIN_FACE_CONFIDENCE = 0.4f;

    @Nullable
    private static HandlerThread thread;
    @Nullable
    private static Handler handler;
    @Nullable
    private static CameraDevice camera;
    @Nullable
    private static CameraCaptureSession session;
    @Nullable
    private static ImageReader reader;
    @Nullable
    private static Listener listener;
    @Nullable
    private static FaceDetector faceDetector;
    private static int detectorWidth, detectorHeight;

    private static volatile boolean opening;
    private static volatile boolean stopRequested;
    private static volatile int displayRotationDegrees;
    private static volatile long lastPlaybackTickMs;
    private static volatile boolean keepAlive;
    private static volatile boolean batterySaver = true;
    private static volatile long sessionStartMs;
    private static volatile long nextAllowedStartMs;
    private static volatile long lastSuspiciousMs;

    private static boolean permissionRequested;
    private static boolean cameraUnavailable;
    private static float focalLengthPx;
    private static int sensorOrientation;
    private static long lastFrameMs;

    private KidsDistanceMonitor() {
    }

    /**
     * Call on the main thread for every playback tick. Starts the camera if needed.
     */
    static void onPlaybackTick(Activity activity, Listener newListener, boolean saver) {
        final long now = SystemClock.elapsedRealtime();
        lastPlaybackTickMs = now;
        batterySaver = saver;
        updateDisplayRotation(activity);
        if (camera != null || opening || cameraUnavailable) return;
        if (saver && now < nextAllowedStartMs) return;
        start(activity, newListener);
    }

    /** Keeps the camera running while playback is paused, so the child moving away can be seen. */
    static void setKeepAlive(boolean value) {
        keepAlive = value;
        if (value) lastPlaybackTickMs = SystemClock.elapsedRealtime();
    }

    /** A measurement was close to the limit. Keeps the camera on in battery saver mode. */
    static void markSuspicious() {
        lastSuspiciousMs = SystemClock.elapsedRealtime();
    }

    static boolean isRunning() {
        return camera != null || opening;
    }

    @SuppressLint("MissingPermission")
    private static void start(Activity activity, Listener newListener) {
        try {
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (!permissionRequested) {
                    permissionRequested = true;
                    activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
                    Utils.showSnackbarShort("Cho phép camera để đo khoảng cách mắt bé tới màn hình");
                }
                return;
            }

            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = null;
            CameraCharacteristics characteristics = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics candidate = manager.getCameraCharacteristics(id);
                Integer facing = candidate.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    characteristics = candidate;
                    break;
                }
            }
            if (cameraId == null) {
                cameraUnavailable = true;
                Logger.printDebug(() -> "Kids mode: no front camera");
                return;
            }

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null || focalLengths == null || focalLengths.length == 0 || physicalSize == null) {
                cameraUnavailable = true;
                Logger.printDebug(() -> "Kids mode: front camera is missing required information");
                return;
            }

            Size size = chooseSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (size == null) {
                cameraUnavailable = true;
                return;
            }
            focalLengthPx = KidsRules.focalLengthPixels(focalLengths[0], physicalSize.getWidth(), size.getWidth());
            sensorOrientation = orientation == null ? 270 : orientation;
            final Range<Integer> fpsRange = chooseFpsRange(
                    characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));

            ensureThread();
            listener = newListener;
            stopRequested = false;
            opening = true;
            sessionStartMs = SystemClock.elapsedRealtime();

            ImageReader imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(KidsDistanceMonitor::onImageAvailable, handler);
            reader = imageReader;

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    opening = false;
                    if (stopRequested) {
                        device.close();
                        return;
                    }
                    camera = device;
                    createSession(device, imageReader, fpsRange);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    release();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    Logger.printDebug(() -> "Kids mode camera error: " + error);
                    device.close();
                    release();
                }
            }, handler);

            handler.postDelayed(KidsDistanceMonitor::watchdog, WATCHDOG_INTERVAL_MS);
        } catch (Exception ex) {
            opening = false;
            Logger.printException(() -> "Kids mode camera start failure", ex);
            release();
        }
    }

    private static void createSession(CameraDevice device, ImageReader imageReader, @Nullable Range<Integer> fpsRange) {
        try {
            //noinspection deprecation (the replacement needs API 28, the app supports 26)
            device.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession captureSession) {
                            if (camera == null) return;
                            session = captureSession;
                            try {
                                CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(imageReader.getSurface());
                                if (fpsRange != null) {
                                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                }
                                captureSession.setRepeatingRequest(builder.build(), null, handler);
                            } catch (Exception ex) {
                                Logger.printException(() -> "Kids mode capture request failure", ex);
                                release();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession captureSession) {
                            Logger.printDebug(() -> "Kids mode camera session failed");
                            release();
                        }
                    }, handler);
        } catch (Exception ex) {
            Logger.printException(() -> "Kids mode create session failure", ex);
            release();
        }
    }

    static void stop() {
        stopRequested = true;
        Handler h = handler;
        if (h != null) {
            h.post(KidsDistanceMonitor::release);
        } else {
            release();
        }
    }

    private static void release() {
        opening = false;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) camera.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
    }

    private static void watchdog() {
        if (!isRunning()) return;
        final long now = SystemClock.elapsedRealtime();
        if (!keepAlive && now - lastPlaybackTickMs > IDLE_STOP_MS) {
            Logger.printDebug(() -> "Kids mode: stopping camera, playback is idle");
            release();
            return;
        }
        if (batterySaver && !keepAlive
                && now - sessionStartMs > SAVER_ACTIVE_MS
                && now - lastSuspiciousMs > SUSPICIOUS_HOLD_MS) {
            nextAllowedStartMs = now + SAVER_PAUSE_MS;
            release();
            return;
        }
        Handler h = handler;
        if (h != null) h.postDelayed(KidsDistanceMonitor::watchdog, WATCHDOG_INTERVAL_MS);
    }

    // ---------------------------------------------------------------- Frame analysis

    private static void onImageAvailable(ImageReader imageReader) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return;
            final long now = SystemClock.elapsedRealtime();
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return;
            lastFrameMs = now;

            final float distanceCm = measure(image);
            final Listener current = listener;
            if (current != null) Utils.runOnMainThread(() -> current.onMeasurement(distanceCm));
        } catch (Exception ex) {
            Logger.printException(() -> "Kids mode frame failure", ex);
        } finally {
            if (image != null) image.close();
        }
    }

    private static float measure(Image image) {
        Bitmap bitmap = toUprightGrayBitmap(image);
        final int width = bitmap.getWidth() & ~1; // FaceDetector requires an even width.
        final int height = bitmap.getHeight();
        if (width != bitmap.getWidth()) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        }

        if (faceDetector == null || detectorWidth != width || detectorHeight != height) {
            faceDetector = new FaceDetector(width, height, 1);
            detectorWidth = width;
            detectorHeight = height;
        }

        FaceDetector.Face[] faces = new FaceDetector.Face[1];
        final int found = faceDetector.findFaces(bitmap, faces);
        bitmap.recycle();
        if (found == 0 || faces[0] == null || faces[0].confidence() < MIN_FACE_CONFIDENCE) {
            return -1;
        }
        return KidsRules.estimateDistanceCm(focalLengthPx, faces[0].eyesDistance(), KidsRules.CHILD_EYE_DISTANCE_MM);
    }

    /** Converts the luminance plane to an upright RGB_565 bitmap, the format FaceDetector needs. */
    private static Bitmap toUprightGrayBitmap(Image image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();

        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int rowStart = y * rowStride;
            final int outStart = y * width;
            for (int x = 0; x < width; x++) {
                final int luma = buffer.get(rowStart + x * pixelStride) & 0xFF;
                pixels[outStart + x] = 0xFF000000 | (luma << 16) | (luma << 8) | luma;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);

        // Front camera: rotate by sensor orientation plus display rotation to make the face upright.
        final int rotation = (sensorOrientation + displayRotationDegrees) % 360;
        if (rotation == 0) return bitmap;

        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);
        if (rotated != bitmap) bitmap.recycle();
        if (rotated.getConfig() != Bitmap.Config.RGB_565) {
            Bitmap converted = rotated.copy(Bitmap.Config.RGB_565, false);
            rotated.recycle();
            return converted;
        }
        return rotated;
    }

    // ---------------------------------------------------------------- Helpers

    private static void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new HandlerThread("KidsDistance");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @SuppressWarnings("deprecation")
    private static void updateDisplayRotation(Activity activity) {
        try {
            switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90 -> displayRotationDegrees = 90;
                case Surface.ROTATION_180 -> displayRotationDegrees = 180;
                case Surface.ROTATION_270 -> displayRotationDegrees = 270;
                default -> displayRotationDegrees = 0;
            }
        } catch (Exception ignored) {
        }
    }

    /** Smallest size at least TARGET_WIDTH wide, preferring the 4:3 full sensor aspect ratio. */
    @Nullable
    private static Size chooseSize(@Nullable Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() < TARGET_WIDTH) continue;
            final boolean fourByThree = size.getWidth() * 3 == size.getHeight() * 4;
            if (best == null) {
                best = size;
                continue;
            }
            final boolean bestFourByThree = best.getWidth() * 3 == best.getHeight() * 4;
            if (fourByThree && !bestFourByThree
                    || fourByThree == bestFourByThree && size.getWidth() < best.getWidth()) {
                best = size;
            }
        }
        return best != null ? best : sizes[sizes.length - 1];
    }

    /** The range with the lowest maximum frame rate, to save battery. */
    @Nullable
    private static Range<Integer> chooseFpsRange(@Nullable Range<Integer>[] ranges) {
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = ranges[0];
        for (Range<Integer> range : ranges) {
            if (range.getUpper() < best.getUpper()) best = range;
        }
        return best;
    }
}
