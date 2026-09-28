package io.github.wakebrief;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.function.IntConsumer;

/**
 * Small helpers that build standard Material 3 views in code, coloured from the theme
 * (wallpaper colours on Android 12+), so every screen looks like a stock Android app.
 */
final class Ui {

    static final int FILLED = 0, TONAL = 1, TEXT = 2, OUTLINED = 3;

    final Context ctx;
    final int surface, onSurface, onSurfaceVariant, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer, error;

    Ui(Context themed) {
        ctx = themed;
        surface = color(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
        onSurface = color(com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F);
        onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F);
        outline = color(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCAC4D0);
        primary = color(androidx.appcompat.R.attr.colorPrimary, 0xFF5F33E1);
        onPrimary = color(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF);
        primaryContainer = color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE7DEFF);
        onPrimaryContainer = color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF1D0060);
        error = color(androidx.appcompat.R.attr.colorError, 0xFFB3261E);
    }

    int color(int attr, int fallback) {
        return MaterialColors.getColor(ctx, attr, fallback);
    }

    int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ text

    /** A TextView in one of the theme's type styles, e.g. R.attr.textAppearanceTitleMedium. */
    TextView text(CharSequence s, int appearanceAttr, int color) {
        TextView t = new TextView(ctx);
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(appearanceAttr, tv, true)) {
            TextViewCompat.setTextAppearance(t, tv.resourceId);
        }
        t.setText(s);
        t.setTextColor(color);
        return t;
    }

    TextView title(CharSequence s) { return text(s, com.google.android.material.R.attr.textAppearanceTitleMedium, onSurface); }
    TextView body(CharSequence s) { return text(s, com.google.android.material.R.attr.textAppearanceBodyLarge, onSurface); }
    TextView supporting(CharSequence s) { return text(s, com.google.android.material.R.attr.textAppearanceBodyMedium, onSurfaceVariant); }

    /** Section header, like the ones in the phone's Settings app. */
    TextView header(String s) {
        TextView t = text(s, com.google.android.material.R.attr.textAppearanceLabelLarge, primary);
        t.setPadding(dp(16), dp(24), dp(16), dp(8));
        return t;
    }

    /** Explanatory text under a group, indented like list content. */
    TextView note(String s) {
        TextView t = text(s, com.google.android.material.R.attr.textAppearanceBodyMedium, onSurfaceVariant);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        return t;
    }

    // ------------------------------------------------------------------ containers

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    LinearLayout.LayoutParams margins(float h, float top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(dp(h), dp(top), dp(h), 0);
        return lp;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Material 3 filled card added to {@code parent}; returns the column to put things in. */
    LinearLayout card(ViewGroup parent) {
        MaterialCardView card = new MaterialCardView(ctx, null,
                com.google.android.material.R.attr.materialCardViewFilledStyle);
        LinearLayout col = column();
        card.addView(col, matchWrap());
        parent.addView(card, margins(16, 8));
        return col;
    }

    static MaterialCardView cardView(LinearLayout col) {
        return (MaterialCardView) col.getParent();
    }

    // ------------------------------------------------------------------ list items

    ImageView icon(int res, int color) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return v;
    }

    /**
     * A standard list item: leading icon (or any view), title, supporting text, trailing
     * view. Tappable when {@code onClick} is set.
     */
    LinearLayout item(View leading, String title, String summary, View trailing, View.OnClickListener onClick) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(summary == null ? 56 : 72));
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (leading != null) {
            LinearLayout.LayoutParams lp = leading.getLayoutParams() instanceof LinearLayout.LayoutParams
                    ? (LinearLayout.LayoutParams) leading.getLayoutParams() : wrap();
            lp.rightMargin = dp(16);
            r.addView(leading, lp);
        }
        LinearLayout texts = column();
        texts.addView(body(title));
        if (summary != null && !summary.isEmpty()) texts.addView(supporting(summary));
        r.addView(texts, weight(1));
        if (trailing != null) {
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = dp(12);
            r.addView(trailing, lp);
        }
        if (onClick != null) {
            r.setOnClickListener(onClick);
            r.setBackground(ripple());
        }
        return r;
    }

    LinearLayout item(int iconRes, String title, String summary, View trailing, View.OnClickListener onClick) {
        return item(iconRes == 0 ? null : icon(iconRes, onSurfaceVariant), title, summary, trailing, onClick);
    }

    /** List item with a switch; tapping the row flips it. */
    LinearLayout switchItem(int iconRes, String title, String summary, boolean checked, IntConsumer onChange) {
        MaterialSwitch s = new MaterialSwitch(ctx);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> onChange.accept(on ? 1 : 0));
        return item(iconRes, title, summary, s, v -> s.toggle());
    }

    Drawable ripple() {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return ctx.getDrawable(tv.resourceId);
    }

    // ------------------------------------------------------------------ controls

    MaterialButton button(String label, int kind, int iconRes, View.OnClickListener onClick) {
        int attr;
        switch (kind) {
            case TONAL: attr = com.google.android.material.R.attr.materialButtonTonalStyle; break;
            case OUTLINED: attr = com.google.android.material.R.attr.materialButtonOutlinedStyle; break;
            case TEXT: attr = androidx.appcompat.R.attr.borderlessButtonStyle; break;
            default: attr = com.google.android.material.R.attr.materialButtonStyle;
        }
        MaterialButton b = new MaterialButton(ctx, null, attr);
        b.setText(label);
        if (iconRes != 0) b.setIconResource(iconRes);
        b.setOnClickListener(onClick);
        return b;
    }

    Chip filterChip(String label) {
        Chip c = (Chip) LayoutInflater.from(ctx).inflate(R.layout.chip_filter, null, false);
        c.setText(label);
        return c;
    }

    /** One-of-many choice as filter chips (always exactly one selected). */
    ChipGroup choices(String[] labels, int selected, IntConsumer onSelect) {
        ChipGroup g = new ChipGroup(ctx);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        for (int i = 0; i < labels.length; i++) {
            Chip c = filterChip(labels[i]);
            c.setId(View.generateViewId());
            c.setChecked(i == selected);
            final int idx = i;
            c.setOnClickListener(v -> onSelect.accept(idx));
            g.addView(c);
        }
        g.setPadding(dp(16), 0, dp(16), 0);
        return g;
    }

    /** Outlined text field (its EditText is {@link #edit}). */
    TextInputLayout field(String label, String helper, boolean multiLine) {
        TextInputLayout l = new TextInputLayout(ctx, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        l.setHint(label);
        if (helper != null) l.setHelperText(helper);
        TextInputEditText e = new TextInputEditText(l.getContext());
        if (multiLine) {
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        l.addView(e, matchWrap());
        return l;
    }

    static EditText edit(TextInputLayout l) {
        return l.getEditText();
    }

    LinearProgressIndicator progress() {
        LinearProgressIndicator p = new LinearProgressIndicator(ctx);
        p.setMax(100);
        return p;
    }

    MaterialAlertDialogBuilder dialog() {
        return new MaterialAlertDialogBuilder(ctx);
    }

    // ------------------------------------------------------------------ colour choices

    /** Colour choices: seeds for the Material palette when not using the wallpaper's colours. */
    static final String[] HERO_KEYS = {"violet", "indigo", "ocean", "teal", "forest", "sunset", "rose", "graphite", "custom"};
    static final String[] HERO_NAMES = {"Violet", "Indigo", "Ocean", "Teal", "Forest", "Sunset", "Rose", "Graphite", "Custom"};
    private static final int[] HERO_SEEDS = {
            0xFF6A3DF0, 0xFF4F46E5, 0xFF1E88E5, 0xFF0F9F95, 0xFF2E9E5B, 0xFFF4743B, 0xFFE0457B, 0xFF3A3F4B,
    };

    /** The seed colour for a choice ("custom" uses {@code hue}). */
    static int seed(String key, int hue) {
        if ("custom".equals(key)) return android.graphics.Color.HSVToColor(new float[]{hue % 360, 0.70f, 0.90f});
        int i = java.util.Arrays.asList(HERO_KEYS).indexOf(key);
        return HERO_SEEDS[i < 0 || i >= HERO_SEEDS.length ? 0 : i];
    }

    // ------------------------------------------------------------------ sleep ring

    /** Circular gauge with a value and caption in the middle (see SleepGauge). */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float maxText;
        private float fraction;
        private String text = "", sub = "";

        /** {@code edgeColor} outlines the arc so its status colour reads on any background. */
        Ring(Ui ui, int trackColor, int textColor, int edgeColor) {
            super(ui.ctx);
            float stroke = ui.dp(7);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(trackColor);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke + ui.dp(3));
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setColor(edgeColor);
            label.setColor(textColor);
            label.setTextAlign(Paint.Align.CENTER);
            label.setFakeBoldText(true);
            maxText = ui.dp(15);
            caption.setColor((textColor & 0x00FFFFFF) | 0xCC000000);
            caption.setTextAlign(Paint.Align.CENTER);
            caption.setTextSize(ui.dp(10.5f));
        }

        void set(float fraction, int color, String text, String sub) {
            this.fraction = Math.max(0f, Math.min(1f, fraction));
            arc.setColor(color);
            this.text = text;
            this.sub = sub == null ? "" : sub;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float inset = edge.getStrokeWidth() / 2 + 1;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawArc(box, 0, 360, false, track);
            if (fraction > 0) {
                c.drawArc(box, -90, 360 * fraction, false, edge);
                c.drawArc(box, -90, 360 * fraction, false, arc);
            }
            float room = (getWidth() - 2 * edge.getStrokeWidth()) * 0.8f;
            float size = maxText;
            label.setTextSize(size);
            while (size > maxText * 0.6f && label.measureText(text) > room) {
                size -= 0.5f;
                label.setTextSize(size);
            }
            Paint.FontMetrics fm = label.getFontMetrics(), fs = caption.getFontMetrics();
            float cx = getWidth() / 2f, textH = fm.descent - fm.ascent;
            float subH = sub.isEmpty() ? 0 : fs.descent - fs.ascent;
            float top = getHeight() / 2f - (textH + subH) / 2;
            c.drawText(text, cx, top - fm.ascent, label);
            if (!sub.isEmpty()) c.drawText(sub, cx, top + textH - fs.ascent, caption);
        }
    }

    // ------------------------------------------------------------------ layout

    /** Keeps a one-line text inside its width by shrinking it, down to {@code minSp}. */
    void fitText(TextView t, float maxSp, float minSp) {
        t.setSingleLine(true);
        Paint measure = new Paint(t.getPaint());
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            int avail = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
            if (avail <= 0) return;
            String s = t.getText().toString();
            float sp = maxSp;
            while (true) {
                measure.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm));
                if (measure.measureText(s) <= avail || sp <= minSp) break;
                sp -= 0.5f;
            }
            if (Math.abs(t.getTextSize() - measure.getTextSize()) > 0.5f) {
                float chosen = sp;
                t.post(() -> t.setTextSize(chosen));
            }
        });
    }
}
