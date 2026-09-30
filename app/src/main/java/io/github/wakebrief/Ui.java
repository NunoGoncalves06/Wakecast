package io.github.wakebrief;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.listitem.ListItemCardView;
import com.google.android.material.listitem.ListItemLayout;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Builds Material 3 Expressive components in code (segmented lists, connected button groups,
 * sliders, wavy progress, loading indicator), coloured from the theme: the wallpaper's colours
 * on Android 12+, or the colour picked in Settings.
 */
final class Ui {

    static final int FILLED = 0, TONAL = 1, TEXT = 2, OUTLINED = 3;

    final Context ctx;
    final int surface, onSurface, onSurfaceVariant, outline, primary, onPrimary,
            primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer, error;

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
        secondaryContainer = color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8);
        onSecondaryContainer = color(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B);
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

    /** Section header above a group, like the ones in the phone's Settings app. */
    TextView header(String s) {
        TextView t = text(s, com.google.android.material.R.attr.textAppearanceTitleSmall, primary);
        t.setPadding(dp(28), dp(24), dp(28), dp(8));
        return t;
    }

    /** Explanatory text under a group. */
    TextView note(String s) {
        TextView t = text(s, com.google.android.material.R.attr.textAppearanceBodyMedium, onSurfaceVariant);
        t.setPadding(dp(28), dp(8), dp(28), dp(8));
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
        card.setRadius(dp(28));
        LinearLayout col = column();
        card.addView(col, matchWrap());
        parent.addView(card, margins(16, 8));
        return col;
    }

    static MaterialCardView cardView(LinearLayout col) {
        return (MaterialCardView) col.getParent();
    }

    // ------------------------------------------------------------------ segmented lists

    /**
     * A segmented list (Material 3 Expressive): rows are separate rounded surfaces with small
     * gaps, and the group's outer corners are large. Rows get their shape from their position.
     */
    static final class Group extends LinearLayout {
        private final int gap;

        Group(Ui ui) {
            super(ui.ctx);
            setOrientation(VERTICAL);
            gap = ui.dp(2);
        }

        @Override public void onViewAdded(View child) {
            super.onViewAdded(child);
            refresh();
        }

        @Override public void onViewRemoved(View child) {
            super.onViewRemoved(child);
            refresh();
        }

        private void refresh() {
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                View c = getChildAt(i);
                if (c instanceof ListItemLayout) ((ListItemLayout) c).updateAppearance(i, n);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof MarginLayoutParams) ((MarginLayoutParams) lp).topMargin = i == 0 ? 0 : gap;
            }
        }
    }

    /** New segmented list added to {@code parent}. */
    Group group(ViewGroup parent) {
        Group g = new Group(this);
        parent.addView(g, margins(16, 0));
        return g;
    }

    /** One segment holding any view; tappable when {@code onClick} is set. */
    ListItemLayout segment(View inside, View.OnClickListener onClick) {
        ListItemLayout item = new ListItemLayout(ctx);
        ListItemCardView card = new ListItemCardView(ctx);
        card.addView(inside, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (onClick != null) card.setOnClickListener(onClick);
        else card.setClickable(false);
        item.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setLayoutParams(matchWrap());
        return item;
    }

    static ListItemCardView cardOf(View segment) {
        return (ListItemCardView) ((ViewGroup) segment).getChildAt(0);
    }

    ImageView icon(int res, int color) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return v;
    }

    /** Icon in a tonal circle, for rows that open another page. */
    View badge(int res) {
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(secondaryContainer);
        f.setBackground(d);
        ImageView i = icon(res, onSecondaryContainer);
        f.addView(i, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return f;
    }

    /**
     * A list row as a segment: leading icon (or any view), title, supporting text, trailing
     * view. Tappable when {@code onClick} is set.
     */
    ListItemLayout item(View leading, String title, String summary, View trailing, View.OnClickListener onClick) {
        LinearLayout r = row();
        r.setMinimumHeight(dp(40));
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
        return segment(r, onClick);
    }

    ListItemLayout item(int iconRes, String title, String summary, View trailing, View.OnClickListener onClick) {
        return item(iconRes == 0 ? null : icon(iconRes, onSurfaceVariant), title, summary, trailing, onClick);
    }

    /** Row that opens a page: icon in a tonal circle and a chevron. */
    ListItemLayout link(int iconRes, String title, String summary, View.OnClickListener onClick) {
        return item(badge(iconRes), title, summary, icon(R.drawable.ic_chevron_right, onSurfaceVariant), onClick);
    }

    /** Row with a switch; tapping the row flips it. */
    ListItemLayout switchItem(int iconRes, String title, String summary, boolean checked, IntConsumer onChange) {
        MaterialSwitch s = new MaterialSwitch(ctx);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> onChange.accept(on ? 1 : 0));
        return item(iconRes, title, summary, s, v -> s.toggle());
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

    /**
     * One-of-many choice as a connected button group (always exactly one selected), spread over
     * the full width. {@code icons} may be null.
     */
    MaterialButtonToggleGroup choices(String[] labels, int[] icons, int selected, IntConsumer onSelect) {
        MaterialButtonToggleGroup g = new MaterialButtonToggleGroup(ctx);
        g.setSingleSelection(true);
        g.setSelectionRequired(true);
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton b = new MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle);
            b.setId(ids[i] = View.generateViewId());
            b.setText(labels[i]);
            b.setCheckable(true);
            b.setMaxLines(1);
            if (icons != null && icons[i] != 0) b.setIconResource(icons[i]);
            g.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        g.check(ids[Math.max(0, Math.min(labels.length - 1, selected))]);
        g.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < ids.length; i++) if (ids[i] == id) onSelect.accept(i);
        });
        g.setLayoutParams(margins(16, 0));
        return g;
    }

    /**
     * Slider row as a segment: title and current value on top, the slider below. {@code onChange}
     * runs when the user moves it.
     */
    ListItemLayout slider(String title, int from, int to, int value, IntFunction<String> format, IntConsumer onChange) {
        LinearLayout col = column();
        LinearLayout top = row();
        top.addView(body(title), weight(1));
        TextView shown = text(format.apply(value), com.google.android.material.R.attr.textAppearanceTitleMedium, primary);
        top.addView(shown);
        col.addView(top, matchWrap());
        Slider s = new Slider(ctx);
        s.setValueFrom(from);
        s.setValueTo(Math.max(to, value));
        s.setStepSize(1);
        s.setValue(Math.max(from, value));
        s.setTickVisible(false);
        s.setLabelFormatter(v -> format.apply(Math.round(v)));
        s.setContentDescription(title);
        s.addOnChangeListener((sl, v, fromUser) -> {
            shown.setText(format.apply(Math.round(v)));
            if (fromUser) onChange.accept(Math.round(v));
        });
        col.addView(s, matchWrap());
        return segment(col, null);
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

    /** Wavy determinate progress bar (0-100). */
    LinearProgressIndicator progress() {
        LinearProgressIndicator p = new LinearProgressIndicator(ctx);
        p.setMax(100);
        p.setWaveAmplitude(dp(3));
        p.setWavelength(dp(40));
        p.setTrackThickness(dp(4));
        return p;
    }

    /** Material 3 Expressive loading indicator (morphing shapes), hidden until {@code show()}. */
    LoadingIndicator loading() {
        LoadingIndicator l = new LoadingIndicator(ctx);
        l.setIndicatorSize(dp(28));
        l.setVisibility(View.GONE);
        return l;
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

    /** The seed colour for a choice ("custom" uses {@code customArgb}, from the colour picker). */
    static int seed(String key, int customArgb) {
        if ("custom".equals(key)) return customArgb | 0xFF000000;
        int i = java.util.Arrays.asList(HERO_KEYS).indexOf(key);
        return HERO_SEEDS[i < 0 || i >= HERO_SEEDS.length ? 0 : i];
    }

    // ------------------------------------------------------------------ sleep ring

    /** Wavy circular progress indicator with a value and caption in the middle (see SleepGauge). */
    static final class Ring extends FrameLayout {
        private final CircularProgressIndicator arc;
        private final TextView value, caption;

        Ring(Ui ui, int sizeDp, int trackColor, int textColor) {
            super(ui.ctx);
            arc = new CircularProgressIndicator(ui.ctx);
            arc.setMax(1000);
            arc.setIndicatorSize(ui.dp(sizeDp));
            arc.setTrackThickness(ui.dp(8));
            arc.setTrackColor(trackColor);
            arc.setWaveAmplitude(ui.dp(2));
            arc.setWavelength(ui.dp(16));
            addView(arc, new FrameLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp), Gravity.CENTER));
            LinearLayout texts = ui.column();
            texts.setGravity(Gravity.CENTER);
            value = ui.text("", com.google.android.material.R.attr.textAppearanceTitleMedium, textColor);
            value.setGravity(Gravity.CENTER);
            value.setMaxLines(1);
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value, 10, 16, 1, TypedValue.COMPLEX_UNIT_SP);
            caption = ui.text("", com.google.android.material.R.attr.textAppearanceLabelSmall, textColor);
            caption.setGravity(Gravity.CENTER);
            texts.addView(value, new LinearLayout.LayoutParams(ui.dp(sizeDp - 26), ui.dp(22)));
            texts.addView(caption, Ui.wrap());
            addView(texts, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }

        void set(float fraction, int color, String text, String sub) {
            arc.setIndicatorColor(color);
            arc.setProgressCompat(Math.round(Math.max(0f, Math.min(1f, fraction)) * 1000), true);
            value.setText(text);
            caption.setText(sub == null ? "" : sub);
            caption.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ layout

    /** Keeps a one-line text inside its width by shrinking it, down to {@code minSp}. */
    void fitText(TextView t, float maxSp, float minSp) {
        t.setSingleLine(true);
        android.graphics.Paint measure = new android.graphics.Paint(t.getPaint());
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
