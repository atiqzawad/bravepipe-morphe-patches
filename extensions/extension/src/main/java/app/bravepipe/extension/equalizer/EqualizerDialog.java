package app.bravepipe.extension.equalizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Built in code (no layout resources), so the patch does not have to touch the app's resources.
 * Every change is written to the shared preferences right away, the player picks it up through
 * its preference listener in {@link VideoEqualizer}.
 */
final class EqualizerDialog {
    private interface Updater {
        EqualizerSettings update(EqualizerSettings settings, int value);
    }

    private EqualizerDialog() {
    }

    static void show(final Activity activity, final SharedPreferences prefs) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad = Math.round(16 * density);

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        final SeekBar[] bars = new SeekBar[3];
        final TextView[] values = new TextView[3];
        final EqualizerSettings current = EqualizerSettings.load(prefs);

        bars[0] = addRow(activity, root, "Brightness", values, 0,
                EqualizerSettings.BRIGHTNESS_MIN, EqualizerSettings.BRIGHTNESS_MAX,
                current.getBrightness(), prefs, (s, v) -> s.withBrightness(v));
        bars[1] = addRow(activity, root, "Contrast", values, 1,
                EqualizerSettings.CONTRAST_MIN, EqualizerSettings.CONTRAST_MAX,
                current.getContrast(), prefs, (s, v) -> s.withContrast(v));
        bars[2] = addRow(activity, root, "Saturation", values, 2,
                EqualizerSettings.SATURATION_MIN, EqualizerSettings.SATURATION_MAX,
                current.getSaturation(), prefs, (s, v) -> s.withSaturation(v));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Video equalizer")
                .setView(root)
                .setNeutralButton("Reset", null) // replaced below so the dialog stays open
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    EqualizerSettings.defaults().save(prefs);
                    bars[0].setProgress(EqualizerSettings.BRIGHTNESS_DEFAULT
                            - EqualizerSettings.BRIGHTNESS_MIN);
                    bars[1].setProgress(EqualizerSettings.CONTRAST_DEFAULT
                            - EqualizerSettings.CONTRAST_MIN);
                    bars[2].setProgress(EqualizerSettings.SATURATION_DEFAULT
                            - EqualizerSettings.SATURATION_MIN);
                }));
        dialog.show();
    }

    private static SeekBar addRow(final Activity activity, final LinearLayout root,
                                  final String label, final TextView[] values, final int index,
                                  final int min, final int max, final int initial,
                                  final SharedPreferences prefs, final Updater updater) {
        final LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);

        final TextView name = new TextView(activity);
        name.setText(label);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(activity);
        values[index] = value;
        header.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        final SeekBar bar = new SeekBar(activity);
        // SeekBar#setMin needs API 26+, an offset works everywhere
        bar.setMax(max - min);
        bar.setProgress(initial - min);
        value.setText((initial) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(final SeekBar seekBar, final int progress,
                                          final boolean fromUser) {
                value.setText((progress + min) + "%");
                if (fromUser) {
                    updater.update(EqualizerSettings.load(prefs), progress + min).save(prefs);
                }
            }

            @Override
            public void onStartTrackingTouch(final SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(final SeekBar seekBar) {
            }
        });
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(8 * activity.getResources().getDisplayMetrics().density);
        root.addView(bar, params);
        return bar;
    }
}
