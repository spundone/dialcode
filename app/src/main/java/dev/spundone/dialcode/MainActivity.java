package dev.spundone.dialcode;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.wear.tiles.TileService;

import java.util.ArrayList;
import java.util.List;

/**
 * The only screen. Codes are computed on the main thread once a second while resumed and nothing runs
 * once it is paused. Screenshots and the recents preview are blocked with FLAG_SECURE.
 */
public final class MainActivity extends Activity {
    private static final int RING_PERIOD = 30;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private List<Account> accounts;

    private Palette pal;
    private ScrollView scroll;
    private LinearLayout list;
    private RingView ring;
    private FrameLayout root;
    private ScrollView sheet;
    private String notice;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            refresh(now);
            handler.postDelayed(this, 1000 - (now % 1000) + 10);
        }
    };

    private static final class Row {
        Account account;
        TextView code;
        TextView chip;
        long window = Long.MIN_VALUE;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getResources().getBoolean(R.bool.secure_window)) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        root = new FrameLayout(this);
        pal = new Palette(this);
        root.setBackgroundColor(pal.background);
        ring = new RingView(this, pal);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int padH = (int) (dm.widthPixels * 0.115f);
        int padV = (int) (dm.heightPixels * 0.13f);
        list.setPadding(padH, padV, padH, padV);
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(ring, new FrameLayout.LayoutParams(-1, -1)); // above the list; it never takes touches
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        notice = null;
        try {
            accounts = Vault.load(this);
            notice = Importer.run(this, accounts);
            // Also unsticks a tile that was rendered while the watch was locked.
            requestTileUpdate();
        } catch (Exception e) {
            accounts = null;
            notice = "Can't open the vault. Unlock the watch and reopen.";
        }
        build();
        handler.post(tick);
        scroll.requestFocus();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        closeSheet();
        // Drop secrets and codes from memory and the view tree while off screen.
        rows.clear();
        list.removeAllViews();
        accounts = null;
        super.onStop();
    }

    private void build() {
        rows.clear();
        list.removeAllViews();
        TextView header = text(getString(R.string.app_name), 14, pal.primary, 600);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        list.addView(header, wrap(10));
        if (notice != null) {
            TextView n = text(notice, 13, pal.onTertiaryContainer, 500);
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setPadding(dp(14), dp(10), dp(14), dp(10));
            n.setBackground(shape(pal.tertiaryContainer, 26));
            list.addView(n, wrap(8));
        }
        if (accounts == null) return;
        if (accounts.isEmpty()) {
            LinearLayout empty = card(pal.surfaceContainer);
            empty.addView(text("No accounts yet", 16, pal.onSurface, 600), wrap(4));
            empty.addView(text("Sync a Proton Authenticator export from your Mac with scripts/push-to-watch.sh.",
                    12, pal.onSurfaceVariant, 400), wrap(0));
            list.addView(empty, wrap(0));
            ring.set(-1, false);
            return;
        }
        for (Account a : accounts) {
            Row r = new Row();
            r.account = a;
            int fg = a.pinned ? pal.onPrimaryContainer : pal.onSurface;
            int fgVar = a.pinned ? pal.onPrimaryContainer : pal.onSurfaceVariant;
            LinearLayout box = card(a.pinned ? pal.primaryContainer : pal.surfaceContainer);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(a.title(), 13, fgVar, 500);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.chip = text("", 11, a.pinned ? pal.primaryContainer : pal.onPrimary, 600);
            r.chip.setPadding(dp(7), dp(1), dp(7), dp(1));
            r.chip.setBackground(shape(a.pinned ? pal.onPrimaryContainer : pal.primary, 50));
            r.chip.setVisibility(android.view.View.GONE);
            top.addView(r.chip);
            box.addView(top, wrap(0));

            r.code = text("", 30, fg, 650);
            r.code.setFontFeatureSettings("tnum");
            r.code.setLetterSpacing(0.03f);
            box.addView(r.code, wrap(0));
            String sub = a.type == Account.HOTP && a.subtitle().isEmpty() ? "Tap for next code" : a.subtitle();
            if (!sub.isEmpty()) {
                TextView t = text(sub, 11, fgVar, 400);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                box.addView(t, wrap(0));
            }
            box.setOnClickListener(v -> onTap(r));
            box.setOnLongClickListener(v -> {
                onLongPress(r);
                return true;
            });
            list.addView(box, wrap(6));
            rows.add(r);
        }
    }

    private LinearLayout card(int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(11));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(color, 26), shape(0xFFFFFFFF, 26)));
        return box;
    }

    private GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void refresh(long now) {
        long sec = now / 1000;
        boolean hasRing = false;
        for (Row r : rows) {
            Account a = r.account;
            int w = a.window();
            long win = w == 0 ? a.counter : sec / w;
            if (win != r.window) {
                r.window = win;
                r.code.setText(Account.group(a.code(now)));
            }
            if (w == RING_PERIOD) {
                hasRing = true;
            } else if (w > 0) {
                r.chip.setVisibility(android.view.View.VISIBLE);
                r.chip.setText((w - sec % w) + "s");
            }
        }
        if (hasRing) {
            long left = RING_PERIOD - sec % RING_PERIOD;
            ring.set(left / (float) RING_PERIOD, left <= 5);
        } else {
            ring.set(-1, false);
        }
    }

    private void onTap(Row r) {
        if (r.account.type != Account.HOTP) return;
        r.account.counter++;
        persist();
        refresh(System.currentTimeMillis());
    }

    private void onLongPress(Row r) {
        Account a = r.account;
        LinearLayout box = openSheet(a.title());
        box.addView(pill(a.pinned ? "Remove from tile" : "Show on tile", pal.primary, pal.onPrimary, v -> {
            a.pinned = !a.pinned;
            changed();
        }), wrap(6));
        box.addView(pill("Move to top", pal.surfaceContainerHigh, pal.onSurface, v -> {
            accounts.remove(a);
            accounts.add(0, a);
            changed();
        }), wrap(6));
        box.addView(pill("Delete", pal.surfaceContainerHigh, pal.error, v -> confirmDelete(a)), wrap(6));
        box.addView(pill("Cancel", 0, pal.onSurfaceVariant, v -> closeSheet()), wrap(0));
    }

    private void confirmDelete(Account a) {
        LinearLayout box = openSheet("Delete " + a.title() + " from this watch?");
        box.addView(pill("Delete", pal.errorContainer, pal.onErrorContainer, v -> {
            accounts.remove(a);
            changed();
        }), wrap(6));
        box.addView(pill("Cancel", pal.surfaceContainerHigh, pal.onSurface, v -> closeSheet()), wrap(0));
    }

    private void changed() {
        closeSheet();
        persist();
        requestTileUpdate();
        build();
        refresh(System.currentTimeMillis());
    }

    /** Full-screen action sheet in the app's own M3E style (replaces the system AlertDialog). */
    private LinearLayout openSheet(String title) {
        closeSheet();
        sheet = new ScrollView(this);
        sheet.setVerticalScrollBarEnabled(false);
        sheet.setFillViewport(true);
        sheet.setBackgroundColor(pal.background);
        sheet.setClickable(true);
        sheet.setFocusable(true);
        sheet.setFocusableInTouchMode(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int padH = (int) (dm.widthPixels * 0.14f);
        int padV = (int) (dm.heightPixels * 0.14f);
        box.setPadding(padH, padV, padH, padV);
        TextView t = text(title, 16, pal.onSurface, 600);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setMaxLines(3);
        box.addView(t, wrap(12));
        sheet.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -1));
        sheet.requestFocus();
        return box;
    }

    private void closeSheet() {
        if (sheet == null) return;
        root.removeView(sheet);
        sheet = null;
        scroll.requestFocus();
    }

    private TextView pill(String label, int bg, int fg, android.view.View.OnClickListener click) {
        TextView b = text(label, 15, fg, 600);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.graphics.drawable.Drawable fill = bg == 0 ? null : shape(bg, 50);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, shape(0xFFFFFFFF, 50)));
        b.setOnClickListener(click);
        return b;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (sheet != null) closeSheet();
        else super.onBackPressed();
    }

    private void persist() {
        if (accounts == null) return;
        try {
            Vault.save(this, accounts);
        } catch (Exception e) {
            notice = "Couldn't save. Unlock the watch and try again.";
        }
    }

    private void requestTileUpdate() {
        TileService.getUpdater(this).requestUpdate(CodesTileService.class);
    }

    /** Rotating bezel / crown scrolls the list. */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            float delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
                    * ViewConfiguration.get(this).getScaledVerticalScrollFactor();
            (sheet != null ? sheet : scroll).smoothScrollBy(0, Math.round(delta));
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, weight, false));
        return t;
    }

    private LinearLayout.LayoutParams wrap(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomDp);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
