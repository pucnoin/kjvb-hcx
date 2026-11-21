package com.example.stresstest;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws a full-surface quad with a very heavy fragment shader, several times
 * per frame, to keep the GPU busy. It also measures how long the GPU needs
 * per frame so the app can notice throttling.
 */
public class GpuStressRenderer implements GLSurfaceView.Renderer {

    private static final String VERTEX =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    private static final String FRAGMENT =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vUv;\n" +
            "uniform float uTime;\n" +
            "uniform float uIter;\n" +
            "void main(){\n" +
            "  vec2 z = vUv * 4.0 - 2.0;\n" +
            "  float acc = 0.0;\n" +
            "  for (int i = 0; i < 1024; i++) {\n" +
            "    if (float(i) >= uIter) break;\n" +
            "    z = vec2(sin(z.x * 1.3 + z.y) + cos(uTime + z.y),\n" +
            "             cos(z.y * 1.1 - z.x) + sin(uTime * 0.7 + z.x));\n" +
            "    acc += length(z) * 0.001;\n" +
            "  }\n" +
            "  gl_FragColor = vec4(fract(acc), fract(acc * 1.7), fract(acc * 2.3), 1.0);\n" +
            "}\n";

    /** When false the surface only shows a dark background (no load). */
    public volatile boolean active;
    public volatile float iterations = 100f;
    public volatile int draws = 1;
    /** Set if the shader could not be built on this phone. */
    public volatile String error;

    private final AtomicLong nanosSum = new AtomicLong();
    private final AtomicLong frames = new AtomicLong();
    private final long startNs = System.nanoTime();

    private int program;
    private int aPos;
    private int uTime;
    private int uIter;
    private FloatBuffer quad;

    /** Average GPU time per frame (ms) since the last call, or NaN if no frames. */
    public double takeAvgFrameMs() {
        long f = frames.getAndSet(0);
        long n = nanosSum.getAndSet(0);
        if (f == 0) return Double.NaN;
        return n / 1e6 / f;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                program = 0;
                throw new RuntimeException(log);
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uTime = GLES20.glGetUniformLocation(program, "uTime");
            uIter = GLES20.glGetUniformLocation(program, "uIter");
            float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
            quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(v).position(0);
            error = null;
        } catch (RuntimeException e) {
            program = 0;
            error = "GPU shader failed: " + e.getMessage();
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!active || program == 0) {
            GLES20.glClearColor(0.05f, 0.07f, 0.10f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        long t0 = System.nanoTime();
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glUniform1f(uIter, iterations);
        float t = (System.nanoTime() - startNs) / 1e9f;
        int n = draws;
        for (int d = 0; d < n; d++) {
            GLES20.glUniform1f(uTime, t + d * 0.01f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES20.glFinish(); // wait until the GPU is really done, so timing is real
        nanosSum.addAndGet(System.nanoTime() - t0);
        frames.incrementAndGet();
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException(log);
        }
        return s;
    }
}
