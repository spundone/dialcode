package dev.spundone.dialcode;

import android.content.Context;

/**
 * Material 3 Expressive dark color roles: a fixed mint scheme with amber accents, tuned for an AMOLED
 * watch. Fixed rather than read from the system palette, because One UI Watch's dynamic tones are
 * low-chroma and wash the codes out. Tones follow the M3 dark mapping (80 / 20 / 30 / 90).
 */
final class Palette {
    final int background = 0xFF000000;
    final int primary = 0xFF5FE3B4;             // mint T80, slightly boosted chroma
    final int onPrimary = 0xFF00382A;           // T20
    final int primaryContainer = 0xFF00513D;    // T30
    final int onPrimaryContainer = 0xFF8FF8CF;  // T90
    final int tertiaryContainer = 0xFF5A4300;   // amber T30, for notices
    final int onTertiaryContainer = 0xFFFFDF9C; // amber T90
    final int surfaceContainer = 0xFF161D1A;    // neutral (mint-tinted) T10
    final int surfaceContainerHigh = 0xFF232B27;// T16
    final int onSurface = 0xFFE6EEEA;           // T93
    final int onSurfaceVariant = 0xFFB4C4BC;    // neutral-variant T78
    final int outline = 0xFF7C8B84;             // T56
    final int error = 0xFFFF8A7A;               // vivid coral for the last seconds
    final int errorContainer = 0xFF93000A;
    final int onErrorContainer = 0xFFFFDAD6;

    Palette(Context c) {}
}
