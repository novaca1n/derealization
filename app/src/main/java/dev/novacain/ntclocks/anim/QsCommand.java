package dev.novacain.ntclocks.anim;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Команды для SystemUI от лаунчера: «развернуть быстрые настройки» (свайп двумя пальцами вниз по столу, HomeHooks).
 * У лаунчера Nothing нет EXPAND_STATUS_BAR, а SystemUI может. Приём — только от тех, у кого есть STATUS_BAR
 * (лаунчер — да, обычные приложения — нет).
 */
public final class QsCommand {
    private QsCommand() {
    }

    public static void hook() {
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Application app = (Application) p.thisObject;
                try {
                    app.registerReceiver(new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            try {
                                Object sb = c.getSystemService("statusbar");
                                XposedHelpers.callMethod(sb, "expandSettingsPanel");
                            } catch (Throwable t) {
                                XposedBridge.log("derealization: quick settings on command — " + t);
                            }
                        }
                    }, new IntentFilter(HomeHooks.ACTION_QS), "android.permission.STATUS_BAR", null, Context.RECEIVER_EXPORTED);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: SystemUI commands — " + t);
                }
            }
        });
    }
}
