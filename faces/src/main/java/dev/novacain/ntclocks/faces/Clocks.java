package dev.novacain.ntclocks.faces;

import android.content.Context;

/** Список часов модуля: id (как в lock_screen_custom_clock_face), название и создание вида. */
public final class Clocks {
    public static final String DOTS = "NC_DOTS";
    public static final String STRETCH = "NC_STRETCH";
    public static final String ANALOG = "NC_ANALOG";
    public static final String IOS = "NC_IOS";
    public static final String COLOROS = "NC_COLOROS";
    public static final String ORIGIN = "NC_ORIGIN";
    /** Свои часы модуля. */
    public static final String[] OWN = {IOS, COLOROS, ORIGIN, DOTS, STRETCH, ANALOG};
    /** Стили crDroid: NC_CRD_<номер стиля в crDroid>. */
    public static final String CRD = "NC_CRD_";
    public static final String[] IDS = all();

    private static String[] all() {
        String[] a = new String[OWN.length + dev.novacain.ntclocks.faces.crd.CrdStyles.NUMBERS.length];
        System.arraycopy(OWN, 0, a, 0, OWN.length);
        for (int i = 0; i < dev.novacain.ntclocks.faces.crd.CrdStyles.NUMBERS.length; i++) {
            a[OWN.length + i] = CRD + dev.novacain.ntclocks.faces.crd.CrdStyles.NUMBERS[i];
        }
        return a;
    }

    public static boolean isCrd(String id) {
        return id != null && id.startsWith(CRD);
    }

    static int crdNumber(String id) {
        try {
            return Integer.parseInt(id.substring(CRD.length()));
        } catch (Exception e) {
            return 1;
        }
    }

    /** Красивое название стиля crDroid по имени разметки: keyguard_clock_ios3 → «iOS 3». */
    static String crdTitle(String id) {
        int n = crdNumber(id);
        String name = dev.novacain.ntclocks.faces.crd.CrdStyles.NAMES[dev.novacain.ntclocks.faces.crd.CrdClockViewIndex.of(n)]
                .replace("keyguard_", "").replace("anci_clockdate_", "").replace("anci_clock_", "").replace("clock_", "");
        String base = name.replaceAll("[0-9]+$", "");
        String num = name.substring(base.length());
        switch (base) {
            case "oos": base = "OOS"; break;
            case "ios": base = "iOS"; break;
            case "ide": base = "IDE"; break;
            case "miui": base = "MIUI"; break;
            case "nos": base = "NOS"; break;
            case "a": base = "A"; break;
            case "kln": base = "KLN"; break;
            case "sparkCircle": base = "Spark Circle"; break;
            case "sparkList": base = "Spark List"; break;
            default: base = Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
        return num.isEmpty() ? base : base + " " + num;
    }

    private Clocks() {
    }

    public static boolean isOurs(String id) {
        if (isCrd(id)) return true;
        for (String x : OWN) if (x.equals(id)) return true;
        return false;
    }

    public static String title(String id) {
        switch (id) {
            case DOTS: return "Точки";
            case STRETCH: return "Растяг";
            case ANALOG: return "Стрелки";
            case IOS: return "iOS";
            case COLOROS: return "ColorOS";
            case ORIGIN: return "Origin";
            default: return isCrd(id) ? crdTitle(id) : id;
        }
    }

    /** Значения по умолчанию для конкретных часов — чтобы каждые выглядели «как задумано» сразу. */
    public static ClockPrefs defaults(String id) {
        ClockPrefs p = new ClockPrefs();
        if (isCrd(id)) {
            p.hideSystemDate = true;               // почти все стили crDroid рисуют свою дату
            return p;
        }
        switch (id) {
            case IOS:
                p.weight = 600; p.stretchY = 1f; p.letterSpacing = 0f; p.leadingZero = false; p.glass = true;
                break;
            case COLOROS:
                p.weight = 600; p.stretchY = 1f; p.lineGap = 0f; p.twoTone = true; p.showDate = true;
                p.align = ClockPrefs.ALIGN_LEFT; p.hideSystemDate = true;
                break;
            case ORIGIN:
                p.weight = 150; p.stretchY = 1f; p.letterSpacing = 0.02f; p.showDate = true; p.hideSystemDate = true;
                break;
        }
        return p;
    }

    public static ClockFace create(Context context, String id, int face) {
        if (isCrd(id)) return new dev.novacain.ntclocks.faces.crd.CrdClockView(context, crdNumber(id), face);
        switch (id) {
            case STRETCH: return new StretchClockView(context, face);
            case ANALOG: return new AnalogClockView(context, face);
            case IOS: return new IosClockView(context, face);
            case COLOROS: return new ColorOsClockView(context, face);
            case ORIGIN: return new OriginClockView(context, face);
            default: return new DotClockView(context, face);
        }
    }
}
