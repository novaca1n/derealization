package dev.novacain.ntclocks.faces.crd;

/** Номер стиля crDroid → позиция в CrdStyles. */
public final class CrdClockViewIndex {
    private CrdClockViewIndex() {
    }

    public static int of(int number) {
        return CrdClockView.indexOf(number);
    }
}
