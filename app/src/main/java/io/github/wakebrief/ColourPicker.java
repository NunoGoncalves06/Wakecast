package io.github.wakebrief;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * "Pick any colour" dialog: a saturation/brightness square, a hue bar, a live preview and a
 * hex field for exact colours. The app then builds its whole Material palette from the pick.
 */
final class ColourPicker {

    private ColourPicker() {}

    static void show(BaseActivity a, int initial, IntConsumer onPick) {
        Ui ui = a.ui;
        float[] hsv = new float[3];
        Color.colorToHSV(initial | 0xFF000000, hsv);

        LinearLayout box = ui.column();
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);

        TextView preview = ui.text("", com.google.android.material.R.attr.textAppearanceTitleMedium, Color.WHITE);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(ui.dp(56));
        box.addView(preview, Ui.matchWrap());

        SvPanel sv = new SvPanel(ui);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(190));
        svLp.topMargin = ui.dp(16);
        box.addView(sv, svLp);

        HueBar hue = new HueBar(ui);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(36));
        hueLp.topMargin = ui.dp(16);
        box.addView(hue, hueLp);

        TextInputLayout hexField = ui.field("Hex code", "Or type an exact colour, e.g. 6A3DF0", false);
        hexField.setPrefixText("#");
        EditText hex = Ui.edit(hexField);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6), HEX_ONLY});
        LinearLayout.LayoutParams hexLp = Ui.matchWrap();
        hexLp.topMargin = ui.dp(16);
        box.addView(hexField, hexLp);

        boolean[] syncing = {false};
        Runnable refresh = () -> {
            int c = Color.HSVToColor(hsv);
            GradientDrawable d = new GradientDrawable();
            d.setColor(c);
            d.setCornerRadius(ui.dp(16));
            preview.setBackground(d);
            preview.setText(hexOf(c));
            preview.setTextColor(Color.luminance(c) > 0.5f ? 0xFF1C1B1F : Color.WHITE);
            sv.set(hsv[0], hsv[1], hsv[2]);
            hue.setHue(hsv[0]);
            String code = hexOf(c).substring(1);
            if (!code.equals(hex.getText().toString())) {
                syncing[0] = true;
                hex.setText(code);
                hex.setSelection(code.length());
                syncing[0] = false;
            }
        };
        sv.listener = (s, v) -> {
            hsv[1] = s;
            hsv[2] = v;
            refresh.run();
        };
        hue.listener = h -> {
            hsv[0] = h;
            refresh.run();
        };
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (syncing[0] || e.length() != 6) return;
                try {
                    Color.colorToHSV(0xFF000000 | Integer.parseInt(e.toString(), 16), hsv);
                    refresh.run();
                } catch (NumberFormatException ignored) {
                    // the filter only lets hex digits through
                }
            }
        });
        refresh.run();

        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);
        ui.dialog()
                .setTitle("Custom colour")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use colour", (dialog, which) -> onPick.accept(Color.HSVToColor(hsv)))
                .show();
    }

    static String hexOf(int argb) {
        return String.format(Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    /** Keeps only 0-9 and A-F, upper-cased. */
    private static final InputFilter HEX_ONLY = (src, start, end, dest, dstart, dend) -> {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            char ch = Character.toUpperCase(src.charAt(i));
            if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F')) sb.append(ch);
        }
        String kept = sb.toString();
        return kept.equals(src.subSequence(start, end).toString()) ? null : kept;
    };

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ saturation/brightness square

    interface SvListener { void onChange(float saturation, float brightness); }

    /** Left to right: grey to full colour. Top to bottom: bright to black. */
    static final class SvPanel extends View {
        SvListener listener;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float corner, radius;
        private float hue, sat, val;

        SvPanel(Ui ui) {
            super(ui.ctx);
            corner = ui.dp(16);
            radius = ui.dp(12);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ui.dp(3));
            ring.setColor(Color.WHITE);
            setContentDescription("Colour strength and brightness. Type a hex code below for an exact colour.");
        }

        void set(float h, float s, float v) {
            hue = h;
            sat = s;
            val = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            box.set(0, 0, w, h);
            fill.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE,
                    Color.HSVToColor(new float[]{hue, 1f, 1f}), Shader.TileMode.CLAMP));
            c.drawRoundRect(box, corner, corner, fill);
            fill.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, corner, corner, fill);
            fill.setShader(null);

            float x = clamp(sat * w, radius, w - radius);
            float y = clamp((1 - val) * h, radius, h - radius);
            dot.setColor(Color.HSVToColor(new float[]{hue, sat, val}));
            c.drawCircle(x, y, radius, dot);
            c.drawCircle(x, y, radius, ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true); // don't scroll the dialog
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    sat = clamp(e.getX() / getWidth(), 0f, 1f);
                    val = clamp(1f - e.getY() / getHeight(), 0f, 1f);
                    if (listener != null) listener.onChange(sat, val);
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }

    // ------------------------------------------------------------------ hue bar

    interface HueListener { void onChange(float hue); }

    /** The rainbow: drag to choose the base colour. */
    static final class HueBar extends View {
        private static final int[] HUES = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
        };
        HueListener listener;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final float radius, barHeight;
        private float hue;

        HueBar(Ui ui) {
            super(ui.ctx);
            radius = ui.dp(14);
            barHeight = ui.dp(14);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(ui.dp(3));
            ring.setColor(Color.WHITE);
            setContentDescription("Colour");
        }

        void setHue(float h) {
            hue = h;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            bar.set(radius, (h - barHeight) / 2, w - radius, (h + barHeight) / 2);
            fill.setShader(new LinearGradient(bar.left, 0, bar.right, 0, HUES, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(bar, barHeight / 2, barHeight / 2, fill);
            fill.setShader(null);

            float x = bar.left + (hue / 360f) * bar.width();
            dot.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            c.drawCircle(x, h / 2, radius, dot);
            c.drawCircle(x, h / 2, radius, ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    float span = Math.max(1f, getWidth() - 2 * radius);
                    hue = clamp((e.getX() - radius) / span, 0f, 1f) * 359.9f;
                    if (listener != null) listener.onChange(hue);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
