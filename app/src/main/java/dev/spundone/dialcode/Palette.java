package dev.spundone.dialcode;

import android.content.Context;

/**
 * Material 3 Expressive dark color roles. Read from the system's dynamic palette (Android 12+
 * system_accent / system_neutral tones), so the app follows the watch theme where One UI provides one.
 */
final class Palette {
    final int background = 0xFF000000;
    final int primary;            // accent1 200
    final int onPrimary;          // accent1 800
    final int primaryContainer;   // accent1 700
    final int onPrimaryContainer; // accent1 100
    final int tertiaryContainer;  // accent3 700
    final int onTertiaryContainer;// accent3 100
    final int surfaceContainer;   // neutral2 800 (tonal card)
    final int surfaceContainerHigh;
    final int onSurface;          // neutral1 50
    final int onSurfaceVariant;   // neutral2 200
    final int outline;            // neutral2 600
    final int error = 0xFFFFB4AB;          // M3 dark error roles (no dynamic error tones on Android)
    final int errorContainer = 0xFF93000A;
    final int onErrorContainer = 0xFFFFDAD6;

    Palette(Context c) {
        primary = c.getColor(android.R.color.system_accent1_200);
        onPrimary = c.getColor(android.R.color.system_accent1_800);
        primaryContainer = c.getColor(android.R.color.system_accent1_700);
        onPrimaryContainer = c.getColor(android.R.color.system_accent1_100);
        tertiaryContainer = c.getColor(android.R.color.system_accent3_700);
        onTertiaryContainer = c.getColor(android.R.color.system_accent3_100);
        surfaceContainer = c.getColor(android.R.color.system_neutral2_900);
        surfaceContainerHigh = c.getColor(android.R.color.system_neutral2_800);
        onSurface = c.getColor(android.R.color.system_neutral1_50);
        onSurfaceVariant = c.getColor(android.R.color.system_neutral2_200);
        outline = c.getColor(android.R.color.system_neutral2_600);
    }
}
