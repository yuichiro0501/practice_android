package com.example.glcamera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import java.util.Collections;

/**
 * Camera2 API で「特定のカメラ」を開き、その映像を
 * OpenGL ES 2.0 (GLSurfaceView + 外部OESテクスチャ) で画面に描画するサンプル。
 *
 * 前提: 画面は縦固定 (AndroidManifest で portrait 指定)、minSdk 23 以上。
 */
public class MainActivity extends Activity implements CameraRenderer.Callback {

    private static final String TAG = "GLCamera";
    private static final int REQUEST_CAMERA = 1;

    // ---- 表示したいカメラの指定 -------------------------------------------
    // 特定のカメラIDを使う場合はここに ID を書く (例: "0", "1", "2" ...)。
    // null の場合は TARGET_LENS_FACING に一致する最初のカメラを使う。
    private static final String TARGET_CAMERA_ID = null;
    private static final int TARGET_LENS_FACING = CameraCharacteristics.LENS_FACING_BACK;
    // 希望するプレビュー解像度 (近いサイズが自動で選ばれる)
    private static final int DESIRED_WIDTH = 1920;
    private static final int DESIRED_HEIGHT = 1080;
    // -------------------------------------------------------------------------

    private GLSurfaceView glView;
    private CameraRenderer renderer;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private SurfaceTexture surfaceTexture; // GLスレッドで作られたカメラ出力先
    private Surface previewSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);              // OpenGL ES 2.0
        renderer = new CameraRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY); // 新フレーム到着時のみ描画
        setContentView(glView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        glView.onResume(); // → CameraRenderer.onSurfaceCreated が呼ばれ SurfaceTexture が作られる

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        glView.onPause();  // EGLコンテキスト破棄 (再開時に作り直される)
        surfaceTexture = null;
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                openCameraIfReady();
            } else {
                Toast.makeText(this, "カメラの権限が必要です", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /** CameraRenderer から (メインスレッドで) 呼ばれる */
    @Override
    public void onSurfaceTextureReady(SurfaceTexture st) {
        surfaceTexture = st;
        openCameraIfReady();
    }

    // ===================== カメラ制御 =====================

    private void openCameraIfReady() {
        if (surfaceTexture == null || cameraDevice != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        try {
            String cameraId = selectCameraId();
            if (cameraId == null) {
                Toast.makeText(this, "指定したカメラが見つかりません", Toast.LENGTH_LONG).show();
                return;
            }
            Log.i(TAG, "Open camera id=" + cameraId);

            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));

            // センサーは通常横向きに付いているため、縦画面では幅と高さを入れ替えて扱う
            Integer sensorOrientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean swap = sensorOrientation != null && (sensorOrientation == 90 || sensorOrientation == 270);
            final int displayW = swap ? size.getHeight() : size.getWidth();
            final int displayH = swap ? size.getWidth() : size.getHeight();
            glView.queueEvent(() -> renderer.setPreviewSize(displayW, displayH));

            surfaceTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(surfaceTexture);

            cameraManager.openCamera(cameraId, stateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    /** TARGET_CAMERA_ID / TARGET_LENS_FACING に従ってカメラIDを決める */
    private String selectCameraId() throws CameraAccessException {
        String[] ids = cameraManager.getCameraIdList();
        if (TARGET_CAMERA_ID != null) {
            for (String id : ids) {
                if (id.equals(TARGET_CAMERA_ID)) return id;
            }
            return null;
        }
        for (String id : ids) {
            Integer facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == TARGET_LENS_FACING) return id;
        }
        return ids.length > 0 ? ids[0] : null;
    }

    /** 希望解像度に面積が最も近いサイズを選ぶ */
    private Size choosePreviewSize(Size[] sizes) {
        Size best = sizes[0];
        long target = (long) DESIRED_WIDTH * DESIRED_HEIGHT;
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight() - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            camera.close();
            cameraDevice = null;
        }
    };

    @SuppressWarnings("deprecation")
    private void startPreview() {
        try {
            final CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException ignored) {
        }
        cameraThread = null;
        cameraHandler = null;
    }
}
