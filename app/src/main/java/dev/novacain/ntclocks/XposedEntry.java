package dev.novacain.ntclocks;

import android.content.Context;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Вход модуля (LSPosed, область — SystemUI). Код часов грузится вторым загрузчиком с родителем = загрузчик SystemUI:
 * так классы часов реализуют интерфейсы именно SystemUI (ClockProvider и др.), а не свои копии.
 * После конструктора ClockRegistry вызывается Injector.inject — он добавляет часы модуля в реестр.
 */
public final class XposedEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static boolean sysuiHooked;
    private static final String PKG = "dev.novacain.ntclocks";
    private static String modulePath;
    private static ClassLoader loader;

    /**
     * Загрузчик кода часов. Путь к APK спрашиваем у системы: после обновления модуля APK лежит по новому пути,
     * а modulePath из initZygote остаётся старым до перезагрузки телефона.
     */
    private static synchronized ClassLoader loader(Context ctx, ClassLoader sysui) throws Exception {
        if (loader == null) {
            String apk;
            try {
                apk = ctx.getPackageManager().getApplicationInfo(PKG, 0).sourceDir;
            } catch (Throwable t) {
                apk = modulePath;
            }
            loader = new PathClassLoader(apk, sysui);
        }
        return loader;
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    /** Отметиться у ntweaker (статус на главной): через несколько секунд после старта процесса, когда всё поднялось. */
    private static void reportAlive() {
        XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                android.app.Application app = (android.app.Application) p.thisObject;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        app.getContentResolver().call(android.net.Uri.parse("content://dev.novacain.ntclocks.prefs"), "alive", null, null);
                    } catch (Throwable t) {
                        XposedBridge.log("ntweaker: отметка статуса — " + t);
                    }
                }, 6000);
            }
        });
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.settings".equals(lpparam.packageName)) {
            if (!lpparam.isFirstApplication || !Guard.enter(lpparam.packageName)) return;
            try {
                dev.novacain.ntclocks.anim.AboutHooks.hook(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: «О телефоне» — " + t);
            }
            return;
        }
        if ("com.nothing.wallpaper".equals(lpparam.packageName)) {
            if (!lpparam.isFirstApplication || !Guard.enter(lpparam.packageName)) return;
            try {
                dev.novacain.ntclocks.anim.DepthHooks.picker(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: глубина в «Обоях и стиле» — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.PickerHooks.hook(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: derealization в «Обоях и стиле» — " + t);
            }
            return;
        }
        if ("com.nothing.launcher".equals(lpparam.packageName) && lpparam.isFirstApplication) {
            if (!Guard.enter(lpparam.packageName)) return;
            reportAlive();
            try {
                dev.novacain.ntclocks.anim.AnimHooks.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: лаунчер — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.OriginHooks.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: OriginOS (лаунчер) — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.ResizeHooks.hook(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: растягивание значков — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.LucentHooks.hook(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: прозрачные папки — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.NavHooks.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: полоска жестов (лаунчер) — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.GlassHooks.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: стекло меню — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.HomeHooks.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: стол и док — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.HomeWave.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: живой стол — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.IconExport.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: значки для Hive — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.Gravity.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: гравитация — " + t);
            }
            try {
                dev.novacain.ntclocks.anim.Coloros17.launcher(lpparam.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("ntweaker: ColorOS 17 (лаунчер) — " + t);
            }
            return;
        }
        if (!"com.android.systemui".equals(lpparam.packageName) || !lpparam.isFirstApplication) return;
        // в процесс SystemUI бывает загружен ещё один APK — точка входа срабатывает повторно с чужим загрузчиком:
        // классы SystemUI «не находятся», а хуки на общие классы Android (переходы WM Shell) встают второй раз
        if (sysuiHooked || XposedHelpers.findClassIfExists("com.android.systemui.SystemUIApplication", lpparam.classLoader) == null) return;
        sysuiHooked = true;
        if (!Guard.enter(lpparam.packageName)) return;
        reportAlive();
        try {
            dev.novacain.ntclocks.anim.AnimHooks.shell();
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: переходы — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.QsSliders.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: вертикальные ползунки — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.DepthHooks.systemui(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: глубина — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.QsCommand.hook();
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: команды SystemUI — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.UnlockAnim.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: разблокировка — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.LockWidgets.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: виджеты экрана блокировки — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.NavHooks.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: полоска жестов — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.ColorEngine.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: цвета — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.SplitShade.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: раздельная шторка — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.OriginHooks.systemui(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: шторка OriginOS — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.GlassHooks.shade(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: стекло шторки — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.ShadeWarm.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: прогрев шторки — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.ShadeRubber.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: упругая шторка — " + t);
        }
        try {
            dev.novacain.ntclocks.anim.Coloros17.systemui(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("ntweaker: ColorOS 17 (SystemUI) — " + t);
        }
        try {
            ClassLoader sysui = lpparam.classLoader;
            // в процессе SystemUI бывают и чужие загрузчики (другие APK) — там реестра часов нет, это не ошибка
            if (XposedHelpers.findClassIfExists("com.android.systemui.shared.clocks.ClockRegistry", sysui) == null) return;
            Class<?> registry = XposedHelpers.findClass("com.android.systemui.shared.clocks.ClockRegistry", sysui);
            XposedBridge.hookAllConstructors(registry, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                        loader(ctx, sysui).loadClass("dev.novacain.ntclocks.sysui.Injector")
                                .getMethod("inject", Object.class).invoke(null, param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("NtClocks: inject — " + t);
                    }
                }
            });
            // дата Nothing над часами: прятать, если у часов модуля своя (см. Injector.afterSliceLayout)
            Class<?> slice = XposedHelpers.findClassIfExists("com.android.keyguard.KeyguardSliceViewController", sysui);
            if (slice != null) {
                XposedHelpers.findAndHookMethod(slice, "updateSliceLayout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Context ctx = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                            loader(ctx, sysui).loadClass("dev.novacain.ntclocks.sysui.Injector")
                                    .getMethod("afterSliceLayout", Object.class).invoke(null, param.thisObject);
                        } catch (Throwable t) {
                            XposedBridge.log("NtClocks: дата — " + t);
                        }
                    }
                });
            }
            XposedBridge.log("NtClocks: SystemUI подхвачен");
        } catch (Throwable t) {
            XposedBridge.log("NtClocks: не удалось подключиться к SystemUI — " + t);
        }
    }
}
