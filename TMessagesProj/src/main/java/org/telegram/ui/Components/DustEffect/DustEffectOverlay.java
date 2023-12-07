package org.telegram.ui.Components.DustEffect;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class DustEffectOverlay extends GLSurfaceView {
    private DustEffectRenderer renderer;

    private Runnable onBecameIdleListener;

    private volatile boolean available;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public DustEffectOverlay(Context context) {
        super(context);

        available = supported();
        if (!available) {
            setVisibility(INVISIBLE);
            return;
        }

        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        getHolder().setFormat(PixelFormat.RGBA_8888);

        int particleSize = Math.round(AndroidUtilities.density);
        renderer = new DustEffectRenderer(2);

        onBecameIdleListener = () -> {
            setVisibility(INVISIBLE);
            FileLog.d("DustEffectOverlay, becoming invisible");
        };
        renderer.setOnBecameIdleListener(() -> {
            AndroidUtilities.runOnUIThread(onBecameIdleListener);
        });

        setRenderer(new RendererWrapper());
    }

    public boolean isAvailable() {
        return available;
    }

    public void start(Point origin, Bitmap bitmap, Runnable onBecameVisible) {
        if (!available) {
            return;
        }

        AndroidUtilities.cancelRunOnUIThread(onBecameIdleListener);
        if (getVisibility() == INVISIBLE) {
            setVisibility(VISIBLE);
            FileLog.d("DustEffectOverlay, becoming visible");
        }
        queueEvent(() -> {
            renderer.start(origin, bitmap, () -> {
                AndroidUtilities.runOnUIThread(onBecameVisible);
            });
        });
    }

    public void addToRootOf(View view) {
        if (!available) {
            return;
        }
        ViewGroup root = getRootView(view);
        if (root != null) {
            root.addView(this);
        }
    }

    public void detach() {
        ViewParent parent = getParent();

        if (parent != null) {
            ((ViewGroup) parent).removeView(this);
        }
    }

    private static ViewGroup getRootView(View view) {
        Activity activity = AndroidUtilities.findActivity(view.getContext());
        if (activity == null) {
            return null;
        }
        View rootView = activity.findViewById(android.R.id.content).getRootView();
        if (!(rootView instanceof ViewGroup)) {
            return null;
        }
        return (ViewGroup) rootView;
    }

    private class RendererWrapper implements Renderer {
        long lastTime = 0;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            FileLog.d("DustEffectOverlay, surface created");

            if (available) {
                try {
                    renderer.init();
                } catch (Exception e) {
                    FileLog.e("DustEffectOverlay error", e);
                    available = false;
                    AndroidUtilities.runOnUIThread(() -> {
                        setVisibility(INVISIBLE);
                        ViewParent parent = getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(DustEffectOverlay.this);
                        }
                    });
                }
            }
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            FileLog.d("DustEffectOverlay, surface changed");

            if (available) {
                try {
                    renderer.resize(width, height);
                } catch (Exception e) {
                    FileLog.e("DustEffectOverlay error", e);
                    available = false;
                    AndroidUtilities.runOnUIThread(() -> {
                        setVisibility(INVISIBLE);
                        ViewParent parent = getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(DustEffectOverlay.this);
                        }
                    });
                }
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (lastTime == 0) {
                lastTime = System.nanoTime();
            }

            long now = System.nanoTime();
            float delta = (float) ((now - lastTime) / 1_000_000_000.0);
            lastTime = now;

            if (delta > 1f / 15f) {
                delta = 1f / 15f;
            }

            if (available) {
                try {
                    renderer.render(delta);
                } catch (Exception e) {
                    FileLog.e("DustEffectOverlay error", e);
                    available = false;
                    AndroidUtilities.runOnUIThread(() -> {
                        setVisibility(INVISIBLE);
                        ViewParent parent = getParent();
                        if (parent instanceof ViewGroup) {
                            ((ViewGroup) parent).removeView(DustEffectOverlay.this);
                        }
                    });
                }
            }
        }
    }
}
