package org.telegram.ui.Components.DustEffect;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.messenger.R;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Locale;

class DustEffectRenderer {
    private final int particleSize;

    private final float[] mvpMatrix = new float[16];

    private SpawnShader spawnShader;

    private DustShader dustShader;

    private SpriteShader spriteShader;

    private ArrayList<DustEffect> effects;

    private Runnable onBecameIdleListener;

    private boolean initialized = false;

    public DustEffectRenderer(int particleSize) {
        this.particleSize = particleSize;
        this.effects = new ArrayList<>();
    }

    public void start(Point origin, Bitmap bitmap, Runnable onBecameVisible) {
        if (!initialized) {
            return;
        }
        DustEffect dustEffect = new DustEffect(origin, bitmap, onBecameVisible);
        effects.add(dustEffect);
    }

    public void init() {
        int[] version = new int[2];
        GLES31.glGetIntegerv(GLES31.GL_MAJOR_VERSION, version, 0);
        GLES31.glGetIntegerv(GLES31.GL_MINOR_VERSION, version, 1);
        FileLog.d("DustEffectRenderer, init OpenGL ES version is " + version[0] + "." + version[1]);

        if (version[0] < 3 || (version[0] == 3 && version[1] < 1)) {
            throw new RuntimeException("OpenGL ES version must be at least 3.1");
        }

        GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES31.glEnable(GLES31.GL_BLEND);
        GLES31.glBlendFunc(GLES31.GL_SRC_ALPHA, GLES31.GL_ONE_MINUS_SRC_ALPHA);

        spawnShader = new SpawnShader();
        spawnShader.use();
        spawnShader.setViewTextureSlot(0);
        spawnShader.setStride(particleSize);

        dustShader = new DustShader();
        dustShader.use();
        dustShader.setSize((float) particleSize);

        spriteShader = new SpriteShader();
        spriteShader.use();
        spriteShader.setSpriteTextureSlot(0);

        initialized = true;
        if (onBecameIdleListener != null) {
            onBecameIdleListener.run();
        }
    }

    public void resize(int width, int height) {
        GLES31.glViewport(0, 0, width, height);

        Matrix.orthoM(mvpMatrix, 0, 0.0f, (float) width, (float) height,
                0.0f, 1.0f, -1.0f);

        dustShader.use();
        dustShader.setMVP(mvpMatrix);

        spriteShader.use();
        spriteShader.setMVP(mvpMatrix);
    }

    public void render(float delta) {
        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);

        Object[] renderArray = effects.toArray();
        for (Object effect : renderArray) {
            ((DustEffect) effect).render(delta);
        }
    }

    public void dispose() {
        for (DustEffect effect : effects) {
            effect.dispose();
        }
        effects.clear();

        spawnShader.dispose();
        dustShader.dispose();
        spriteShader.dispose();

        initialized = false;
    }

    public void setOnBecameIdleListener(Runnable onBecameIdleListener) {
        this.onBecameIdleListener = onBecameIdleListener;
    }

    private void finishEffect(DustEffect effect) {
        effect.dispose();
        effects.remove(effect);

        if (effects.size() == 0 && onBecameIdleListener != null) {
            onBecameIdleListener.run();
        }
    }

    private static int createShader(int type, int resourceID) {
        int shader = GLES31.glCreateShader(type);
        checkGLErrors();

        GLES31.glShaderSource(shader, RLottieDrawable.readRes(null, resourceID));
        checkGLErrors();

        GLES31.glCompileShader(shader);
        checkGLErrors();

        int[] status = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, status, 0);
        if (status[0] == GLES31.GL_FALSE) {
            String shaderType;
            switch (type) {
                case GLES31.GL_VERTEX_SHADER:
                    shaderType = "Vertex";
                    break;
                case GLES31.GL_FRAGMENT_SHADER:
                    shaderType = "Fragment";
                    break;
                case GLES31.GL_COMPUTE_SHADER:
                    shaderType = "Compute";
                    break;
                default:
                    shaderType = "Unknown type";
            }

            String errorMsg = shaderType + " shader compilation error: "
                    + GLES31.glGetShaderInfoLog(shader);
            GLES31.glDeleteShader(shader);
            throw new RuntimeException(errorMsg);
        }

        return shader;
    }

    private static void checkGLErrors() {
        int err;
        if ((err = GLES31.glGetError()) != GLES31.GL_NO_ERROR) {
            throw new RuntimeException("DustEffectRenderer OpenGL ES error: " + err);
        }
    }

    private class DustEffect {
        private final Runnable onBecameVisibleCallback;

        private float totalTime = 0.0f;

        private long totalFrames = 0;

        private final Rect startRect;

        private int viewTexture;

        private int particleVAO;

        private int particleBuffer;

        private int particleColumns;

        private int particleRows;

        private int spriteVAO;

        private int spriteBuffer;

        private FloatBuffer spriteVerticesBB;

        public DustEffect(Point origin, Bitmap bitmap, Runnable onBecameVisibleCallback) {
            this.onBecameVisibleCallback = onBecameVisibleCallback;
            startRect = new Rect(origin.x, origin.y,
                    origin.x + bitmap.getWidth(), origin.y + bitmap.getHeight());

            createViewTexture(bitmap);
            createParticleBuffer();
            createSpriteBuffer();
        }

        public void render(float delta) {
            if (totalFrames == 1) {
                onBecameVisibleCallback.run();
            }

            totalTime += delta;
            totalFrames += 1;

            if (totalTime > 2.5f) {
                finishEffect(this);
                FileLog.d(String.format(Locale.ENGLISH, "DustEffectRenderer, " +
                                "effect finished, particle count: %d, avg. FPS: %f",
                        particleRows * particleColumns, (float) totalFrames / totalTime));
                return;
            }

            dustShader.use();
            dustShader.setWidth(particleColumns);
            dustShader.setHeight(particleRows);
            dustShader.setTime(totalTime);

            GLES31.glBindVertexArray(particleVAO);

            boolean drawSprite = true;
            float animationProgress = totalTime / 0.3f;
            if (animationProgress > 1.0f) {
                animationProgress = 1.0f;
                drawSprite = false;
            }

            int rightColumn = (int) Math.ceil(animationProgress * particleColumns);

            GLES31.glDrawArrays(GLES31.GL_POINTS, 0, rightColumn * particleRows);

            if (drawSprite) {
                spriteShader.use();

                spriteVerticesBB.position(8);
                spriteVerticesBB.put((float) (startRect.left + rightColumn * particleSize));
                spriteVerticesBB.put((float) (startRect.top));
                spriteVerticesBB.put((float) rightColumn / (float) particleColumns);
                spriteVerticesBB.put(0.0f);

                spriteVerticesBB.put((float) (startRect.left + rightColumn * particleSize));
                spriteVerticesBB.put((float) (startRect.bottom));
                spriteVerticesBB.put((float) rightColumn / (float) particleColumns);
                spriteVerticesBB.put(1.0f);
                spriteVerticesBB.position(8);

                GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, spriteBuffer);
                GLES31.glBufferSubData(GLES31.GL_ARRAY_BUFFER, 8 * 4, 8 * 4, spriteVerticesBB);

                GLES31.glBindVertexArray(spriteVAO);
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, viewTexture);

                GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);
            }
        }

        public void dispose() {
            int buffers[] = new int[]{
                    particleBuffer,
                    spriteBuffer,
            };
            GLES31.glDeleteBuffers(buffers.length, buffers, 0);

            int vao[] = new int[]{
                    particleVAO,
                    spriteVAO,
            };
            GLES31.glDeleteVertexArrays(vao.length, vao, 0);

            int textures[] = new int[]{
                    viewTexture,
            };
            GLES31.glDeleteTextures(textures.length, textures, 0);
        }

        private void createViewTexture(Bitmap bitmap) {
            int temp[] = new int[1];
            GLES31.glGenTextures(1, temp, 0);
            viewTexture = temp[0];
            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, viewTexture);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_LINEAR);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
            GLES31.glTexParameteri(GLES31.GL_TEXTURE_2D, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES31.GL_TEXTURE_2D, 0, bitmap, 0);
        }

        @SuppressLint("NewApi")
        private void createParticleBuffer() {
            int temp[] = new int[1];
            GLES31.glGenBuffers(1, temp, 0);
            particleBuffer = temp[0];

            particleColumns = startRect.width() / particleSize;
            particleRows = startRect.height() / particleSize;

            int particleCount = particleColumns * particleRows;

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, particleBuffer);
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, particleCount * 12 * 4, null,
                    GLES31.GL_STATIC_DRAW);

            spawnShader.use();
            spawnShader.setLeft((float) startRect.left + (float) particleSize / 2.0f);
            spawnShader.setTop((float) startRect.top + (float) particleSize / 2.0f);
            spawnShader.setWidth(particleColumns);
            spawnShader.setHeight(particleRows);

            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, particleBuffer);

            GLES31.glDispatchCompute((int) Math.ceil((double) particleColumns / 8.0),
                    (int) Math.ceil((double) particleRows / 8.0), 1);
            GLES31.glMemoryBarrier(GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

            GLES31.glGenVertexArrays(1, temp, 0);
            particleVAO = temp[0];

            GLES31.glBindVertexArray(particleVAO);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particleBuffer);
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(0, 4, GLES31.GL_FLOAT, false, 12 * 4, 0);
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 12 * 4, 4 * 4);
            GLES31.glEnableVertexAttribArray(2);
            GLES31.glVertexAttribPointer(2, 2, GLES31.GL_FLOAT, false, 12 * 4, 6 * 4);
            GLES31.glEnableVertexAttribArray(3);
            GLES31.glVertexAttribPointer(3, 1, GLES31.GL_FLOAT, false, 12 * 4, 8 * 4);
        }

        private void createSpriteBuffer() {
            int temp[] = new int[1];
            GLES31.glGenBuffers(1, temp, 0);
            spriteBuffer = temp[0];

            GLES31.glGenVertexArrays(1, temp, 0);
            spriteVAO = temp[0];

            GLES31.glBindVertexArray(spriteVAO);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, spriteBuffer);
            GLES31.glEnableVertexAttribArray(0);
            GLES31.glVertexAttribPointer(0, 2, GLES31.GL_FLOAT, false, 4 * 4, 0);
            GLES31.glEnableVertexAttribArray(1);
            GLES31.glVertexAttribPointer(1, 2, GLES31.GL_FLOAT, false, 4 * 4, 2 * 4);

            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, 4 * 4 * 4, null, GLES31.GL_DYNAMIC_DRAW);

            spriteVerticesBB = ByteBuffer
                    .allocateDirect(16 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();

            spriteVerticesBB.put((float) (startRect.right));
            spriteVerticesBB.put((float) (startRect.top));
            spriteVerticesBB.put(1.0f);
            spriteVerticesBB.put(0.0f);

            spriteVerticesBB.put((float) (startRect.right));
            spriteVerticesBB.put((float) (startRect.bottom));
            spriteVerticesBB.put(1.0f);
            spriteVerticesBB.put(1.0f);

            spriteVerticesBB.position(0);

            GLES31.glBufferSubData(GLES31.GL_ARRAY_BUFFER, 0, 8 * 4, spriteVerticesBB);
        }
    }

    private abstract class Shader {
        protected int program;

        public void use() {
            GLES31.glUseProgram(program);
        }

        public void dispose() {
            GLES31.glDeleteProgram(program);
        }
    }

    private class DustShader extends Shader {
        private int uMVP;

        private int uWidth;

        private int uHeight;

        private int uTime;

        private int uSize;

        public DustShader() {
            program = GLES31.glCreateProgram();

            int vertexShader = createShader(GLES31.GL_VERTEX_SHADER, R.raw.dust_vertex);
            int fragmentShader = createShader(GLES31.GL_FRAGMENT_SHADER, R.raw.dust_fragment);
            GLES31.glAttachShader(program, vertexShader);
            GLES31.glAttachShader(program, fragmentShader);
            GLES31.glLinkProgram(program);
            GLES31.glDeleteShader(vertexShader);
            GLES31.glDeleteShader(fragmentShader);

            int[] status = new int[1];
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String errorMsg = "Dust program linking error: "
                        + GLES31.glGetProgramInfoLog(program);
                GLES31.glDeleteProgram(program);
                throw new RuntimeException(errorMsg);
            }

            uMVP = GLES31.glGetUniformLocation(program, "uMVP");
            uWidth = GLES31.glGetUniformLocation(program, "uWidth");
            uHeight = GLES31.glGetUniformLocation(program, "uHeight");
            uTime = GLES31.glGetUniformLocation(program, "uTime");
            uSize = GLES31.glGetUniformLocation(program, "uSize");
        }

        public void setMVP(float mvp[]) {
            GLES31.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        }

        public void setTime(float time) {
            GLES31.glUniform1f(uTime, time);
        }

        public void setWidth(int width) {
            GLES31.glUniform1i(uWidth, width);
        }

        public void setHeight(int height) {
            GLES31.glUniform1i(uHeight, height);
        }

        public void setSize(float size) {
            GLES31.glUniform1f(uSize, size);
        }
    }

    private class SpriteShader extends Shader {
        private int uMVP;

        private int uSprite;

        public SpriteShader() {
            program = GLES31.glCreateProgram();

            int vertexShader = createShader(GLES31.GL_VERTEX_SHADER, R.raw.sprite_vertex);
            int fragmentShader = createShader(GLES31.GL_FRAGMENT_SHADER, R.raw.sprite_fragment);
            GLES31.glAttachShader(program, vertexShader);
            GLES31.glAttachShader(program, fragmentShader);
            GLES31.glLinkProgram(program);
            GLES31.glDeleteShader(vertexShader);
            GLES31.glDeleteShader(fragmentShader);

            int[] status = new int[1];
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String errorMsg = "Dust program linking error: "
                        + GLES31.glGetProgramInfoLog(program);
                GLES31.glDeleteProgram(program);
                throw new RuntimeException(errorMsg);
            }

            uMVP = GLES31.glGetUniformLocation(program, "uMVP");
            uSprite = GLES31.glGetUniformLocation(program, "uSprite");
        }

        public void setMVP(float mvp[]) {
            GLES31.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        }

        public void setSpriteTextureSlot(int slot) {
            GLES31.glUniform1i(uSprite, slot);
        }
    }

    private class SpawnShader extends Shader {
        private int uView;

        private int uLeft;

        private int uTop;

        private int uWidth;

        private int uHeight;

        private int uStride;

        public SpawnShader() {
            program = GLES31.glCreateProgram();
            checkGLErrors();

            int computeShader = createShader(GLES31.GL_COMPUTE_SHADER, R.raw.dust_spawn);
            GLES31.glAttachShader(program, computeShader);
            GLES31.glLinkProgram(program);
            GLES31.glDeleteShader(computeShader);

            int[] status = new int[1];
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) {
                String errorMsg = "Spawn program linking error: "
                        + GLES31.glGetProgramInfoLog(program);
                GLES31.glDeleteProgram(program);
                throw new RuntimeException(errorMsg);
            }

            uView = GLES31.glGetUniformLocation(program, "uView");
            uLeft = GLES31.glGetUniformLocation(program, "uLeft");
            uTop = GLES31.glGetUniformLocation(program, "uTop");
            uWidth = GLES31.glGetUniformLocation(program, "uWidth");
            uHeight = GLES31.glGetUniformLocation(program, "uHeight");
            uStride = GLES31.glGetUniformLocation(program, "uStride");
        }

        public void setViewTextureSlot(int slot) {
            GLES31.glUniform1i(uView, slot);
        }

        public void setLeft(float left) {
            GLES31.glUniform1f(uLeft, left);
        }

        public void setTop(float top) {
            GLES31.glUniform1f(uTop, top);
        }

        public void setWidth(int width) {
            GLES31.glUniform1ui(uWidth, width);
        }

        public void setHeight(int height) {
            GLES31.glUniform1ui(uHeight, height);
        }

        public void setStride(int stride) {
            GLES31.glUniform1ui(uStride, stride);
        }
    }
}
