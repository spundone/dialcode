package dev.spundone.dialcode;

import android.app.Activity;
import android.content.SharedPreferences;
import android.app.RemoteInput;
import android.content.Intent;
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
    private SharedPreferences prefs;
    /** Setup wizard page on screen, or null. */
    private String page;
    /** Back gesture while a page is open (Android 13+ no longer routes it through onBackPressed). */
    private Object backCallback;
    /** Setup key page state. The key is typed on Dialcode's own keypad so no keyboard app ever sees it. */
    private String typedName = "";
    private final StringBuilder typedKey = new StringBuilder();
    /** Page to reopen after returning from the system text input screen. */
    private String resumePage;
    private static final int REQ_NAME = 1;

    private static final String STEP_WELCOME = "welcome", STEP_METHOD = "method", STEP_MAC = "mac",
            STEP_KEY = "key", STEP_TILES = "tiles", STEP_DONE = "done";

    /** While the "Sync from your computer" page is up, look for a pushed export every 2 s. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!STEP_MAC.equals(page) || accounts == null) return;
            try {
                String msg = Importer.run(MainActivity.this, accounts);
                if (msg != null) {
                    notice = msg;
                    requestTileUpdate();
                    if (!accounts.isEmpty()) {
                        if (setupDone()) {
                            closeSheet();
                            build();
                        } else {
                            show(STEP_TILES);
                        }
                        return;
                    }
                    show(STEP_MAC); // e.g. a password-protected export: show why
                    return;
                }
            } catch (Exception ignored) {
                // Locked mid-wait; onResume retries.
            }
            handler.postDelayed(this, 2000);
        }
    };

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
        prefs = getSharedPreferences("setup", MODE_PRIVATE);
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
        if (accounts != null && !setupDone()) {
            String step = prefs.getString("step", null);
            if (step == null && !accounts.isEmpty()) {
                // Installed before the wizard existed and already has accounts: nothing to set up.
                finishSetup();
            } else {
                if (step == null) step = STEP_WELCOME;
                boolean adding = step.equals(STEP_WELCOME) || step.equals(STEP_METHOD)
                        || step.equals(STEP_MAC) || step.equals(STEP_KEY);
                if (adding && !accounts.isEmpty()) step = STEP_TILES;
                show(step);
            }
        }
        if (resumePage != null && accounts != null) show(resumePage);
        resumePage = null;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_NAME || data == null) return;
        android.os.Bundle r = RemoteInput.getResultsFromIntent(data);
        CharSequence name = r == null ? null : r.getCharSequence("name");
        if (name != null) typedName = name.toString().trim();
    }

    /** Opens the watch's own text input (keyboard, voice, handwriting) for the account name. */
    private void askName() {
        RemoteInput in = new RemoteInput.Builder("name").setLabel("Account name").build();
        Intent i = new Intent("android.support.wearable.input.action.REMOTE_INPUT");
        i.putExtra("android.support.wearable.input.extra.REMOTE_INPUTS", new RemoteInput[] {in});
        i.putExtra("android.support.wearable.input.extra.TITLE", "Account name");
        resumePage = STEP_KEY;
        try {
            startActivityForResult(i, REQ_NAME);
        } catch (android.content.ActivityNotFoundException e) {
            resumePage = null;
            notice = "This watch has no text input.";
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (resumePage == null) clearTyped();
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
            empty.addView(text("Sync them from your computer, or type a setup key.", 12, pal.onSurfaceVariant, 400), wrap(0));
            list.addView(empty, wrap(10));
            ring.set(-1, false);
            addFooter();
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
        list.addView(new android.view.View(this), wrap(6));
        addFooter();
    }

    private void addFooter() {
        list.addView(pill("Sync from computer", pal.surfaceContainerHigh, pal.onSurface, v -> show(STEP_MAC)), wrap(6));
        list.addView(pill("Type a setup key", pal.surfaceContainerHigh, pal.onSurface, v -> show(STEP_KEY)), wrap(0));
    }

    // ---- Setup wizard ------------------------------------------------------------------------------

    private boolean setupDone() {
        return prefs.getBoolean("done", false);
    }

    private void finishSetup() {
        prefs.edit().putBoolean("done", true).remove("step").apply();
    }

    /** Shows one wizard page as a full-screen sheet. Pages reached from the list keep the list underneath. */
    private void show(String step) {
        if (!setupDone()) prefs.edit().putString("step", step).apply();
        handler.removeCallbacks(poll);
        boolean again = step.equals(page); // re-shown with a result, e.g. a rejected export
        LinearLayout box;
        switch (step) {
            case STEP_WELCOME:
                box = openSheet("Welcome to Dialcode");
                box.addView(body("Your 2FA codes on your wrist. Works offline, and accounts stay encrypted on this watch."), wrap(14));
                box.addView(pill("Get started", pal.primary, pal.onPrimary, v -> show(STEP_METHOD)), wrap(0));
                break;
            case STEP_METHOD:
                box = openSheet("Add your accounts");
                box.addView(body("Copy them from Proton Authenticator with your computer, or type a site's setup key here."), wrap(14));
                box.addView(pill("From my computer", pal.primary, pal.onPrimary, v -> show(STEP_MAC)), wrap(6));
                box.addView(pill("Type a setup key", pal.surfaceContainerHigh, pal.onSurface, v -> show(STEP_KEY)), wrap(6));
                box.addView(pill("Skip for now", 0, pal.onSurfaceVariant, v -> show(STEP_DONE)), wrap(0));
                break;
            case STEP_MAC:
                box = openSheet("Sync from your computer");
                box.addView(body("1. In Proton Authenticator, export your codes with no password.\n2. On your computer (Mac, Windows or Linux), run:"), wrap(8));
                TextView cmd = text("dialcode sync export.json", 13, pal.onSurface, 500);
                cmd.setTypeface(Typeface.MONOSPACE);
                cmd.setGravity(Gravity.CENTER);
                cmd.setPadding(dp(10), dp(10), dp(10), dp(10));
                cmd.setBackground(shape(pal.surfaceContainerHigh, 16));
                box.addView(cmd, wrap(10));
                if (again && notice != null) {
                    TextView n = text(notice, 12, pal.onTertiaryContainer, 500);
                    n.setGravity(Gravity.CENTER);
                    n.setPadding(dp(12), dp(8), dp(12), dp(8));
                    n.setBackground(shape(pal.tertiaryContainer, 16));
                    box.addView(n, wrap(10));
                }
                TextView wait = text("Waiting for your export…", 13, pal.primary, 600);
                wait.setGravity(Gravity.CENTER);
                box.addView(wait, wrap(12));
                box.addView(pill("Back", pal.surfaceContainerHigh, pal.onSurface, v -> back()), wrap(0));
                // Keep the screen on only while waiting, so the export lands while the user watches.
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                handler.postDelayed(poll, 2000);
                break;
            case STEP_KEY:
                box = openSheet("Type a setup key");
                TextView name = field(typedName.isEmpty() ? "Name, e.g. GitHub" : typedName, !typedName.isEmpty());
                name.setOnClickListener(v -> askName());
                box.addView(name, wrap(6));
                TextView key = field(typedKey.length() == 0 ? "Setup key" : groupKey(), typedKey.length() > 0);
                key.setTypeface(Typeface.MONOSPACE);
                key.setMaxLines(4);
                box.addView(key, wrap(8));
                TextView err = text("", 12, pal.error, 500);
                err.setGravity(Gravity.CENTER);
                err.setVisibility(android.view.View.GONE);
                box.addView(err, wrap(6));
                addKeypad(box, key);
                box.addView(body("Use the text key a site shows under its QR code. Codes are 6 digits every 30 seconds."), wrap(10));
                box.addView(pill("Add", pal.primary, pal.onPrimary, v -> addTyped(err)), wrap(6));
                box.addView(pill("Back", pal.surfaceContainerHigh, pal.onSurface, v -> {
                    clearTyped();
                    back();
                }), wrap(0));
                break;
            case STEP_TILES:
                box = openSheet("Pick codes for your tile");
                box.addView(body("The tile shows up to three. Change them later by long-pressing a code."), wrap(12));
                for (Account a : accounts) {
                    TextView t = pill((a.pinned ? "✓  " : "") + a.title(), a.pinned ? pal.primaryContainer : pal.surfaceContainerHigh,
                            a.pinned ? pal.onPrimaryContainer : pal.onSurface, null);
                    t.setSingleLine(true);
                    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    t.setOnClickListener(v -> {
                        a.pinned = !a.pinned;
                        persist();
                        requestTileUpdate();
                        int y = sheet.getScrollY();
                        show(STEP_TILES);
                        sheet.post(() -> sheet.scrollTo(0, y));
                    });
                    box.addView(t, wrap(6));
                }
                box.addView(pill("Next", pal.primary, pal.onPrimary, v -> show(STEP_DONE)), wrap(0));
                break;
            default: // STEP_DONE
                box = openSheet("You're all set");
                box.addView(body("To add the tile, swipe left from the watch face to the end of your tiles, tap +, and pick "
                        + "\u201cDialcode codes\u201d.\n\nTo update your accounts later, run dialcode sync again on your computer."), wrap(14));
                box.addView(pill("Show my codes", pal.primary, pal.onPrimary, v -> {
                    finishSetup();
                    closeSheet();
                    build();
                    refresh(System.currentTimeMillis());
                }), wrap(0));
                break;
        }
        page = step;
    }

    private void back() {
        if (setupDone()) {
            closeSheet();
            return;
        }
        switch (page) {
            case STEP_WELCOME: finish(); break;
            case STEP_METHOD: show(STEP_WELCOME); break;
            case STEP_DONE: show(accounts != null && !accounts.isEmpty() ? STEP_TILES : STEP_METHOD); break;
            default: show(STEP_METHOD); break;
        }
    }

    private void addTyped(TextView err) {
        Account a = null;
        String problem = null;
        if (typedName.isEmpty()) problem = "Give it a name: tap the name field.";
        else if (typedKey.length() < 16) problem = "That key looks too short. Setup keys are usually 16 or 32 characters.";
        else {
            try {
                a = Account.manual(typedName, typedKey.toString());
            } catch (IllegalArgumentException e) {
                problem = "That key isn't valid.";
            }
        }
        if (a != null) {
            for (Account b : accounts) {
                if (b.identity().equals(a.identity())) {
                    problem = "That key is already on this watch.";
                    break;
                }
            }
        }
        if (problem != null) {
            err.setText(problem);
            err.setVisibility(android.view.View.VISIBLE);
            return;
        }
        clearTyped();
        accounts.add(a);
        persist();
        requestTileUpdate();
        notice = "Added " + a.title() + ".";
        if (setupDone()) {
            closeSheet();
            build();
            refresh(System.currentTimeMillis());
        } else {
            show(STEP_TILES);
        }
    }

    private void clearTyped() {
        typedName = "";
        typedKey.setLength(0);
    }

    private String groupKey() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < typedKey.length(); i++) {
            if (i > 0 && i % 4 == 0) b.append(' ');
            b.append(typedKey.charAt(i));
        }
        return b.toString();
    }

    /** Base32 keypad: A to Z, 2 to 7 and backspace, six keys a row. */
    private void addKeypad(LinearLayout box, TextView display) {
        String keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567\b";
        LinearLayout row = null;
        for (int i = 0; i < keys.length(); i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, wrap(4));
            }
            char c = keys.charAt(i);
            boolean del = c == '\b';
            TextView k = text(del ? "⌫" : String.valueOf(c), 16, del ? pal.onPrimaryContainer : pal.onSurface, 600);
            k.setGravity(Gravity.CENTER);
            k.setMinHeight(dp(40));
            int bg = del ? pal.primaryContainer : pal.surfaceContainerHigh;
            k.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(bg, 14), shape(0xFFFFFFFF, 14)));
            k.setOnClickListener(v -> {
                if (del) {
                    if (typedKey.length() > 0) typedKey.setLength(typedKey.length() - 1);
                } else if (typedKey.length() < 128) {
                    typedKey.append(c);
                }
                boolean empty = typedKey.length() == 0;
                display.setText(empty ? "Setup key" : groupKey());
                display.setTextColor(empty ? pal.outline : pal.onSurface);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            row.addView(k, lp);
        }
    }

    private TextView body(String s) {
        TextView t = text(s, 13, pal.onSurfaceVariant, 400);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    /** A tappable field-shaped label. */
    private TextView field(String value, boolean filled) {
        TextView t = text(value, 14, filled ? pal.onSurface : pal.outline, 500);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(pal.surfaceContainerHigh, 24),
                shape(0xFFFFFFFF, 24)));
        return t;
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
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback == null) {
            android.window.OnBackInvokedCallback cb = () -> {
                if (page != null) back();
                else closeSheet();
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
        return box;
    }

    private void closeSheet() {
        page = null;
        handler.removeCallbacks(poll);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
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
        if (page != null) back();
        else if (sheet != null) closeSheet();
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
