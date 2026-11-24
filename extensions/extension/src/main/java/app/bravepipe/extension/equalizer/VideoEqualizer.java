package app.bravepipe.extension.equalizer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Video equalizer (brightness, contrast, saturation) for BravePipe's player.
 * <p>
 * BravePipe renders video into a SurfaceView, which can not be color filtered. While any value
 * differs from its default, the video is therefore routed into a TextureView that gets a
 * ColorMatrix filter, and routed back into the SurfaceView once everything is reset.
 * <p>
 * The release APK is shrunk by R8, so this class only relies on things R8 can not remove:
 * fields are found by their <b>type</b>, and the ExoPlayer methods used are the ones the app
 * itself calls (setVideoSurface, clearVideoSurface, setVideoSurfaceHolder).
 * Every entry point is wrapped so that a mismatch never crashes the app, it only disables the
 * equalizer.
 */
@SuppressWarnings({"unused", "JavaReflectionMemberAccess"})
public final class VideoEqualizer {
    private static final String TAG = "BraveEqualizer";

    private static final String PLAYER_UI_TYPE = "org.schabi.newpipe.player.Player";
    private static final String BINDING_TYPE = "org.schabi.newpipe.databinding.PlayerBinding";
    private static final String SURFACE_VIEW_TYPE = "org.schabi.newpipe.views.ExpandableSurfaceView";
    private static final String HOLDER_CALLBACK_TYPE =
            "org.schabi.newpipe.player.playback.SurfaceHolderCallback";
    private static final String EXO_PLAYER_TYPE = "com.google.android.exoplayer2.ExoPlayer";
    private static final String EXO_PLAYER_BASE = "com.google.android.exoplayer2.Player";

    private static final Map<Object, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VideoEqualizer() {
    }

    // ---------------------------------------------------------------- hooks (called from smali)

    /**
     * Hook at the start of VideoPlayerUi.initPlayer(): a new ExoPlayer instance is about to be
     * used, so whatever was attached to the old one is gone.
     */
    public static void onInitPlayer(final Object ui) {
        try {
            final State state = stateFor(ui);
            if (state != null) {
                state.attachedExo = null;
            }
        } catch (final Throwable t) {
            Log.e(TAG, "onInitPlayer failed", t);
        }
    }

    /**
     * Hook at the start of VideoPlayerUi.setupVideoSurfaceIfNeeded().
     *
     * @return true if the equalizer owns the video output right now, so the original method must
     *         not attach the SurfaceView
     */
    public static boolean skipSurfaceSetup(final Object ui) {
        try {
            final State state = stateFor(ui);
            if (state == null) {
                return false;
            }
            return state.update();
        } catch (final Throwable t) {
            Log.e(TAG, "skipSurfaceSetup failed", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------------- state

    private static State stateFor(final Object ui) throws Exception {
        State state = STATES.get(ui);
        if (state == null) {
            final Object binding = fieldOfType(ui, BINDING_TYPE);
            final Object surface = binding == null ? null : fieldOfType(binding, SURFACE_VIEW_TYPE);
            if (!(surface instanceof SurfaceView)) {
                Log.w(TAG, "player layout not recognised, equalizer disabled");
                return null;
            }
            state = new State(ui, (SurfaceView) surface);
            STATES.put(ui, state);
        }
        return state;
    }

    private static final class State {
        private final WeakReference<Object> uiRef;
        private final WeakReference<SurfaceView> surfaceViewRef;
        private final SharedPreferences prefs;
        // keep strong: SharedPreferences only holds its listeners weakly
        @SuppressWarnings("FieldCanBeLocal")
        private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

        private TextureView textureView;
        private TextView button;
        private Surface textureSurface;
        private boolean active;
        // the ExoPlayer the TextureView currently feeds, null if none
        private WeakReference<Object> attachedExo;

        State(final Object ui, final SurfaceView surfaceView) {
            uiRef = new WeakReference<>(ui);
            surfaceViewRef = new WeakReference<>(surfaceView);
            final Context context = surfaceView.getContext();
            prefs = context.getSharedPreferences(
                    context.getPackageName() + "_preferences", Context.MODE_PRIVATE);

            createTextureView(surfaceView);
            createButton(surfaceView);

            prefsListener = (sharedPrefs, key) -> {
                if (key != null && key.startsWith("brave_video_equalizer_")) {
                    try {
                        update();
                    } catch (final Throwable t) {
                        Log.e(TAG, "update after settings change failed", t);
                    }
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        }

        /**
         * Brings the video output in line with the saved settings.
         *
         * @return true if the TextureView is the active output
         */
        boolean update() throws Exception {
            final SurfaceView surfaceView = surfaceViewRef.get();
            final Object ui = uiRef.get();
            if (surfaceView == null || ui == null || textureView == null) {
                return false;
            }

            final EqualizerSettings settings = EqualizerSettings.load(prefs);
            final Object exo = exoPlayerOf(ui);

            if (settings.isDefault()) {
                if (active) {
                    deactivate(surfaceView, exo);
                }
                active = false;
                return false;
            }

            final Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(
                    new ColorMatrix(settings.toColorMatrix())));
            textureView.setLayerPaint(paint);

            final boolean newExo = exo != null
                    && (attachedExo == null || attachedExo.get() != exo);
            if (!active || newExo) {
                activate(surfaceView, exo);
            }
            active = true;
            return true;
        }

        private void activate(final SurfaceView surfaceView, final Object exo) throws Exception {
            // the SurfaceView must stop feeding the player, or it would steal the output back
            // whenever its surface is recreated (rotation, app switch)
            final Object callback = fieldOfType(uiRef.get(), HOLDER_CALLBACK_TYPE);
            if (callback instanceof SurfaceHolder.Callback) {
                surfaceView.getHolder().removeCallback((SurfaceHolder.Callback) callback);
            }
            if (exo != null) {
                invoke(exo, "clearVideoSurface");
            }
            textureView.setVisibility(View.VISIBLE);
            attachedExo = exo == null ? null : new WeakReference<>(exo);
            if (exo != null && textureView.isAvailable()) {
                attachTextureSurface(exo, textureView.getSurfaceTexture());
            }
            // otherwise the SurfaceTextureListener attaches it as soon as it becomes available
        }

        private void deactivate(final SurfaceView surfaceView, final Object exo) throws Exception {
            if (exo != null) {
                invoke(exo, "setVideoSurface", Surface.class, null);
            }
            releaseTextureSurface();
            attachedExo = null;
            textureView.setVisibility(View.GONE);
            textureView.setLayerPaint(null);

            // hand the output back to the SurfaceView
            final Object callback = fieldOfType(uiRef.get(), HOLDER_CALLBACK_TYPE);
            if (callback instanceof SurfaceHolder.Callback) {
                final SurfaceHolder holder = surfaceView.getHolder();
                holder.addCallback((SurfaceHolder.Callback) callback);
                if (exo != null && holder.getSurface().isValid()) {
                    invoke(exo, "setVideoSurfaceHolder", SurfaceHolder.class, holder);
                }
            }
        }

        void attachTextureSurface(final Object exo, final SurfaceTexture texture) throws Exception {
            releaseTextureSurface();
            textureSurface = new Surface(texture);
            invoke(exo, "setVideoSurface", Surface.class, textureSurface);
        }

        void releaseTextureSurface() {
            if (textureSurface != null) {
                textureSurface.release();
                textureSurface = null;
            }
        }

        // ------------------------------------------------------------------ views

        private void createTextureView(final SurfaceView surfaceView) {
            final ViewParent parent = surfaceView.getParent();
            if (!(parent instanceof ViewGroup)) {
                return;
            }
            final ViewGroup group = (ViewGroup) parent;

            textureView = new TextureView(surfaceView.getContext());
            textureView.setVisibility(View.GONE);
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(final SurfaceTexture texture,
                                                      final int width, final int height) {
                    try {
                        final Object ui = uiRef.get();
                        final Object exo = ui == null ? null : exoPlayerOf(ui);
                        if (active && exo != null) {
                            attachTextureSurface(exo, texture);
                        }
                    } catch (final Throwable t) {
                        Log.e(TAG, "attaching texture surface failed", t);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(final SurfaceTexture texture,
                                                        final int width, final int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    try {
                        final Object ui = uiRef.get();
                        final Object exo = ui == null ? null : exoPlayerOf(ui);
                        if (exo != null && textureSurface != null) {
                            invoke(exo, "setVideoSurface", Surface.class, null);
                        }
                    } catch (final Throwable t) {
                        Log.e(TAG, "detaching texture surface failed", t);
                    }
                    releaseTextureSurface();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            });

            // same slot and layout rules as the SurfaceView, directly above it
            final ViewGroup.LayoutParams surfaceParams = surfaceView.getLayoutParams();
            final ViewGroup.LayoutParams params = surfaceParams instanceof RelativeLayout.LayoutParams
                    ? new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) surfaceParams)
                    : new ViewGroup.LayoutParams(surfaceParams);
            group.addView(textureView, group.indexOfChild(surfaceView) + 1, params);

            // The SurfaceView sizes itself (aspect ratio, resize mode, zoom scale) in its own
            // measure/layout. Mirror the result instead of re-implementing that logic.
            surfaceView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                   oldLeft, oldTop, oldRight, oldBottom) -> {
                final TextureView texture = textureView;
                if (texture == null) {
                    return;
                }
                final ViewGroup.LayoutParams lp = texture.getLayoutParams();
                final int width = right - left;
                final int height = bottom - top;
                if (lp.width != width || lp.height != height) {
                    lp.width = width;
                    lp.height = height;
                    texture.setLayoutParams(lp);
                }
                texture.setScaleX(view.getScaleX());
                texture.setScaleY(view.getScaleY());

                if (button != null) {
                    // the dialog needs an activity, the popup player lives in a service window
                    button.setVisibility(activityOf(view) != null ? View.VISIBLE : View.GONE);
                }
            });
        }

        private void createButton(final SurfaceView surfaceView) {
            final Context context = surfaceView.getContext();
            final int id = context.getResources()
                    .getIdentifier("secondaryControls", "id", context.getPackageName());
            final ViewParent parent = surfaceView.getParent();
            if (id == 0 || !(parent instanceof View)) {
                Log.w(TAG, "secondary controls not found, no equalizer button");
                return;
            }
            final View controls = ((View) parent).findViewById(id);
            if (!(controls instanceof LinearLayout)) {
                return;
            }

            final float density = context.getResources().getDisplayMetrics().density;
            final int pad = Math.round(6 * density);

            button = new TextView(context);
            button.setText("EQ");
            button.setTextColor(0xFFFFFFFF);
            button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
            button.setGravity(Gravity.CENTER);
            button.setMinWidth(Math.round(50 * density));
            button.setPadding(pad, pad, pad, pad);
            final TypedValue background = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, background, true)) {
                button.setBackgroundResource(background.resourceId);
            }
            button.setOnClickListener(v -> showDialog(surfaceView));

            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(35 * density));
            params.setMarginEnd(Math.round(8 * density));
            // after the first child (the resize button)
            ((LinearLayout) controls).addView(button, Math.min(1, ((LinearLayout) controls).getChildCount()),
                    params);
        }

        private void showDialog(final SurfaceView surfaceView) {
            final Activity activity = activityOf(surfaceView);
            if (activity == null) {
                return;
            }
            EqualizerDialog.show(activity, prefs);
        }
    }

    // ---------------------------------------------------------------------- helpers

    static Activity activityOf(final View view) {
        final ViewParent parent = view.getParent();
        // for the popup player the root view's parent is a ViewRootImpl, not a View
        if (!(parent instanceof ViewGroup)) {
            return null;
        }
        Context context = ((View) parent).getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Finds the app's ExoPlayer instance: UI -> Player -> field of type ExoPlayer. */
    private static Object exoPlayerOf(final Object ui) throws Exception {
        final Object player = fieldOfType(ui, PLAYER_UI_TYPE);
        return player == null ? null : fieldOfType(player, EXO_PLAYER_TYPE);
    }

    /** Value of the first field (searching superclasses too) whose type has exactly this name. */
    private static Object fieldOfType(final Object target, final String typeName) throws Exception {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Field field : c.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) {
                    field.setAccessible(true);
                    return field.get(target);
                }
            }
        }
        return null;
    }

    private static Object invoke(final Object exo, final String name) throws Exception {
        return method(exo, name).invoke(exo);
    }

    private static Object invoke(final Object exo, final String name,
                                 final Class<?> argType, final Object arg) throws Exception {
        return method(exo, name, argType).invoke(exo, arg);
    }

    private static Method method(final Object exo, final String name,
                                 final Class<?>... argTypes) throws Exception {
        // look the method up on the public interface, the implementation class is not public
        final Class<?> base = Class.forName(EXO_PLAYER_BASE, false, exo.getClass().getClassLoader());
        return base.getMethod(name, argTypes);
    }
}
