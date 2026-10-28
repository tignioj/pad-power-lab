package com.padpowerlab;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.locks.LockSupport;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Full-screen fragment compute in a separate FBO; preview size does not reduce load. */
final class GpuLoadView extends GLSurfaceView implements GLSurfaceView.Renderer {
    volatile boolean active;
    volatile int iterations = 48;
    volatile int targetWidth = 1920, targetHeight = 1280;
    volatile int frameLimit = 60;
    volatile float fps;
    volatile String rendererName = "OpenGL ES 3.0";
    volatile String error;
    private int compute, blit, framebuffer, texture, vao;
    private int width, height, allocatedWidth, allocatedHeight;
    private int timeLoc, sizeLoc, stepsLoc, phaseLoc;
    private long epoch = SystemClock.elapsedRealtimeNanos(), fpsEpoch;
    private int frames;
    private boolean ready;

    GpuLoadView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    void setActive(boolean value) {
        active = value;
        fps = 0;
        setRenderMode(value ? RENDERMODE_CONTINUOUSLY : RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    private static final String VERTEX = "#version 300 es\n"
        + "out vec2 uv; void main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);"
        + "uv=p; gl_Position=vec4(p*2.0-1.0,0,1);}";
    private static final String FRAGMENT = "#version 300 es\nprecision highp float;\n"
        + "in vec2 uv; out vec4 color; uniform vec2 resolution; uniform float clock;"
        + "uniform float phase; uniform int steps;"
        + "void main(){ vec2 p=(uv-0.5)*vec2(resolution.x/resolution.y,1.0)*2.4;"
        + "vec3 q=vec3(p,0.25*sin(clock*.18)+phase); float e=0.0;"
        + "for(int i=0;i<160;i++){ if(i>=steps)break;"
        + "q=abs(q)/max(dot(q,q),0.18)-vec3(.81,.89,.94);"
        + "q+=.024*sin(q.yzx*2.9+clock*.2+float(i)*.07);"
        + "e+=exp(-abs(length(q)-1.2)*2.2)/(2.0+float(i)*.08); }"
        + "float f=e/max(float(steps)*.055,1.0);"
        + "vec3 ink=vec3(.025,.055,.095);"
        + "vec3 glow=vec3(.34+.2*sin(q.x),.8,.55+.25*sin(q.z));"
        + "vec3 hdr=ink+glow*f*.32; color=vec4(hdr/(1.0+hdr),1.0);}";
    private static final String BLIT = "#version 300 es\nprecision mediump float;"
        + "in vec2 uv; out vec4 color; uniform sampler2D image;"
        + "void main(){color=texture(image,uv);}";

    private int shader(int type, String source) {
        int id = GLES30.glCreateShader(type);
        GLES30.glShaderSource(id, source);
        GLES30.glCompileShader(id);
        int[] ok = new int[1]; GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES30.glGetShaderInfoLog(id));
        return id;
    }
    private int program(String fragment) {
        int vs = shader(GLES30.GL_VERTEX_SHADER, VERTEX), fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs); GLES30.glLinkProgram(p);
        int[] ok = new int[1]; GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs);
        if (ok[0] == 0) throw new IllegalStateException(GLES30.glGetProgramInfoLog(p));
        return p;
    }
    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        ready = false;
        allocatedWidth = allocatedHeight = 0;
        framebuffer = texture = 0;
        try {
            compute = program(FRAGMENT); blit = program(BLIT);
            int[] ids = new int[1]; GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0];
            GLES30.glBindVertexArray(vao);
            timeLoc = GLES30.glGetUniformLocation(compute, "clock");
            sizeLoc = GLES30.glGetUniformLocation(compute, "resolution");
            stepsLoc = GLES30.glGetUniformLocation(compute, "steps");
            phaseLoc = GLES30.glGetUniformLocation(compute, "phase");
            rendererName = GLES30.glGetString(GLES30.GL_RENDERER);
            fpsEpoch = SystemClock.elapsedRealtimeNanos(); frames = 0;
            ready = true; error = null;
        } catch (Exception ex) { error = "GPU 初始化失败：" + ex.getMessage(); Log.e("PowerLab", error, ex); }
    }
    @Override public void onSurfaceChanged(GL10 unused, int w, int h) { width = w; height = h; }
    private void allocate(int w, int h) {
        if (texture != 0) GLES30.glDeleteTextures(1, new int[]{texture}, 0);
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        int[] id = new int[1]; GLES30.glGenTextures(1, id, 0); texture = id[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, id, 0); framebuffer = id[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0);
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("离屏缓冲区不可用");
        allocatedWidth = w; allocatedHeight = h;
    }
    @Override public void onDrawFrame(GL10 unused) {
        long start = SystemClock.elapsedRealtimeNanos();
        if (!ready) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, width, height); GLES30.glClearColor(.04f,.08f,.12f,1); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        boolean work = active;
        int w = work ? targetWidth : 640, h = work ? targetHeight : 400;
        try {
            if (w != allocatedWidth || h != allocatedHeight) allocate(w, h);
            GLES30.glBindVertexArray(vao);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
            GLES30.glViewport(0, 0, w, h); GLES30.glUseProgram(compute);
            GLES30.glUniform2f(sizeLoc, w, h);
            GLES30.glUniform1f(timeLoc, (start-epoch)/1e9f);
            GLES30.glUniform1f(phaseLoc, (start % 1_000_000_000L) / 1e11f);
            GLES30.glUniform1i(stepsLoc, work ? iterations : 16);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, width, height); GLES30.glUseProgram(blit);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
            // Synchronize to count completed frames and avoid queuing excessive GPU work.
            GLES30.glFinish();
            int glError = GLES30.glGetError();
            if (glError != GLES30.GL_NO_ERROR) throw new IllegalStateException("OpenGL 错误 " + glError);
            if (work) {
                long now = SystemClock.elapsedRealtimeNanos(); frames++;
                if (now-fpsEpoch >= 1_000_000_000L) { fps = frames*1e9f/(now-fpsEpoch); frames=0; fpsEpoch=now; }
                if (frameLimit > 0) {
                    long rest = 1_000_000_000L/frameLimit-(now-start);
                    if (rest > 0) LockSupport.parkNanos(rest);
                }
            } else { frames = 0; fpsEpoch = start; fps = 0; }
        } catch (Exception ex) { ready = false; error = "GPU 运行失败："+ex.getMessage(); Log.e("PowerLab", error, ex); }
    }
}
