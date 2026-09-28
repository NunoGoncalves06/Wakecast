package io.github.wakebrief;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * The app's design system, after the "Task Management & To-do List" Figma kit: a violet
 * accent on white, soft pastel glows behind everything, white cards with soft violet-tinted
 * shadows instead of borders, pastel icon tiles, "field cards" (small label over a bold value),
 * pill chips instead of steppers, and the Lexend Deca typeface. A matching dark palette keeps
 * the same shapes. Framework widgets only, no libraries.
 */
final class Ui {

    final Context ctx;
    final boolean dark;

    final int bg, surface, surface2, text, muted, outline;
    final int accent, onAccent, accentSoft;
    final int success, warn;
    final int heroStart, heroEnd;
    final int shadow;
    static final int HERO_TEXT = 0xFFFFFFFF;
    static final int HERO_MUTED = 0xD9FFFFFF;

    /** Tile colors from the kit's icon tiles (pink, violet, orange, blue, green, yellow). */
    static final int PINK = 0xFFF478B8, VIOLET = 0xFF9260F4, ORANGE = 0xFFFF9142,
            BLUE = 0xFF0087FF, GREEN = 0xFF1FB57A, YELLOW = 0xFFF5B400;

    private static Typeface regular, medium, semibold, bold;

    Ui(Context ctx, boolean dark) {
        this.ctx = ctx;
        this.dark = dark;
        if (dark) {
            bg = 0xFF100E1A;
            surface = 0xFF1C1A2B;
            surface2 = 0xFF26233A;
            text = 0xFFF3F1FA;
            muted = 0xFFA7A1C0;
            outline = 0xFF2E2A45;
            onAccent = 0xFFFFFFFF;
            success = 0xFF4ADE9A;
            warn = 0xFFFFA36B;
        } else {
            bg = 0xFFFBFAFF;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFF4F1FE;
            text = 0xFF24252C;
            muted = 0xFF6E6A7C;
            outline = 0xFFEDE8FB;
            onAccent = 0xFFFFFFFF;
            success = 0xFF1FA56B;
            warn = 0xFFE8742F;
        }
        // The whole app follows the color picked for the top card (Appearance).
        Prefs prefs = new Prefs(ctx);
        int[] g = heroGradient(prefs.heroColor(), prefs.heroHue());
        heroStart = g[0];
        heroEnd = g[1];
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(g[0], hsv);
        if (dark) { // lighter and softer so it reads on the dark background
            hsv[1] *= 0.8f;
            hsv[2] = Math.min(1f, hsv[2] * 1.3f + 0.1f);
        }
        accent = android.graphics.Color.HSVToColor(hsv);
        accentSoft = mix(accent, surface, dark ? 0.24f : 0.12f);
        shadow = dark ? 0xFF000000 : accent;
        loadFonts(ctx);
    }

    /** {@code a} at {@code amount} over {@code b}. */
    private static int mix(int a, int b, float amount) {
        int r = Math.round(((a >> 16) & 0xFF) * amount + ((b >> 16) & 0xFF) * (1 - amount));
        int gr = Math.round(((a >> 8) & 0xFF) * amount + ((b >> 8) & 0xFF) * (1 - amount));
        int bl = Math.round((a & 0xFF) * amount + (b & 0xFF) * (1 - amount));
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    /** Resolves the "system" / "light" / "dark" preference. */
    static boolean isDark(Context ctx, String pref) {
        if ("dark".equals(pref)) return true;
        if ("light".equals(pref)) return false;
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ type

    /** Lexend Deca (SIL OFL, bundled in assets/fonts), one variable file for every weight. */
    private static synchronized void loadFonts(Context ctx) {
        if (regular != null) return;
        regular = font(ctx, 400);
        medium = font(ctx, 500);
        semibold = font(ctx, 600);
        bold = font(ctx, 700);
    }

    private static Typeface font(Context ctx, int weight) {
        try {
            Typeface t = new Typeface.Builder(ctx.getAssets(), "fonts/LexendDeca.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
            // fall through to the system font
        }
        return Typeface.create(weight >= 600 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ drawables

    GradientDrawable shape(int fill, float radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    Drawable pressable(int fill, float radiusDp, int stroke) {
        int ripple = dark ? 0x33FFFFFF : 0x1A5F33E1;
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                shape(fill, radiusDp, stroke), shape(0xFFFFFFFF, radiusDp, 0));
    }

    /** A soft, slightly violet shadow, like the kit's cards (no hard borders). */
    <T extends View> T lift(T v, float elevationDp) {
        v.setElevation(dp(elevationDp));
        if (Build.VERSION.SDK_INT >= 28) {
            int a = dark ? 0x90 : 0x38;
            v.setOutlineAmbientShadowColor((shadow & 0x00FFFFFF) | (a << 24));
            v.setOutlineSpotShadowColor((shadow & 0x00FFFFFF) | (a << 24));
        }
        return v;
    }

    /** Pastel background of an icon tile in the given color. */
    int tint(int color) {
        return (color & 0x00FFFFFF) | (dark ? 0x33000000 : 0x1F000000);
    }

    /** The page background: soft mint / lavender / butter glows like the kit's screens. */
    Drawable glowBackground() {
        return new Glow(bg, dark
                ? new int[]{0x2A6C43F0, 0x1E1FB57A, 0x183C8BFF}
                : new int[]{0x3DB8F5D0, 0x40D9CCFF, 0x40FFF1B8});
    }

    private static final class Glow extends Drawable {
        private final int base;
        private final int[] colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // centre x, centre y, radius, as fractions of the width / height / width
        private static final float[][] SPOTS = {{0.05f, 0.10f, 0.85f}, {1.0f, 0.35f, 0.9f}, {0.2f, 0.95f, 0.9f}};

        Glow(int base, int[] colors) {
            this.base = base;
            this.colors = colors;
        }

        @Override public void draw(Canvas c) {
            c.drawColor(base);
            float w = getBounds().width(), h = getBounds().height();
            for (int i = 0; i < SPOTS.length; i++) {
                float cx = SPOTS[i][0] * w, cy = SPOTS[i][1] * h, r = SPOTS[i][2] * w;
                paint.setShader(new RadialGradient(cx, cy, r, colors[i], colors[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, paint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ------------------------------------------------------------------ text

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(regular);
        t.setLineSpacing(0, 1.18f);
        t.setIncludeFontPadding(false);
        return t;
    }

    TextView medium(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(medium);
        return t;
    }

    TextView bold(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(semibold);
        return t;
    }

    TextView heavy(CharSequence s, float sp, int color) {
        TextView t = text(s, sp, color);
        t.setTypeface(bold);
        return t;
    }

    /** Small grey label above a group of controls. */
    TextView label(String s) {
        TextView t = medium(s, 13, muted);
        t.setPadding(0, dp(18), 0, dp(10));
        return t;
    }

    TextView hint(String s) {
        TextView t = text(s, 12.5f, muted);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    // ------------------------------------------------------------------ icons

    ImageView icon(int res, int color, float sizeDp) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** A rounded pastel square with a colored icon, like the kit's task-group tiles. */
    LinearLayout tile(int res, int color, float sizeDp) {
        LinearLayout t = row();
        t.setGravity(Gravity.CENTER);
        t.setBackground(shape(tint(color), sizeDp * 0.3f, 0));
        t.addView(icon(res, color, sizeDp * 0.56f));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    // ------------------------------------------------------------------ containers

    /** A white section card: icon tile + title on top, soft shadow, no border. */
    LinearLayout card(int iconRes, int color, String title) {
        LinearLayout c = column();
        c.setBackground(shape(surface, 20, 0));
        lift(c, 3);
        c.setPadding(dp(18), dp(18), dp(18), dp(20));
        if (title != null) {
            LinearLayout h = row();
            h.addView(tile(iconRes, color, 36));
            TextView t = bold(title, 17, text);
            t.setPadding(dp(12), 0, 0, 0);
            h.addView(t);
            c.addView(h);
        }
        return c;
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

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    LinearLayout.LayoutParams gapTop(float topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    LinearLayout.LayoutParams wrapGapTop(float topDp) {
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    /**
     * The kit's form field: a soft card with an icon tile, a small grey label and the value
     * (text or an input) underneath, optionally a chevron when it opens a picker.
     */
    LinearLayout field(int iconRes, int color, String label, View value, boolean chevron) {
        LinearLayout f = row();
        f.setBackground(shape(surface2, 16, 0));
        f.setPadding(dp(12), dp(10), dp(14), dp(10));
        f.setMinimumHeight(dp(62));
        if (iconRes != 0) f.addView(tile(iconRes, color, 38));
        LinearLayout col = column();
        col.setPadding(iconRes != 0 ? dp(12) : dp(4), 0, 0, 0);
        TextView l = text(label, 11.5f, muted);
        l.setPadding(0, 0, 0, dp(3));
        col.addView(l);
        col.addView(value, matchWrap());
        f.addView(col, weight(1));
        if (chevron) f.addView(icon(R.drawable.ic_expand_more, text, 22));
        return f;
    }

    /** Icon tile, title and subtitle, and an optional view on the right (the kit's task rows). */
    LinearLayout listRow(int iconRes, int color, String title, String subtitle, View trailing) {
        LinearLayout r = row();
        r.addView(tile(iconRes, color, 40));
        LinearLayout texts = column();
        texts.setPadding(dp(12), 0, dp(10), 0);
        texts.addView(bold(title, 15, text));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12.5f, muted);
            sub.setPadding(0, dp(3), 0, 0);
            texts.addView(sub);
        }
        r.addView(texts, weight(1));
        if (trailing != null) r.addView(trailing);
        return r;
    }

    /** A field whose value is plain text (for pickers). */
    TextView fieldValue(String s) {
        return bold(s, 15, text);
    }

    /**
     * Keeps a text on one line within its width: shrinks it step by step down to {@code minSp},
     * and if even that is too wide (large font or display size), calls {@code onOverflow} so the
     * layout can make room. Re-checks whenever the view is laid out again or the text changes.
     */
    void fitText(TextView t, float maxSp, float minSp, Runnable onOverflow) {
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Paint measure = new Paint(t.getPaint());
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        t.addOnLayoutChangeListener((v, l, top, r, b, ol, ot, or, ob) -> {
            int avail = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
            if (avail <= 0) return;
            String s = t.getText().toString();
            float sp = maxSp;
            while (true) {
                measure.setTextSize(android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, sp, dm));
                if (measure.measureText(s) <= avail || sp <= minSp) break;
                sp -= 0.5f;
            }
            boolean fits = measure.measureText(s) <= avail;
            float px = measure.getTextSize();
            if (Math.abs(t.getTextSize() - px) > 0.5f) {
                float chosen = sp;
                t.post(() -> t.setTextSize(chosen)); // not during layout
            }
            if (!fits && onOverflow != null) t.post(onOverflow);
        });
        t.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { t.requestLayout(); }
        });
    }

    // ------------------------------------------------------------------ buttons

    /** Full-width violet button with a glow, like "Add Project" / "Let's Start". */
    TextView primaryButton(String label, View.OnClickListener onClick) {
        TextView b = bold(label, 16, onAccent);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        b.setBackground(pressable(accent, 16, 0));
        b.setOnClickListener(onClick);
        b.setElevation(dp(8));
        if (Build.VERSION.SDK_INT >= 28) {
            b.setOutlineSpotShadowColor(accent);
            b.setOutlineAmbientShadowColor(accent);
        }
        return b;
    }

    /** Lavender button with violet text, like the kit's "Change Logo". */
    TextView secondaryButton(String label, View.OnClickListener onClick) {
        TextView b = bold(label, 15, accent);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(50));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(pressable(accentSoft, 14, 0));
        b.setOnClickListener(onClick);
        return b;
    }

    TextView pill(String label, int fg, int fill, View.OnClickListener onClick) {
        TextView b = bold(label, 13.5f, fg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(36));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setBackground(pressable(fill, 12, 0));
        b.setOnClickListener(onClick);
        return b;
    }

    /** Puts an icon before a button's text. */
    <T extends TextView> T withIcon(T b, int res, int color) {
        Drawable d = ctx.getDrawable(res);
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
            int s = dp(20);
            d.setBounds(0, 0, s, s);
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        return b;
    }

    /** Small rounded status tag, like the kit's "Done" / "In Progress". */
    TextView badge(String s, int color) {
        TextView t = medium(s, 11.5f, color);
        t.setPadding(dp(9), dp(4), dp(9), dp(4));
        t.setBackground(shape(tint(color), 8, 0));
        return t;
    }

    // ------------------------------------------------------------------ inputs

    /** A bare input to place inside {@link #field}: the field card is its background. */
    EditText input(String hintText, boolean multiLine) {
        EditText e = new EditText(ctx);
        e.setHint(hintText);
        e.setTextSize(15);
        e.setTypeface(semibold);
        e.setTextColor(text);
        e.setHintTextColor((muted & 0x00FFFFFF) | 0xB3000000);
        e.setHighlightColor(accentSoft);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setMinLines(3);
        } else {
            e.setSingleLine(true);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(accent);
            cursor.setSize(dp(2), dp(20));
            e.setTextCursorDrawable(cursor);
        }
        return e;
    }

    Switch toggle(boolean checked, boolean onHero) {
        Switch s = new Switch(ctx);
        s.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        int on = onHero ? 0xFFFFFFFF : accent;
        int offThumb = onHero ? 0xFFE9E3FF : (dark ? 0xFFB9B3D6 : 0xFFFFFFFF);
        int onTrack = onHero ? 0x80FFFFFF : (accent & 0x00FFFFFF) | 0x66000000;
        int offTrack = onHero ? 0x40FFFFFF : (dark ? 0xFF3A3556 : 0xFFD9D3EE);
        s.setThumbTintList(new ColorStateList(states, new int[]{on, offThumb}));
        s.setTrackTintList(new ColorStateList(states, new int[]{onTrack, offTrack}));
        return s;
    }

    /** Icon tile, title and explanation, switch on the right; the whole row is tappable. */
    LinearLayout toggleRow(int iconRes, int color, String title, String subtitle, boolean checked,
                           IntConsumer onChange) {
        LinearLayout r = row();
        r.setPadding(0, dp(14), 0, 0);
        r.addView(tile(iconRes, color, 38));
        LinearLayout texts = column();
        texts.setPadding(dp(12), 0, dp(8), 0);
        texts.addView(bold(title, 15, text));
        TextView sub = text(subtitle, 12.5f, muted);
        sub.setPadding(0, dp(3), 0, 0);
        texts.addView(sub);
        r.addView(texts, weight(1));
        Switch s = toggle(checked, false);
        s.setOnCheckedChangeListener((b, isChecked) -> onChange.accept(isChecked ? 1 : 0));
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        return r;
    }

    // ------------------------------------------------------------------ chips

    /**
     * One-of-many choice as pill chips (the kit's "All / To do / In Progress" filter):
     * the chosen one violet, the rest lavender. Replaces steppers and segmented bars.
     */
    Flow options(String[] labels, int selected, IntConsumer onSelect) {
        Flow f = new Flow(ctx, dp(8));
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = chip(labels[i]);
            t.setOnClickListener(v -> {
                for (int j = 0; j < items.length; j++) styleChip(items[j], labels[j], j == idx);
                onSelect.accept(idx);
            });
            items[i] = t;
            f.addView(t);
        }
        for (int j = 0; j < items.length; j++) styleChip(items[j], labels[j], j == selected);
        return f;
    }

    TextView chip(String label) {
        TextView c = medium(label, 14, accent);
        c.setGravity(Gravity.CENTER);
        c.setMinHeight(dp(40));
        c.setMinWidth(dp(56));
        c.setPadding(dp(16), dp(9), dp(16), dp(9));
        return c;
    }

    void styleChip(TextView c, String label, boolean selected) {
        c.setText(label);
        c.setSelected(selected);
        c.setTextColor(selected ? onAccent : accent);
        c.setTypeface(selected ? semibold : medium);
        c.setBackground(pressable(selected ? accent : accentSoft, 12, 0));
    }

    // ------------------------------------------------------------------ top card colors

    /** The top card's color, picked in Appearance. "custom" uses a hue from a slider. */
    static final String[] HERO_KEYS = {"violet", "indigo", "ocean", "teal", "forest", "sunset", "rose", "graphite", "custom"};
    static final String[] HERO_NAMES = {"Violet", "Indigo", "Ocean", "Teal", "Forest", "Sunset", "Rose", "Graphite", "Custom"};
    private static final int[][] HERO_PRESETS = {
            {0xFF6A3DF0, 0xFF5B2FDC}, {0xFF4F46E5, 0xFF3730A3}, {0xFF1E88E5, 0xFF1565C0},
            {0xFF0F9F95, 0xFF0B7A72}, {0xFF2E9E5B, 0xFF1F7A45}, {0xFFF4743B, 0xFFE0457B},
            {0xFFE0457B, 0xFFB83280}, {0xFF3A3F4B, 0xFF22252D},
    };

    /** Start and end of the top card's gradient, always dark enough for its white text. */
    static int[] heroGradient(String key, int hue) {
        int i = java.util.Arrays.asList(HERO_KEYS).indexOf(key);
        int a, b;
        if ("custom".equals(key)) {
            a = android.graphics.Color.HSVToColor(new float[]{hue % 360, 0.70f, 0.90f});
            b = android.graphics.Color.HSVToColor(new float[]{(hue + 12) % 360, 0.80f, 0.74f});
        } else {
            int[] preset = HERO_PRESETS[i < 0 || i >= HERO_PRESETS.length ? 0 : i];
            a = preset[0];
            b = preset[1];
        }
        return new int[]{readableUnderWhite(a), readableUnderWhite(b)};
    }

    /** Darkens a color just enough that white text on it stays readable (about 3:1 contrast). */
    static int readableUnderWhite(int color) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(color, hsv);
        while (android.graphics.Color.luminance(color) > 0.30f && hsv[2] > 0.2f) {
            hsv[2] *= 0.94f;
            color = android.graphics.Color.HSVToColor(hsv);
        }
        return color;
    }

    // ------------------------------------------------------------------ progress

    /** A thin rounded progress bar (the kit's project cards). */
    LinearLayout progressBar(float fraction, int color) {
        LinearLayout track = row();
        track.setBackground(shape(dark ? 0x26FFFFFF : 0xFFEDE9F8, 4, 0));
        View fill = new View(ctx);
        fill.setBackground(shape(color, 4, 0));
        float f = Math.max(0f, Math.min(1f, fraction));
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(6), Math.max(f, 0.001f)));
        track.addView(new View(ctx), new LinearLayout.LayoutParams(0, dp(6), Math.max(1f - f, 0.001f)));
        return track;
    }

    /** The kit's circular percentage ring. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Optional white outline under the arc, so its color reads on any background. */
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean edged;
        private final RectF box = new RectF();
        private final float maxText;
        private float fraction;
        private String text = "";
        private String sub = "";

        Ring(Ui ui, int trackColor, int arcColor, int textColor) {
            super(ui.ctx);
            float stroke = ui.dp(7);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(trackColor);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(arcColor);
            label.setColor(textColor);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(semibold);
            maxText = ui.dp(15);
            caption.setColor((textColor & 0x00FFFFFF) | 0xCC000000);
            caption.setTextAlign(Paint.Align.CENTER);
            caption.setTypeface(regular);
            caption.setTextSize(ui.dp(10.5f));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke + ui.dp(3));
            edge.setStrokeCap(Paint.Cap.ROUND);
            edge.setColor(0xF2FFFFFF);
        }

        /** Colors the arc (e.g. by how much sleep is left) and gives it a thin white edge. */
        void setArcColor(int color) {
            arc.setColor(color);
            edged = true;
            invalidate();
        }

        void set(float fraction, String text) {
            set(fraction, text, "");
        }

        /** {@code sub} is a small caption drawn under the main text, inside the ring. */
        void set(float fraction, String text, String sub) {
            this.fraction = Math.max(0f, Math.min(1f, fraction));
            this.text = text;
            this.sub = sub == null ? "" : sub;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float inset = (edged ? edge.getStrokeWidth() : track.getStrokeWidth()) / 2 + 1;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawArc(box, 0, 360, false, track);
            if (fraction > 0) {
                if (edged) c.drawArc(box, -90, 360 * fraction, false, edge);
                c.drawArc(box, -90, 360 * fraction, false, arc);
            }

            // Shrink the text until it sits comfortably inside the ring.
            float room = (getWidth() - 2 * track.getStrokeWidth()) * 0.8f;
            float size = maxText;
            label.setTextSize(size);
            while (size > maxText * 0.6f && label.measureText(text) > room) {
                size -= 0.5f;
                label.setTextSize(size);
            }
            Paint.FontMetrics fm = label.getFontMetrics();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (sub.isEmpty()) {
                c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, label);
            } else {
                Paint.FontMetrics fs = caption.getFontMetrics();
                float textH = fm.descent - fm.ascent, subH = fs.descent - fs.ascent;
                float top = cy - (textH + subH) / 2;
                c.drawText(text, cx, top - fm.ascent, label);
                c.drawText(sub, cx, top + textH - fs.ascent, caption);
            }
        }
    }

    // ------------------------------------------------------------------ layout

    /** Wrapping row, for chips. */
    static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c, int gapPx) {
            super(c);
            gap = gapPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int maxW = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                // Children with a fixed size (e.g. color swatches) keep it; the rest wrap.
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int ws = lp != null && lp.width > 0
                        ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST);
                int hs = lp != null && lp.height > 0
                        ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                v.measure(ws, hs);
                int w = v.getMeasuredWidth();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += w + gap;
                rowH = Math.max(rowH, v.getMeasuredHeight());
            }
            int h = y + rowH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth();
                int h = v.getMeasuredHeight();
                if (x > 0 && x + w > maxW) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                v.layout(getPaddingLeft() + x, getPaddingTop() + y,
                        getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
        }
    }
}
