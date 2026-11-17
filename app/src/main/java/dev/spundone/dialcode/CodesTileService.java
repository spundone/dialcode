package dev.spundone.dialcode;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.ResolvableFuture;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.protolayout.expression.DynamicBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.Instant;
import java.util.List;

/**
 * Tile with up to three pinned codes. The system binds this service only to render the tile; it
 * returns the current code window plus the next one and asks to be called again when they run out.
 * The countdown arc is a renderer-side time expression, so nothing here runs every second.
 */
public final class CodesTileService extends TileService {
    private static final String RES_VERSION = "1";
    private static final int MAX = 3;
    private static final int RING_PERIOD = 30;

    @NonNull
    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
        ResolvableFuture<TileBuilders.Tile> f = ResolvableFuture.create();
        try {
            f.set(build());
        } catch (Exception e) {
            // Usually the watch was locked, so the Keystore key was unavailable. Retry soon instead of
            // showing this until the next vault change.
            f.set(message("Unlock the watch to see codes", 30_000));
        }
        return f;
    }

    @NonNull
    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(
            @NonNull RequestBuilders.ResourcesRequest request) {
        ResolvableFuture<ResourceBuilders.Resources> f = ResolvableFuture.create();
        f.set(new ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build());
        return f;
    }

    private TileBuilders.Tile build() throws Exception {
        List<Account> pinned = Vault.pinned(Vault.load(this), MAX);
        if (pinned.isEmpty()) return message("Long-press a code in Dialcode and pick “Show on tile”", 0);

        long now = System.currentTimeMillis();
        long t1 = nextChange(pinned, now);
        long t2 = nextChange(pinned, t1);
        boolean ring = false;
        for (Account a : pinned) ring |= a.window() == RING_PERIOD;

        TimelineBuilders.Timeline.Builder tl = new TimelineBuilders.Timeline.Builder();
        tl.addTimelineEntry(entry(pinned, now, now, t1, ring));
        if (t2 > t1) tl.addTimelineEntry(entry(pinned, t1, t1, t2, ring));
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setTileTimeline(tl.build())
                .setFreshnessIntervalMillis(Math.max(1000, t2 - now))
                .build();
    }

    /** Next instant any pinned code changes; Long.MAX_VALUE-safe for HOTP-only. */
    private static long nextChange(List<Account> accounts, long from) {
        long best = from + 3_600_000L;
        for (Account a : accounts) {
            int w = a.window();
            if (w == 0) continue;
            long ms = w * 1000L;
            best = Math.min(best, (from / ms + 1) * ms);
        }
        return best;
    }

    private TimelineBuilders.TimelineEntry entry(List<Account> pinned, long at, long start, long end, boolean ring) {
        Palette pal = new Palette(this);
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER);
        int n = pinned.size();
        int codeSp = n == 1 ? 32 : n == 2 ? 26 : 22;
        for (int i = 0; i < n; i++) {
            Account a = pinned.get(i);
            if (i > 0) col.addContent(new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4)).build());
            boolean first = i == 0;
            int bg = first ? pal.primaryContainer : pal.surfaceContainerHigh;
            int fg = first ? pal.onPrimaryContainer : pal.onSurface;
            int fgVar = first ? pal.onPrimaryContainer : pal.onSurfaceVariant;
            LayoutElementBuilders.Column card = new LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.dp(n == 3 ? 148 : 160))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                            .setBackground(new ModifiersBuilders.Background.Builder()
                                    .setColor(ColorBuilders.argb(bg))
                                    .setCorner(new ModifiersBuilders.Corner.Builder()
                                            .setRadius(DimensionBuilders.dp(n == 3 ? 18 : 24)).build())
                                    .build())
                            .setPadding(new ModifiersBuilders.Padding.Builder()
                                    .setTop(DimensionBuilders.dp(n == 3 ? 4 : 7))
                                    .setBottom(DimensionBuilders.dp(n == 3 ? 4 : 7))
                                    .setStart(DimensionBuilders.dp(10)).setEnd(DimensionBuilders.dp(10)).build())
                            .build())
                    .addContent(text(a.title(), 12, fgVar, LayoutElementBuilders.FONT_WEIGHT_MEDIUM))
                    .addContent(text(Account.group(a.code(at)), codeSp, fg, LayoutElementBuilders.FONT_WEIGHT_BOLD))
                    .build();
            col.addContent(card);
        }
        LayoutElementBuilders.Box.Builder root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(openApp());
        if (ring) {
            root.addContent(arc(null, pal.surfaceContainerHigh));
            root.addContent(arc(countdownDegrees(start), pal.primary));
        }
        root.addContent(col.build());
        return new TimelineBuilders.TimelineEntry.Builder()
                .setValidity(new TimelineBuilders.TimeInterval.Builder()
                        .setStartMillis(start).setEndMillis(end).build())
                .setLayout(new LayoutElementBuilders.Layout.Builder().setRoot(root.build()).build())
                .build();
    }

    /** Seconds left in this window * 12 degrees, evaluated by the tile renderer from its own clock. */
    private static DynamicBuilders.DynamicFloat countdownDegrees(long windowStart) {
        long endSec = (windowStart / 1000 / RING_PERIOD + 1) * RING_PERIOD;
        return DynamicBuilders.DynamicInstant.platformTimeWithSecondsPrecision()
                .durationUntil(DynamicBuilders.DynamicInstant.withSecondsPrecision(Instant.ofEpochSecond(endSec)))
                .toIntSeconds().asFloat().times(360f / RING_PERIOD);
    }

    /**
     * Arc centred on 12 o'clock that drains toward the top. Samsung's tile renderer centres a
     * dynamic-length line in its slot and ignores both angular alignment and dynamic anchors, so a
     * clockwise-from-top arc like the app's isn't possible here.
     */
    private static LayoutElementBuilders.LayoutElement arc(DynamicBuilders.DynamicFloat length, int color) {
        DimensionBuilders.DegreesProp len = length == null
                ? new DimensionBuilders.DegreesProp.Builder(360f).build()
                : new DimensionBuilders.DegreesProp.Builder(360f).setDynamicValue(length).build();
        LayoutElementBuilders.ArcLine line = new LayoutElementBuilders.ArcLine.Builder()
                .setLength(len)
                .setLayoutConstraintsForDynamicLength(
                        new DimensionBuilders.AngularLayoutConstraint.Builder(360f).build())
                .setThickness(DimensionBuilders.dp(5))
                .setStrokeCap(LayoutElementBuilders.STROKE_CAP_ROUND)
                .setColor(ColorBuilders.argb(color))
                .build();
        return new LayoutElementBuilders.Arc.Builder()
                .setAnchorAngle(DimensionBuilders.degrees(0))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_CENTER)
                .addContent(line)
                .build();
    }

    private TileBuilders.Tile message(String s, long retryMillis) {
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(openApp())
                .addContent(new LayoutElementBuilders.Text.Builder()
                        .setText(s)
                        .setMaxLines(4)
                        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                        .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                                .setSize(DimensionBuilders.sp(14))
                                .setColor(ColorBuilders.argb(new Palette(this).onSurfaceVariant)).build())
                        .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                                .setPadding(new ModifiersBuilders.Padding.Builder()
                                        .setAll(DimensionBuilders.dp(28)).build()).build())
                        .build())
                .build();
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .setFreshnessIntervalMillis(retryMillis)
                .build();
    }

    private ModifiersBuilders.Modifiers openApp() {
        return new ModifiersBuilders.Modifiers.Builder()
                .setClickable(new ModifiersBuilders.Clickable.Builder()
                        .setId("open")
                        .setOnClick(new ActionBuilders.LaunchAction.Builder()
                                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                                        .setPackageName(getPackageName())
                                        .setClassName(MainActivity.class.getName())
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static LayoutElementBuilders.LayoutElement text(String s, int sp, int color, int weight) {
        return new LayoutElementBuilders.Text.Builder()
                .setText(s)
                .setMaxLines(1)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
                        .setSize(DimensionBuilders.sp(sp))
                        .setWeight(weight)
                        .setColor(ColorBuilders.argb(color))
                        .build())
                .build();
    }
}
