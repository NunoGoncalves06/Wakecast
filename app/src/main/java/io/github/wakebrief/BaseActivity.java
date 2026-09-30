package io.github.wakebrief;

import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

/**
 * Every screen: light/dark from Settings, wallpaper colours (or the chosen colour), edge to
 * edge, a large collapsing top app bar and a scrolling column to fill.
 */
abstract class BaseActivity extends AppCompatActivity {

    Prefs p;
    Ui ui;
    MaterialToolbar toolbar;
    CollapsingToolbarLayout collapsing;
    NestedScrollView scroll;
    LinearLayout content;
    ExtendedFloatingActionButton fab;
    private String appliedColours;

    @Override
    protected void onCreate(Bundle state) {
        p = new Prefs(this);
        AppCompatDelegate.setDefaultNightMode(nightMode(p.theme()));
        super.onCreate(state);
        applyColours();
        appliedColours = colourKey();
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_page);
        toolbar = findViewById(R.id.toolbar);
        collapsing = findViewById(R.id.collapsing);
        scroll = findViewById(R.id.scroll);
        content = findViewById(R.id.content);
        fab = findViewById(R.id.fab);
        ui = new Ui(this);
    }

    static int nightMode(String theme) {
        if ("dark".equals(theme)) return AppCompatDelegate.MODE_NIGHT_YES;
        if ("light".equals(theme)) return AppCompatDelegate.MODE_NIGHT_NO;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The colours were changed on another screen (Appearance): rebuild this one too, so the
        // whole app matches without restarting it. Light/dark is handled by AppCompat itself.
        if (!colourKey().equals(appliedColours)) recreate();
    }

    /** Material You: the wallpaper's palette, or one generated from the chosen colour. */
    private void applyColours() {
        if (!DynamicColors.isDynamicColorAvailable()) return; // older phones keep the theme's
        if (p.dynamicColor()) {
            DynamicColors.applyToActivityIfAvailable(this);
        } else {
            DynamicColors.applyToActivityIfAvailable(this, new DynamicColorsOptions.Builder()
                    .setContentBasedSource(Ui.seed(p.heroColor(), p.heroCustom()))
                    .build());
        }
    }

    /** Everything the colours depend on; when it differs from what this screen was built with, rebuild. */
    private String colourKey() {
        return p.dynamicColor() + "/" + p.heroColor() + "/" + p.heroCustom();
    }

    void setTitleText(String title) {
        collapsing.setTitle(title);
    }

    /** Shows a back arrow that closes this screen. */
    void showBack() {
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationContentDescription("Back");
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /** Short message at the bottom (a Material snackbar). */
    void toast(String s) {
        Snackbar bar = Snackbar.make(findViewById(R.id.root), s, Snackbar.LENGTH_SHORT);
        if (fab.getVisibility() == android.view.View.VISIBLE) bar.setAnchorView(fab); // above the button
        bar.show();
    }
}
