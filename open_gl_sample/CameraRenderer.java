package com.example.glcamera;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * カメラ映像を GL_TEXTURE_EXTERNAL_OES テクスチャで受け取り、全画面の四角形に描画するレンダラー。
 * アスペクト比を保ったまま画面に収まるように (レターボックス) 表示する。
 */
public class CameraRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface Callback {
        /** SurfaceTexture の準備ができたとき (メインスレッドで呼ばれる) */
        void onSurfaceTextureReady(SurfaceTexture surfaceTexture);
    }

    private static final String TAG = "CameraRenderer";

    // ---- シェーダー ----
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            // 例: グレースケールにしたい場合は上の行の代わりに以下を使う
            // "    vec4 c = texture2D(sTexture, vTexCoord);\n" +
            // "    float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
            // "    gl_FragColor = vec4(g, g, g, 1.0);\n" +
            "}\n";

    // 全画面四角形 (TRIANGLE_STRIP)
    private static final float[] VERTICES = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f,
    };
    private static final float[] TEX_COORDS = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
    };

    private final GLSurfaceView glView;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final FloatBuffer vertexBuffer = createFloatBuffer(VERTICES);
    private final FloatBuffer texCoordBuffer = createFloatBuffer(TEX_COORDS);
    private final float[] texMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];

    private SurfaceTexture surfaceTexture;
    private int textureId;
    private int program;
    private int aPositionLoc, aTexCoordLoc, uMvpLoc, uTexMatrixLoc;

    private int viewWidth, viewHeight;
    private int previewWidth, previewHeight;

    public CameraRenderer(GLSurfaceView glView, Callback callback) {
        this.glView = glView;
        this.callback = callback;
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    // ===================== GLSurfaceView.Renderer =====================

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");

        // カメラ映像を受け取る外部テクスチャを作成
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 前回 (onPause 前) の SurfaceTexture があれば解放
        if (surfaceTexture != null) {
            surfaceTexture.release();
        }
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);

        final SurfaceTexture st = surfaceTexture;
        mainHandler.post(() -> callback.onSurfaceTextureReady(st));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewWidth = width;
        viewHeight = height;
        updateMvpMatrix();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (surfaceTexture == null) return;

        // 最新のカメラフレームをテクスチャへ取り込み、座標変換行列を取得
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(texMatrix);

        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
    }

    // ===================== SurfaceTexture =====================

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        glView.requestRender(); // 新しいフレームが来たら再描画
    }

    /** 表示上のプレビューサイズ (縦画面基準) を設定。GLスレッドから呼ぶこと */
    public void setPreviewSize(int width, int height) {
        previewWidth = width;
        previewHeight = height;
        updateMvpMatrix();
    }

    /** アスペクト比を保って画面内に収める (はみ出さない) 拡大縮小行列を作る */
    private void updateMvpMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0);
        if (viewWidth == 0 || viewHeight == 0 || previewWidth == 0 || previewHeight == 0) return;

        float viewAspect = (float) viewWidth / viewHeight;
        float previewAspect = (float) previewWidth / previewHeight;
        float scaleX = 1f, scaleY = 1f;
        if (previewAspect > viewAspect) {
            scaleY = viewAspect / previewAspect;   // 上下に黒帯
        } else {
            scaleX = previewAspect / viewAspect;   // 左右に黒帯
        }
        // 画面いっぱいに表示 (はみ出しはトリミング) したい場合は、上の if の条件を逆にする
        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f);
    }

    // ===================== GL ユーティリティ =====================

    private static FloatBuffer createFloatBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed");
        }
        return shader;
    }

    private static int createProgram(String vs, String fs) {
        int vertex = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vertex);
        GLES20.glAttachShader(prog, fragment);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Program link failed");
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return prog;
    }
}
