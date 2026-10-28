package dev.novacain.ntclocks.sysui;

import android.content.Context;
import android.util.Log;

import com.android.systemui.plugins.clocks.ClockMetadata;
import com.android.systemui.plugins.clocks.ClockProvider;
import com.android.systemui.shared.clocks.ClockRegistry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Точка входа внутри SystemUI. Вызывается модулем после конструктора ClockRegistry: кладёт часы модуля в его словарь
 * availableClocks — так же, как сам реестр кладёт часы Nothing. Любая ошибка — только в лог, SystemUI не падает.
 */
public final class Injector {
    private static final String TAG = "NtClocks";
    private static java.lang.ref.WeakReference<Object> sliceController;

    private Injector() {
    }

    @SuppressWarnings("unchecked")
    public static void inject(Object registryObject) {
        try {
            ClockRegistry registry = (ClockRegistry) registryObject;
            Context context = registry.getContext();
            Field f = ClockRegistry.class.getDeclaredField("availableClocks");
            f.setAccessible(true);
            Map<String, Object> clocks = (Map<String, Object>) f.get(registry);
            // ClockInfo у Kotlin — приватный вложенный класс: конструктор берём рефлексией
            Class<?> info = Class.forName("com.android.systemui.shared.clocks.ClockRegistry$ClockInfo", false,
                    ClockRegistry.class.getClassLoader());
            Constructor<?> ctor = info.getDeclaredConstructor(ClockMetadata.class, ClockProvider.class,
                    Class.forName("com.android.systemui.plugins.PluginLifecycleManager", false, ClockRegistry.class.getClassLoader()));
            ctor.setAccessible(true);
            NcProvider provider = new NcProvider(context);
            for (ClockMetadata m : provider.getClocks()) {
                clocks.put(m.getClockId(), ctor.newInstance(m, provider, null));
            }
            PrefsClient.get(context);   // заранее подтянуть настройки
            Log.i(TAG, "часы модуля добавлены в ClockRegistry: " + clocks.keySet());
        } catch (Throwable t) {
            Log.e(TAG, "не удалось добавить часы — SystemUI работает как обычно", t);
        }
    }

    /**
     * После KeyguardSliceViewController.updateSliceLayout (Nothing решила, показывать ли свою дату над часами):
     * если стоят часы модуля со своей датой и включено «Скрыть дату Nothing» — дата прячется. Так же Nothing прячет её
     * для своих часов Old Quicklook. Развёрнутые виджеты Nothing не трогаем — там дата нужна.
     */
    public static void afterSliceLayout(Object controller) {
        try {
            sliceController = new java.lang.ref.WeakReference<>(controller);
            Object registry = field(controller, "mClockRegistry");
            String id = ((ClockRegistry) registry).getCurrentClockId();
            if (!dev.novacain.ntclocks.faces.Clocks.isOurs(id)) return;
            PrefsClient prefs = PrefsClient.peek();
            if (prefs == null || !prefs.prefs(id).hideSystemDate || widgetExpanded()) return;
            ((android.view.View) field(controller, "mView")).setVisibility(android.view.View.GONE);
        } catch (Throwable t) {
            Log.w(TAG, "дата Nothing: " + t);
        }
    }

    /** Настройки изменились — пусть Nothing заново решит про дату (а мы — следом). Главный поток. */
    static void refreshSlice() {
        Object c = sliceController != null ? sliceController.get() : null;
        if (c == null) return;
        try {
            java.lang.reflect.Method m = c.getClass().getDeclaredMethod("updateSliceLayout");
            m.setAccessible(true);
            m.invoke(c);
        } catch (Throwable t) {
            Log.w(TAG, "обновление даты Nothing: " + t);
        }
    }

    private static boolean widgetExpanded() {
        try {
            ClassLoader cl = ClockRegistry.class.getClassLoader();
            Class<?> dep = Class.forName("com.nothing.systemui.NTDependencyEx", false, cl);
            Class<?> host = Class.forName("com.nothing.systemui.widget.NTWidgetHostController", false, cl);
            Object w = dep.getMethod("get", Class.class).invoke(null, host);
            return w != null && (Boolean) host.getMethod("getIsWidgetExpanded").invoke(w);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
