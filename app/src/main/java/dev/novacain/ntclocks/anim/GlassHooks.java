package dev.novacain.ntclocks.anim;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Прозрачность и размытие шторки (SystemUI) и меню приложений (лаунчер Nothing). Имена классов и методов —
 * из AOSP (у Nothing не обфусцированы): ScrimController.getDefaultScrimAlpha, BlurUtils.applyBlur,
 * AllAppsState.getWorkspaceScrimColor, BaseDepthController.applyDepthAndBlur. Ошибки — только в лог.
 */
public final class GlassHooks {
    private static volatile GlassPrefs prefs;

    private GlassHooks() {
    }

    static GlassPrefs prefs() {
        GlassPrefs p = prefs;
        if (p != null) return p;
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return new GlassPrefs();
        synchronized (GlassHooks.class) {
            if (prefs == null) {
                prefs = GlassPrefs.read(app.getContentResolver());
                try {
                    app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(GlassPrefs.KEY), false,
                            new ContentObserver(new Handler(Looper.getMainLooper())) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    prefs = GlassPrefs.read(app.getContentResolver());
                                }
                            });
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: подписка на стекло — " + t);
                }
            }
            return prefs;
        }
    }

    private static float px(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }

    // ───────── шторка и быстрые настройки ─────────

    private static int scrimBehindId;

    public static void shade(ClassLoader cl) {
        // подложка шторки: Nothing держит её непрозрачной (1.0), с размытием AOSP сам делает её полупрозрачной
        Class<?> scrim = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.phone.ScrimController", cl);
        if (scrim != null) {
            Hook.all(scrim, "getDefaultScrimAlpha", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (g.enabled && p.getResult() instanceof Float && (Float) p.getResult() >= 0.99f) p.setResult(g.shadeAlpha);
                }
            });
        }
        // развёрнутые быстрые настройки: при «обрезанной» подложке (mClipsQsScrim) AOSP ставит ей непрозрачность ровно 1 —
        // это и есть чёрный фон под плитками. После расчёта состояния ограничиваем её своей.
        if (scrim != null) {
            Hook.all(scrim, "applyState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled) return;
                    try {
                        String st = String.valueOf(XposedHelpers.getObjectField(p.thisObject, "mState"));
                        if (!"UNLOCKED".equals(st) && !"SHADE_LOCKED".equals(st)) return;
                        float behind = XposedHelpers.getFloatField(p.thisObject, "mBehindAlpha");
                        if (behind > g.shadeAlpha) XposedHelpers.setFloatField(p.thisObject, "mBehindAlpha", g.shadeAlpha);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: подложка быстрых настроек — " + t);
                    }
                }
            });
        }
        // размытие: SystemUI считает радиус от своего максимума (23 px) — пересчитываем к своему
        Class<?> blur = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.BlurUtils", cl);
        if (blur != null) {
            Hook.all(blur, "applyBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled || p.args.length < 3 || !(p.args[1] instanceof Integer)) return;
                    try {
                        float max = (Float) XposedHelpers.callMethod(p.thisObject, "getMaxBlurRadius");
                        Context c = AndroidAppHelper.currentApplication();
                        if (max > 0 && c != null) p.args[1] = Math.round((Integer) p.args[1] / max * px(c, g.shadeBlur));
                        if (g.shadeAlpha < 0.99f) p.args[2] = false;      // окно не «непрозрачное» — иначе размытие не рисуется
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: размытие шторки — " + t);
                    }
                }
            });
        }
        // зерно поверх подложки шторки — сила по её текущей видимости (mViewAlpha), только у основной подложки
        Class<?> scrimView = XposedHelpers.findClassIfExists("com.android.systemui.scrim.ScrimView", cl);
        if (scrimView != null) {
            Hook.all(scrimView, "onDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled || g.shadeGrain <= 0f) return;
                    View v = (View) p.thisObject;
                    if (scrimBehindId == 0) {
                        int id = v.getResources().getIdentifier("scrim_behind", "id", v.getContext().getPackageName());
                        scrimBehindId = id != 0 ? id : -1;
                    }
                    if (v.getId() != scrimBehindId) return;            // числовой id — без поиска имени ресурса в кадре
                    float a = XposedHelpers.getFloatField(v, "mViewAlpha");
                    if (a > 0.01f) LucentBlur.grain((Canvas) p.args[0], g.shadeGrain * Math.min(1f, a / Math.max(0.05f, g.shadeAlpha)));
                }
            });
        }
        hookGlassDraw();
        // панель громкости — стекло (отдельное окно: размывается всё, что за ним)
        Class<?> vol = XposedHelpers.findClassIfExists("com.android.systemui.volume.VolumeDialogImpl", cl);
        if (vol != null) {
            Hook.all(vol, "showH", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    View root = (View) XposedHelpers.getObjectField(p.thisObject, "mDialogView");
                    GlassPrefs g = prefs();
                    if (root == null) return;
                    // громкость как на iPhone — панель Nothing невидима, стекло под ней мелькало бы «призраком»
                    boolean on = g.enabled && !AnimPrefs.IOS.equals(AnimHooks.prefs().volume);
                    Runnable r = () -> glassify(root, on, g.panelAlpha, g.shadeBlur * 0.6f, g.shadeGrain);
                    root.post(r);
                    root.postDelayed(r, 250);
                }
            });
        }
        // уведомления как в One UI: все углы большие (у Nothing соседние «склеены» углом 4dp) и зазор между карточками
        XC_MethodHook dims = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                GlassPrefs g = prefs();
                if (!g.notifRound) return;
                android.content.res.Resources r = (android.content.res.Resources) p.thisObject;
                int id = (Integer) p.args[0];
                if (notifIds == null) {
                    int[] ids = {r.getIdentifier("notification_corner_radius", "dimen", "com.android.systemui"),
                            r.getIdentifier("notification_corner_radius_small", "dimen", "com.android.systemui"),
                            r.getIdentifier("notification_divider_height", "dimen", "com.android.systemui")};
                    if (ids[0] == 0) return;
                    notifIds = ids;
                }
                float dp = id == notifIds[0] || id == notifIds[1] ? g.notifRadius : id == notifIds[2] ? g.notifGap : -1f;
                if (dp < 0f) return;
                float v = dp * r.getDisplayMetrics().density;
                p.setResult(p.getResult() instanceof Float ? (Object) v : (Object) Math.round(v));
            }
        };
        for (String m : new String[]{"getDimension", "getDimensionPixelSize", "getDimensionPixelOffset"}) {
            XposedHelpers.findAndHookMethod(android.content.res.Resources.class, m, int.class, dims);
        }
        XposedBridge.log("ntweaker: стекло шторки подключено" + (scrim == null ? " (без ScrimController)" : "") + (blur == null ? " (без BlurUtils)" : ""));
    }

    // ───────── меню приложений ─────────

    /** Цвета виджетов Nothing поменялись (ColorEngine в SystemUI) — лаунчер перезапускается, чтобы виджеты перечитали ресурсы. */
    private static void watchWidgetColors() {
        Application app = AndroidAppHelper.currentApplication();
        if (app == null) return;
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ColorEngine.WIDGETS_STAMP), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        XposedBridge.log("ntweaker: цвета значков/виджетов изменились — сброс кэша значков и перезапуск лаунчера");
                        // значки лаунчер хранит готовыми картинками (app_icons.db) — без сброса цвет не обновится
                        app.deleteDatabase("app_icons.db");
                        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 700);
                    }
                });
    }

    private static Class<?> sheetCls, cardCls, rowCls;
    private static volatile int[] notifIds;

    /** Метка вида-«стекла» (View.setTag(key)): настройки стекла для его фона. */
    private static final int GLASS_TAG = 0x7e4e5401;

    private static final class GlassSpec {
        final float alpha, grain;
        final int blurPx;

        GlassSpec(float alpha, int blurPx, float grain) {
            this.alpha = alpha;
            this.blurPx = blurPx;
            this.grain = grain;
        }
    }

    /**
     * Стекло для панели. Фоны Nothing НЕ подменяются (их анимируют: сдвигают слои, меняют размер) — вид только
     * помечается, а при его отрисовке (хук dispatchDraw, glassDraw) по текущим границам заливки рисуются размытие
     * (дырка в окне), сама заливка — полупрозрачной поверх, и зерно. Выключено — метки снимаются.
     */
    static void glassify(View root, boolean on, float alpha, float blurDp, float grain) {
        if (root == null) return;
        GlassSpec spec = on ? new GlassSpec(alpha, Math.round(px(root.getContext(), blurDp)), grain) : null;
        glassWalk(root, spec, 0);
    }

    private static void glassWalk(View v, GlassSpec spec, int depth) {
        if (depth > 10) return;
        if (v instanceof android.view.ViewGroup) {
            boolean target = spec != null && fill(v.getBackground(), 0) != null;
            Object was = v.getTag(GLASS_TAG);
            v.setTag(GLASS_TAG, target ? spec : null);
            if (was != null && !target) LucentBlur.clear(v);
            if (was != null || target) v.invalidate();
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) glassWalk(g.getChildAt(i), spec, depth + 1);
        }
    }

    /** Отрисовка помеченного вида (до детей): размытие по заливке фона, заливка полупрозрачной, зерно. */
    static void glassDraw(View v, android.graphics.Canvas c) {
        Object t = v.getTag(GLASS_TAG);
        if (!(t instanceof GlassSpec)) return;
        GlassSpec spec = (GlassSpec) t;
        android.graphics.drawable.GradientDrawable gd = fill(v.getBackground(), 0);
        if (gd == null) return;
        android.graphics.Rect b = gd.getBounds();
        Drawable bg = v.getBackground();
        if (b.width() < 2 || b.height() < 2 || gd.getAlpha() == 0 || (bg != null && bg.getAlpha() == 0)) {
            LucentBlur.clear(v);                         // Nothing спрятала заливку прозрачностью — и стекла нет
            return;
        }
        android.graphics.RectF r = new android.graphics.RectF(b);
        r.offset(v.getScrollX(), v.getScrollY());
        float[] rad = radii(gd, b.width(), b.height());
        if (!LucentBlur.drawShape(c, v, v, r, rad, spec.blurPx)) return;
        // фон Nothing стёрт дыркой — рисуем его же целиком (все слои), но полупрозрачным; фон не трогаем
        int save = c.saveLayerAlpha(r, Math.round(255 * spec.alpha));
        c.translate(v.getScrollX(), v.getScrollY());
        if (bg != null) bg.draw(c);
        c.restoreToCount(save);
        LucentBlur.grainShape(c, r, rad, spec.grain);
    }

    private static boolean glassDrawHooked;

    /** Общий хук ViewGroup.dispatchDraw для помеченных видов (дешёво: getTag по ключу). */
    static synchronized void hookGlassDraw() {
        if (glassDrawHooked) return;
        glassDrawHooked = true;
        XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchDraw", android.graphics.Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                if (v.getTag(GLASS_TAG) != null) {
                    try {
                        glassDraw(v, (android.graphics.Canvas) p.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: стекло панели — " + t);
                        v.setTag(GLASS_TAG, null);
                    }
                }
            }
        });
    }

    /** Заливка фона: GradientDrawable сам по себе или внутри обёрток/слоёв (маску ripple пропускаем). */
    private static android.graphics.drawable.GradientDrawable fill(Drawable d, int depth) {
        if (d == null || depth > 4) return null;
        if (d instanceof android.graphics.drawable.GradientDrawable) return (android.graphics.drawable.GradientDrawable) d;
        if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < l.getNumberOfLayers(); i++) {
                if (l.getId(i) == android.R.id.mask) continue;
                android.graphics.drawable.GradientDrawable g = fill(l.getDrawable(i), depth + 1);
                if (g != null) return g;
            }
            return null;
        }
        if (d instanceof android.graphics.drawable.DrawableWrapper) return fill(((android.graphics.drawable.DrawableWrapper) d).getDrawable(), depth + 1);
        if (d instanceof android.graphics.drawable.DrawableContainer && d.getCurrent() != d) return fill(d.getCurrent(), depth + 1);
        return null;
    }

    /** Углы фона (tl, tr, bl, br) в px, не больше половины меньшей стороны вида. */
    private static float[] radii(android.graphics.drawable.GradientDrawable gd, int w, int h) {
        float half = Math.min(w, h) / 2f;
        float[] r = new float[4];
        if (gd.getShape() == android.graphics.drawable.GradientDrawable.OVAL) {
            java.util.Arrays.fill(r, half);
            return r;
        }
        float[] a = null;
        try {
            a = gd.getCornerRadii();
        } catch (Throwable ignored) {
        }
        if (a != null && a.length >= 8) {
            r[0] = a[0]; r[1] = a[2]; r[3] = a[4]; r[2] = a[6];      // в массиве: tl, tr, br, bl
        } else {
            java.util.Arrays.fill(r, gd.getCornerRadius());
        }
        for (int i = 0; i < 4; i++) r[i] = Math.min(r[i], half);
        return r;
    }
    private static final ThreadLocal<Boolean> IN_SEARCH_STRIP = new ThreadLocal<>();
    private static volatile int[] searchIds;

    /**
     * Стекло для вида меню приложений: карточки категорий (SmartFolderIcon) — фон с альфой «Карточки и поиск».
     * Возвращает силу зерна поверх фона (0 — не рисовать).
     */
    /**
     * Виды с зерном по форме фона (панель поиска меню) — своя таблица только для UI-потока: общий хук dispatchDraw
     * спрашивает её у каждого контейнера в каждом кадре, а «дополнительные поля» Xposed — синхронизированная таблица.
     */
    static final java.util.WeakHashMap<View, Float> GRAIN = new java.util.WeakHashMap<>();

    static float drawerGlass(View v) {
        boolean row = rowCls != null && rowCls.isInstance(v);
        if (!row && (cardCls == null || !cardCls.isInstance(v))) return 0f;
        GlassPrefs g = prefs();
        Drawable bg = v.getBackground();
        if (bg == null && row) {
            // строка подсказок без фона-Drawable рисует подложку своей кистью
            try {
                android.graphics.Paint paint = (android.graphics.Paint) XposedHelpers.getObjectField(v, "mDefaultDrawerBGPaint");
                Object last = XposedHelpers.getAdditionalInstanceField(paint, "ntSet");
                Object base0 = XposedHelpers.getAdditionalInstanceField(paint, "ntBase");
                int base = base0 instanceof Integer && last instanceof Integer && paint.getAlpha() == (Integer) last ? (Integer) base0 : paint.getAlpha();
                int a = g.enabled ? Math.round(base * g.searchAlpha) : base;
                XposedHelpers.setAdditionalInstanceField(paint, "ntBase", base);
                XposedHelpers.setAdditionalInstanceField(paint, "ntSet", a);
                if (paint.getAlpha() != a) {
                    paint.setAlpha(a);
                    v.invalidate();
                }
            } catch (Throwable ignored) {
            }
            return 0f;
        }
        if (bg == null) return 0f;
        int base = XposedHelpers.getAdditionalInstanceField(bg, "ntBase") instanceof Integer
                ? (Integer) XposedHelpers.getAdditionalInstanceField(bg, "ntBase") : bg.getAlpha();
        XposedHelpers.setAdditionalInstanceField(bg, "ntBase", base);
        int a = g.enabled ? Math.round(base * g.searchAlpha) : base;
        if (bg.getAlpha() != a) bg.setAlpha(a);
        return g.enabled ? g.drawerGrain : 0f;
    }

    /** Размытие меню под открытой папкой: сила = видимость подложки папки (0…1). */
    static void sheetBlur(View sheet, float k) {
        try {
            Object act = XposedHelpers.getObjectField(sheet, "mActivityContext");
            View apps = (View) XposedHelpers.callMethod(act, "getAppsView");
            int barId = sheet.getResources().getIdentifier("drawer_search_bar", "id", "com.nothing.launcher");
            View bar = barId != 0 ? ((Activity) act).findViewById(barId) : null;
            int r = Math.round(px(sheet.getContext(), prefs().drawerBlur) * 0.5f * k);
            Object last = XposedHelpers.getAdditionalInstanceField(apps, "ntSheetBlur");
            if (last instanceof Integer && (Integer) last == r) return;
            XposedHelpers.setAdditionalInstanceField(apps, "ntSheetBlur", r);
            android.graphics.RenderEffect e = r > 0
                    ? android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) : null;
            apps.setRenderEffect(e);
            if (bar != null) bar.setRenderEffect(e);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: размытие под папкой — " + t);
        }
    }

    /** Кадр открытой папки меню: размытие под ней по альфе её подложки; вернуть силу зерна поверх (0 — нет). */
    static float sheetFrame(View v) {
        if (sheetCls == null || !sheetCls.isInstance(v)) return -1f;
        GlassPrefs g = prefs();
        View scrim = (View) XposedHelpers.getObjectField(v, "mColorScrim");
        float k = g.enabled && scrim != null && scrim.isAttachedToWindow() && scrim.isShown() ? scrim.getAlpha() : 0f;
        sheetBlur(v, k);
        return g.enabled ? g.drawerGrain * k : 0f;
    }

    /** Фон панели поиска: альфа по настройке; при выключенном стекле — как было. */
    static void searchBg(View container) {
        try {
            View rl = (View) XposedHelpers.getObjectField(container, "searchRelativeLayout");
            if (rl == null || rl.getBackground() == null) return;
            GlassPrefs g = prefs();
            Drawable bg = rl.getBackground();
            if (XposedHelpers.getAdditionalInstanceField(bg, "ntMut") == null) {
                bg = bg.mutate();
                XposedHelpers.setAdditionalInstanceField(bg, "ntMut", true);
            }
            bg.setAlpha(g.enabled ? Math.round(255 * g.searchAlpha) : 255);
            GRAIN.put(rl, g.enabled ? g.drawerGrain : 0f);
            rl.invalidate();
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: панель поиска — " + t);
        }
    }

    public static void launcher(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if ("com.nothing.launcher".equals(((Application) p.thisObject).getPackageName())) watchWidgetColors();
                } catch (Throwable t) {
                    XposedBridge.log("ntweaker: слежение за цветами виджетов — " + t);
                }
            }
        });
        Class<?> allApps = XposedHelpers.findClassIfExists("com.android.launcher3.uioverrides.states.AllAppsState", cl);
        if (allApps != null) {
            Hook.all(allApps, "getWorkspaceScrimColor", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled || !(p.getResult() instanceof Integer)) return;
                    int c = (Integer) p.getResult();
                    p.setResult(Color.argb(Math.round(Color.alpha(c) * g.drawerAlpha), Color.red(c), Color.green(c), Color.blue(c)));
                }
            });
        }
        // зерно поверх подложки меню приложений — сила по альфе её цвета относительно полной
        Class<?> lScrim = XposedHelpers.findClassIfExists("com.android.launcher3.views.ScrimView", cl);
        if (lScrim != null) {
            Hook.all(lScrim, "onDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled || g.drawerGrain <= 0f) return;
                    int c = XposedHelpers.getIntField(p.thisObject, "mBackgroundColor");
                    float a = Color.alpha(c) / 255f / Math.max(0.05f, g.drawerAlpha);
                    if (a > 0.01f) LucentBlur.grain((Canvas) p.args[0], g.drawerGrain * Math.min(1f, a));
                }
            });
        }
        hookGlassDraw();
        // меню долгого нажатия на иконку: пункты — стекло (размытые обои под ними, оттенок, зерно)
        Class<?> popup = XposedHelpers.findClassIfExists("com.android.launcher3.popup.ArrowPopup", cl);
        if (popup != null) {
            XC_MethodHook glassPopup = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    View v = (View) p.thisObject;
                    Runnable r = () -> {
                        GlassPrefs g = prefs();
                        glassify(v, g.enabled, g.panelAlpha, g.drawerBlur * 0.6f, g.drawerGrain);
                    };
                    if (v.isAttachedToWindow()) {
                        v.post(r);
                        v.postDelayed(r, 200);              // после раскладки: у пунктов уже есть размер
                    }
                    else v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View view) {
                            view.removeOnAttachStateChangeListener(this);
                            view.post(r);
                            view.postDelayed(r, 200);
                        }

                        @Override
                        public void onViewDetachedFromWindow(View view) {
                        }
                    });
                }
            };
            Hook.all(popup, "show", glassPopup);          // onInflationComplete в этой версии лаунчера нет
        }
        // открытая папка в меню (SmartFullSheet): непрозрачная подложка → полупрозрачная, а меню под ней размываем
        // (это то же окно, системное размытие его не видит — RenderEffect на списке и панели поиска)
        Class<?> sheet = XposedHelpers.findClassIfExists("com.nothing.launcher.allapps.smart.SmartFullSheet", cl);
        sheetCls = sheet;
        cardCls = XposedHelpers.findClassIfExists("com.nothing.launcher.allapps.smart.SmartFolderIcon", cl);
        rowCls = XposedHelpers.findClassIfExists("com.android.launcher3.appprediction.PredictionRowView", cl);
        if (sheet != null) {
            Hook.all(sheet, "getScrimColor", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    if (!g.enabled || !(p.getResult() instanceof Integer)) return;
                    int c = (Integer) p.getResult();
                    p.setResult(Color.argb(Math.round(Color.alpha(c) * g.drawerAlpha), Color.red(c), Color.green(c), Color.blue(c)));
                }
            });
            // папка закрылась — снять размытие с меню (вид папки может остаться в окне, поэтому не только detach)
            XC_MethodHook off = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (sheetCls.isInstance(p.thisObject)) sheetBlur((View) p.thisObject, 0f);
                }
            };
            Class<?> slide = XposedHelpers.findClassIfExists("com.android.launcher3.views.AbstractSlideInView", cl);
            if (slide != null) Hook.all(slide, "onCloseComplete", off);
        }
        // панель поиска внизу меню (Compose, DrawerSearchBar): цвет плашки — ресурс *_universal_search_bar_fill_color,
        // под ней i9.i.p рисует полосу непрозрачным цветом подложки меню — её убираем совсем (подложка уже есть ниже)
        Class<?> bar = XposedHelpers.findClassIfExists("i9.i", cl);
        if (bar != null) {
            for (java.lang.reflect.Method m : bar.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (!m.getName().equals("p") || t.length != 3 || t[0] != Context.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        IN_SEARCH_STRIP.set(true);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        IN_SEARCH_STRIP.set(false);
                    }
                });
            }
        }
        XposedHelpers.findAndHookMethod(android.content.res.Resources.class, "getColor", int.class, android.content.res.Resources.Theme.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                GlassPrefs g = prefs();
                if (!g.enabled) return;
                android.content.res.Resources r = (android.content.res.Resources) p.thisObject;
                int id = (Integer) p.args[0];
                if (searchIds == null) {
                    int[] ids = new int[]{
                            r.getIdentifier("cl_universal_search_bar_fill_color", "color", "com.nothing.launcher"),
                            r.getIdentifier("nt_universal_search_bar_fill_color", "color", "com.nothing.launcher"),
                            r.getIdentifier("nt_all_apps_scrim_color_nt_mono", "color", "com.nothing.launcher")};
                    if (ids[0] == 0 && ids[1] == 0) return;     // ресурсы не лаунчера — попробуем на следующем вызове
                    searchIds = ids;
                }
                if (id == 0) return;
                int c = (Integer) p.getResult();
                if (id == searchIds[0] || id == searchIds[1]) {
                    // под панелью поиска нет размытия (снизу листается список) — она заметно плотнее карточек
                    p.setResult(Color.argb(Math.round(Color.alpha(c) * (0.6f + 0.4f * g.searchAlpha)), Color.red(c), Color.green(c), Color.blue(c)));
                } else if (id == searchIds[2] && Boolean.TRUE.equals(IN_SEARCH_STRIP.get())) {
                    p.setResult(Color.TRANSPARENT);
                }
            }
        });
        // панель поиска в меню: серая непрозрачная плашка → полупрозрачная (фон rl_all_apps_search ставит updateSearchBg)
        Class<?> search = XposedHelpers.findClassIfExists("com.nothing.launcher.allapps.search.NTAppsSearchContainerLayout", cl);
        if (search != null) {
            XC_MethodHook fix = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    searchBg((View) p.thisObject);
                }
            };
            Hook.all(search, "updateSearchBg", fix);
            Hook.all(search, "onFinishInflate", fix);
            Hook.all(search, "onAttachedToWindow", fix);
        }
        // размытие обоев под лаунчером — по «глубине» состояния: у меню приложений она максимальная.
        // Nothing у себя выкинула размытие из applyDepthAndBlur (осталось только отдаление обоев) — делаем сами
        // публичным Window.setBackgroundBlurRadius.
        Class<?> depth = XposedHelpers.findClassIfExists("com.android.quickstep.util.BaseDepthController", cl);
        Class<?> state = XposedHelpers.findClassIfExists("com.android.launcher3.LauncherState", cl);
        if (depth != null && state != null) {
            Hook.all(depth, "applyDepthAndBlur", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    GlassPrefs g = prefs();
                    try {
                        Activity l = (Activity) XposedHelpers.getObjectField(p.thisObject, "mLauncher");
                        int radius = 0;
                        if (g.enabled && g.drawerBlur > 0) {
                            float d = XposedHelpers.getFloatField(p.thisObject, "mDepth");
                            Object all = XposedHelpers.getStaticObjectField(state, "ALL_APPS");
                            float full = (Float) XposedHelpers.callMethod(all, "getDepth", l);
                            float k = full > 0 ? Math.min(1f, d / full) : 0f;
                            radius = Math.round(px(l, g.drawerBlur) * k);
                        }
                        Integer last = (Integer) XposedHelpers.getAdditionalInstanceField(p.thisObject, "ntweakerBlur");
                        if (last != null && last == radius) return;
                        if (last == null && radius == 0) return;
                        XposedHelpers.setAdditionalInstanceField(p.thisObject, "ntweakerBlur", radius);
                        l.getWindow().setBackgroundBlurRadius(radius);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: размытие меню — " + t);
                    }
                }
            });
        }
        XposedBridge.log("ntweaker: стекло меню приложений подключено" + (allApps == null ? " (без AllAppsState)" : "") + (depth == null ? " (без глубины)" : ""));
    }
}
