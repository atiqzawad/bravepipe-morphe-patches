package app.bravepipe.extension.equalizer;

import android.content.SharedPreferences;

import java.util.Arrays;

/**
 * Holds the video equalizer values (brightness, contrast, saturation), persists them in the
 * shared preferences and turns them into a 4x5 color matrix as used by
 * {@link android.graphics.ColorMatrix}.
 * <p>
 * All values are integer percentages so they map directly onto a {@link android.widget.SeekBar}.
 */
public final class EqualizerSettings {
    public static final String KEY_BRIGHTNESS = "brave_video_equalizer_brightness";
    public static final String KEY_CONTRAST = "brave_video_equalizer_contrast";
    public static final String KEY_SATURATION = "brave_video_equalizer_saturation";

    /** Brightness offset in percent of the full color range. 0 = unchanged. */
    public static final int BRIGHTNESS_MIN = -100;
    public static final int BRIGHTNESS_MAX = 100;
    public static final int BRIGHTNESS_DEFAULT = 0;

    /** Contrast factor in percent. 100 = unchanged. */
    public static final int CONTRAST_MIN = 0;
    public static final int CONTRAST_MAX = 200;
    public static final int CONTRAST_DEFAULT = 100;

    /** Saturation factor in percent. 100 = unchanged, 0 = grayscale. */
    public static final int SATURATION_MIN = 0;
    public static final int SATURATION_MAX = 200;
    public static final int SATURATION_DEFAULT = 100;

    // Luma weights, the same ones android.graphics.ColorMatrix#setSaturation() uses
    private static final float LUMA_R = 0.213f;
    private static final float LUMA_G = 0.715f;
    private static final float LUMA_B = 0.072f;

    private static final int MATRIX_SIZE = 5; // homogeneous 5x5, only the first 4 rows are used
    private static final float COLOR_RANGE = 255f;
    private static final float MID_GRAY = 0.5f;

    private final int brightness;
    private final int contrast;
    private final int saturation;

    public EqualizerSettings(final int brightness,
                                  final int contrast,
                                  final int saturation) {
        this.brightness = clamp(brightness, BRIGHTNESS_MIN, BRIGHTNESS_MAX);
        this.contrast = clamp(contrast, CONTRAST_MIN, CONTRAST_MAX);
        this.saturation = clamp(saturation, SATURATION_MIN, SATURATION_MAX);
    }

    public static EqualizerSettings defaults() {
        return new EqualizerSettings(
                BRIGHTNESS_DEFAULT, CONTRAST_DEFAULT, SATURATION_DEFAULT);
    }

    public static EqualizerSettings load(final SharedPreferences prefs) {
        return new EqualizerSettings(
                prefs.getInt(KEY_BRIGHTNESS, BRIGHTNESS_DEFAULT),
                prefs.getInt(KEY_CONTRAST, CONTRAST_DEFAULT),
                prefs.getInt(KEY_SATURATION, SATURATION_DEFAULT));
    }

    public void save(final SharedPreferences prefs) {
        prefs.edit()
                .putInt(KEY_BRIGHTNESS, brightness)
                .putInt(KEY_CONTRAST, contrast)
                .putInt(KEY_SATURATION, saturation)
                .apply();
    }

    public int getBrightness() {
        return brightness;
    }

    public int getContrast() {
        return contrast;
    }

    public int getSaturation() {
        return saturation;
    }

    public EqualizerSettings withBrightness(final int newBrightness) {
        return new EqualizerSettings(newBrightness, contrast, saturation);
    }

    public EqualizerSettings withContrast(final int newContrast) {
        return new EqualizerSettings(brightness, newContrast, saturation);
    }

    public EqualizerSettings withSaturation(final int newSaturation) {
        return new EqualizerSettings(brightness, contrast, newSaturation);
    }

    /**
     * Checks whether the video would be left untouched by these settings.
     *
     * @return true if every value is at its default
     */
    public boolean isDefault() {
        return brightness == BRIGHTNESS_DEFAULT
                && contrast == CONTRAST_DEFAULT
                && saturation == SATURATION_DEFAULT;
    }

    /**
     * Builds the combined color matrix. Order of application: saturation, then contrast, then
     * brightness.
     *
     * @return 20 floats in the layout expected by
     *         {@link android.graphics.ColorMatrix#set(float[])}
     */
    public float[] toColorMatrix() {
        final float[] result = multiply(
                brightnessMatrix(brightness / 100f),
                multiply(contrastMatrix(contrast / 100f), saturationMatrix(saturation / 100f)));
        return Arrays.copyOf(result, (MATRIX_SIZE - 1) * MATRIX_SIZE);
    }

    private static float[] identity() {
        final float[] m = new float[MATRIX_SIZE * MATRIX_SIZE];
        for (int i = 0; i < MATRIX_SIZE; i++) {
            m[i * MATRIX_SIZE + i] = 1f;
        }
        return m;
    }

    private static float[] saturationMatrix(final float saturationFactor) {
        final float inv = 1f - saturationFactor;
        final float[] m = identity();
        m[0] = inv * LUMA_R + saturationFactor;
        m[1] = inv * LUMA_G;
        m[2] = inv * LUMA_B;
        m[MATRIX_SIZE] = inv * LUMA_R;
        m[MATRIX_SIZE + 1] = inv * LUMA_G + saturationFactor;
        m[MATRIX_SIZE + 2] = inv * LUMA_B;
        m[2 * MATRIX_SIZE] = inv * LUMA_R;
        m[2 * MATRIX_SIZE + 1] = inv * LUMA_G;
        m[2 * MATRIX_SIZE + 2] = inv * LUMA_B + saturationFactor;
        return m;
    }

    // Scales every channel around mid gray so that mid gray itself stays untouched.
    private static float[] contrastMatrix(final float factor) {
        final float translate = MID_GRAY * (1f - factor) * COLOR_RANGE;
        final float[] m = identity();
        for (int row = 0; row < 3; row++) {
            m[row * MATRIX_SIZE + row] = factor;
            m[row * MATRIX_SIZE + 4] = translate;
        }
        return m;
    }

    private static float[] brightnessMatrix(final float offset) {
        final float[] m = identity();
        for (int row = 0; row < 3; row++) {
            m[row * MATRIX_SIZE + 4] = offset * COLOR_RANGE;
        }
        return m;
    }

    // Returns a * b for two 5x5 row-major matrices (so b is applied first).
    private static float[] multiply(final float[] a, final float[] b) {
        final float[] out = new float[MATRIX_SIZE * MATRIX_SIZE];
        for (int row = 0; row < MATRIX_SIZE; row++) {
            for (int col = 0; col < MATRIX_SIZE; col++) {
                float sum = 0f;
                for (int k = 0; k < MATRIX_SIZE; k++) {
                    sum += a[row * MATRIX_SIZE + k] * b[k * MATRIX_SIZE + col];
                }
                out[row * MATRIX_SIZE + col] = sum;
            }
        }
        return out;
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }
}
