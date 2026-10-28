package dev.novacain.ntclocks.faces;

import java.util.Locale;

/**
 * Text shown from inside other apps' processes (SystemUI, the launcher), where derealization's own resources aren't
 * at hand: English, or Russian when the phone is in Russian.
 */
public final class L {
    private L() {
    }

    public static String t(String en, String ru) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }
}
