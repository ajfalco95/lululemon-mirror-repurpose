package dev.mirror.repurpose;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public final class DelayedMirrorActivity extends Activity
        implements TextureView.SurfaceTextureListener {

    private static final int CAMERA_PERMISSION_REQUEST = 2001;

    private TextureView preview;
    private Camera camera;
    private H264Encoder h264Encoder;
    private long lastFrameMs = 0L;
    private long cameraFpsStart = 0L;
    private int cameraFpsFrames = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);

        // Mirror-like horizontal flip.
        preview.setScaleX(-1f);

        // Tap anywhere on the image to save a full-resolution JPEG.
        preview.setOnClickListener(v -> takeSnapshot());

        setContentView(preview);

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void startCamera() {
        if (camera != null || !preview.isAvailable()) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            camera = Camera.open(0);

            Camera.Parameters params = camera.getParameters();

            // Preview at 1280x720 when available.
            List<Camera.Size> previewSizes = params.getSupportedPreviewSizes();
            Camera.Size selectedPreview = null;

            for (Camera.Size size : previewSizes) {
                if (size.width == 1280 && size.height == 720) {
                    selectedPreview = size;
                    break;
                }
            }

            if (selectedPreview == null && !previewSizes.isEmpty()) {
                selectedPreview = previewSizes.get(0);
            }

            if (selectedPreview != null) {
                params.setPreviewSize(
                        selectedPreview.width,
                        selectedPreview.height);
            }

            // Pick the largest still-image size the camera exposes.
            List<Camera.Size> pictureSizes = params.getSupportedPictureSizes();
            Camera.Size largest = null;

            for (Camera.Size size : pictureSizes) {
                if (largest == null
                        || size.width * size.height
                        > largest.width * largest.height) {
                    largest = size;
                }
            }

            if (largest != null) {
                params.setPictureSize(largest.width, largest.height);
            }

            params.setJpegQuality(100);
            params.setPreviewFormat(ImageFormat.NV21);

            camera.setParameters(params);

            // Use the orientation that gave us the upright preview.
            camera.setDisplayOrientation(270);

            SurfaceTexture texture = preview.getSurfaceTexture();
            camera.setPreviewTexture(texture);

            Camera.Parameters fpsParams = camera.getParameters();

            int[] currentRange = new int[2];
            fpsParams.getPreviewFpsRange(currentRange);

            android.util.Log.i(
                    "MirrorCamera",
                    "Current FPS range: "
                            + currentRange[0] / 1000.0
                            + "-"
                            + currentRange[1] / 1000.0);

            for (int[] range : fpsParams.getSupportedPreviewFpsRange()) {
                android.util.Log.i(
                        "MirrorCamera",
                        "Supported FPS range: "
                                + range[0] / 1000.0
                                + "-"
                                + range[1] / 1000.0);
            }

            Camera.Parameters forceFps = camera.getParameters();
            forceFps.setPreviewFpsRange(30000, 30000);
            camera.setParameters(forceFps);

            Camera.Size streamSize = camera.getParameters().getPreviewSize();

            h264Encoder = new H264Encoder();
            h264Encoder.start();

            camera.setPreviewCallback((data, cam) -> {
                long now = android.os.SystemClock.elapsedRealtime();

                if (cameraFpsStart == 0L) {
                    cameraFpsStart = now;
                }

                cameraFpsFrames++;

                if (now - cameraFpsStart >= 1000) {
                    double fps =
                            cameraFpsFrames * 1000.0
                            / (now - cameraFpsStart);

                    android.util.Log.i(
                            "MirrorCameraFPS",
                            "Camera callback FPS: " + fps);

                    cameraFpsFrames = 0;
                    cameraFpsStart = now;
                }

                if (h264Encoder != null) {
                    h264Encoder.queueNv21(data);
                }
            });

            camera.startPreview();

        } catch (RuntimeException | IOException e) {
            e.printStackTrace();
            stopCamera();
        }
    }

    private void takeSnapshot() {
        if (camera == null) {
            return;
        }

        try {
            camera.takePicture(
                    null,
                    null,
                    (data, cam) -> {
                        File dir = getExternalFilesDir(null);

                        if (dir == null) {
                            Toast.makeText(
                                    this,
                                    "No storage available",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }

                        File output = new File(dir, "mirror_raw.jpg");

                        try (FileOutputStream stream =
                                     new FileOutputStream(output)) {

                            stream.write(data);

                            Toast.makeText(
                                    this,
                                    "Saved mirror_raw.jpg",
                                    Toast.LENGTH_LONG).show();

                        } catch (IOException e) {
                            e.printStackTrace();

                            Toast.makeText(
                                    this,
                                    "Snapshot failed",
                                    Toast.LENGTH_LONG).show();
                        }

                        // takePicture() stops preview, so resume it.
                        try {
                            cam.startPreview();
                        } catch (RuntimeException ignored) {
                        }
                    });

        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private void stopCamera() {
        if (camera != null) {
            Camera oldCamera = camera;
            camera = null;

            try {
                oldCamera.setPreviewCallback(null);
            } catch (RuntimeException ignored) {
            }

            try {
                oldCamera.stopPreview();
            } catch (RuntimeException ignored) {
            }

            try {
                oldCamera.release();
            } catch (RuntimeException ignored) {
            }
        }

        if (h264Encoder != null) {
            h264Encoder.stop();
            h264Encoder = null;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(
            SurfaceTexture surface, int width, int height) {
        startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (preview != null && preview.isAvailable()) {
            startCamera();
        }
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }
}
