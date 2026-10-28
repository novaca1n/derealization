package dev.novacain.ntclocks.anim;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Системные анимации «ColorOS 17» (настройка sysMotion). Прошивки ColorOS 17 под рукой нет — параметры из кода
 * ColorOS 16 (OTA Reno12: OplusFolderAnimationManager, VolumeAnimUtil, COUIPressFeedbackHelper), поверх — то, что
 * OPPO обещает в 17 («Fluid Fusion»): у нажатий при отпускании лёгкий упругий отскок.
 *   - папки (лаунчер): все части раскрытия и закрытия — пружины COUI вместо кривых Nothing, значки — каскадом;
 *   - панель громкости (SystemUI, настройка volume): ColorOS — вырастает от края из половины размера с отскоком;
 *     iPhone — своя капсула вместо панели Nothing (IosVolume);
 *   - плитки быстрых настроек: при нажатии сжимаются (0.92 у маленьких, до 0.97 у широких), отпускание — упруго;
 *   - разблокировка: стол «опускается» на место (1.08 → 1), часы остаются и растворяются (UnlockAnim);
 *   - «резиновая» громкость: на краю шкалы ползунок упруго вытягивается в сторону нажатия.
 */
public final class Coloros17 {
    private Coloros17() {
    }

    private static boolean on() {
        return AnimHooks.prefs().coloros17Motion();
    }

    /**
     * Интерполятор пружины COUI один в один (COUISpringInterpolator): за длительность аниматора проигрывается ровно
     * 1 с физического времени пружины, затем значение нормируется к положению в конце. response — период в секундах,
     * bounce — отскок (затухание = 1 − bounce).
     */
    static final class CouiSpring implements Interpolator {
        private final double w0, zeta, wd, impulse;
        private final float fin;

        CouiSpring(double response, double bounce) {
            w0 = 2 * Math.PI / response;
            zeta = 1 - bounce;
            wd = zeta < 1 ? Math.sqrt(1 - zeta * zeta) * w0 : 0;
            impulse = zeta < 1 ? zeta * w0 / wd : w0;
            float f = raw(1f);
            fin = f != 0f ? f : 1f;
        }

        private float raw(float t) {
            double d = Math.max(0f, t), e = Math.exp(-zeta * w0 * d);
            double c = zeta < 1 ? (Math.sin(wd * d) * impulse + Math.cos(wd * d)) * e : (impulse * d + 1) * e;
            return (float) (1 - c);
        }

        @Override
        public float getInterpolation(float t) {
            return raw(t) / fin;
        }
    }

    // ───────── лаунчер: папки ─────────

    public static void launcher(ClassLoader cl) {
        Class<?> folder = XposedHelpers.findClassIfExists("com.android.launcher3.folder.NTFolderAnimationManager", cl);
        if (folder == null) {
            XposedBridge.log("derealization: ColorOS 17 — folder animation not found");
            return;
        }
        // getAnimator собирает всё раскрытие/закрытие в один AnimatorSet — после него перенастраиваем каждую часть
        Hook.all(folder, "getAnimator", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!on() || !(p.getResult() instanceof AnimatorSet)) return;
                boolean opening = XposedHelpers.getBooleanField(p.thisObject, "isOpening");
                retime((AnimatorSet) p.getResult(), opening);
                stagger((AnimatorSet) p.getResult(), opening);
            }
        });
        XposedBridge.log("derealization: ColorOS 17 — folders hooked");
    }

    /**
     * Пружины OplusFolderAnimationManager (COUISpringForce, физическое время): раскрытие — сдвиг/масштаб 0.45 с,
     * отскок 0.15; закрытие — 0.4 с, отскок 0.13 (у ColorOS 0.1…0.16 по скорости); прозрачность и цвет — 0.3 с без
     * отскока. Задержки Nothing (название, индикатор) сохраняются.
     */
    private static void retime(AnimatorSet set, boolean opening) {
        ShellAnims.Spring move = opening ? new ShellAnims.Spring(0.45, 0.85) : new ShellAnims.Spring(0.4, 0.87);
        ShellAnims.Spring fade = new ShellAnims.Spring(0.3, 1.0);
        for (Animator a : set.getChildAnimations()) {
            if (a instanceof AnimatorSet) {
                retime((AnimatorSet) a, opening);
                continue;
            }
            if (!(a instanceof ValueAnimator)) continue;
            ShellAnims.Spring s = isFade((ValueAnimator) a) ? fade : move;
            a.setInterpolator(s);
            a.setDuration(s.durationMs());
        }
    }

    /**
     * Каскад значков папки: аниматоры с целью-значком (BubbleTextView) получают задержку по порядку — сверху вниз,
     * слева направо, 14 мс шаг при раскрытии; при закрытии порядок обратный и шаг 8 мс (значки должны успеть сесть).
     */
    private static void stagger(AnimatorSet set, boolean opening) {
        java.util.List<ObjectAnimator> items = new java.util.ArrayList<>();
        collectItems(set, items);
        java.util.List<View> order = new java.util.ArrayList<>();
        for (ObjectAnimator a : items) if (!order.contains((View) a.getTarget())) order.add((View) a.getTarget());
        order.sort((x, y) -> x.getTop() != y.getTop() ? Integer.compare(x.getTop(), y.getTop()) : Integer.compare(x.getLeft(), y.getLeft()));
        int n = order.size();
        for (ObjectAnimator a : items) {
            int i = order.indexOf((View) a.getTarget());
            long d = opening ? 14L * i : 8L * (n - 1 - i);
            a.setStartDelay(a.getStartDelay() + d);
        }
    }

    private static void collectItems(AnimatorSet set, java.util.List<ObjectAnimator> out) {
        for (Animator a : set.getChildAnimations()) {
            if (a instanceof AnimatorSet) collectItems((AnimatorSet) a, out);
            else if (a instanceof ObjectAnimator && ((ObjectAnimator) a).getTarget() instanceof View
                    && ((View) ((ObjectAnimator) a).getTarget()).getClass().getName().contains("BubbleTextView")) out.add((ObjectAnimator) a);
        }
    }

    private static boolean isFade(ValueAnimator a) {
        String n = a instanceof ObjectAnimator ? ((ObjectAnimator) a).getPropertyName() : null;
        if (n == null) {
            PropertyValuesHolder[] v = a.getValues();
            n = v != null && v.length > 0 ? v[0].getPropertyName() : "";
        }
        n = n == null ? "" : n.toLowerCase();
        return n.contains("alpha") || n.contains("color");
    }

    // ───────── SystemUI: громкость и плитки ─────────

    private static final ThreadLocal<View> VOL_SHOW = new ThreadLocal<>(), VOL_HIDE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> WAS_SHOWING = new ThreadLocal<>();
    private static final java.util.Map<Object, Integer> LAST_LEVEL = new java.util.WeakHashMap<>();
    private static boolean volRight;
    private static java.lang.ref.WeakReference<View> volRows;

    /** Режим панели громкости (AnimPrefs.volume) или null — родная анимация Nothing. */
    private static String vol() {
        String m = AnimHooks.prefs().volume;
        return AnimPrefs.COLOROS.equals(m) || AnimPrefs.IOS.equals(m) ? m : null;
    }

    public static void systemui(ClassLoader cl) {
        volume(cl);
        tiles(cl);
        unlock(cl);
        headsUp(cl);
    }

    /**
     * Всплывающее уведомление «падает» сверху на пружине: StackStateAnimator ставит ему по TRANSLATION_Y интерполятор
     * FAST_OUT_SLOW_IN на 400 мс (событие HEADS_UP_APPEAR) — подменяем на пружину COUI (0.55, отскок 0.25): за 400 мс
     * уведомление чуть проскакивает своё место и упруго возвращается.
     */
    private static void headsUp(ClassLoader cl) {
        Class<?> props = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.stack.AnimationProperties", cl);
        Class<?> interp = XposedHelpers.findClassIfExists("com.android.app.animation.Interpolators", cl);
        if (props == null || interp == null) {
            XposedBridge.log("derealization: ColorOS 17 — notifications not found");
            return;
        }
        Object fosi = XposedHelpers.getStaticObjectField(interp, "FAST_OUT_SLOW_IN");
        Interpolator spring = new CouiSpring(0.55, 0.25);
        Hook.all(props, "setCustomInterpolator", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (on() && p.args[0] == View.TRANSLATION_Y && p.args[1] == fosi) p.args[1] = spring;
            }
        });
        // у Nothing всплывающее уведомление не въезжает, а раскрывается из полоски (клип) — сдвиг Y почти нулевой;
        // поверх раскрытия — своё «падение»: на 36 dp выше и 0.94, пружина с перелётом (setAnimationMatrix)
        Class<?> row = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", cl);
        if (row != null) Hook.all(row, "performAddAnimation", new XC_MethodHook() {
            private boolean mine(MethodHookParam p) {
                return on() && p.args.length >= 4 && Boolean.TRUE.equals(p.args[2]) && Boolean.FALSE.equals(p.args[3]);
            }

            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                // раскрытие из полоски Nothing — за один кадр: штатно доходит до конца (обрезка снимается, слушатели
                // отрабатывают), карточка сразу целиком, а движение — наше падение
                if (mine(p) && p.args[1] instanceof Long) p.args[1] = 1L;
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (mine(p)) drop((View) p.thisObject);
            }
        });
        // уход всплывающего (сам спрятался): как на iPhone — улетает целиком за верхний край с разгоном.
        // Nothing сворачивает обрезку в полоску (mAppearAnimator) — держим её открытой до последнего кадра
        if (row != null) Hook.all(row, "performRemoveAnimation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (!on() || p.args.length < 5 || !Boolean.TRUE.equals(p.args[3]) || !Boolean.FALSE.equals(p.args[4])) return;
                View v = (View) p.thisObject;
                try {
                    Object an = XposedHelpers.getObjectField(v, "mAppearAnimator");
                    if (an instanceof ValueAnimator) {
                        ((ValueAnimator) an).setInterpolator(t -> t < 0.999f ? 0f : 1f);
                        ((ValueAnimator) an).setDuration(FLY_UP_MS);
                    }
                } catch (Throwable ignored) {
                }
                flyUp(v);
            }
        });
        XposedBridge.log("derealization: ColorOS 17 — notifications hooked");
    }

    /**
     * Стол при разблокировке. Nothing (KeyguardUnlockAnimationController.setSurfaceBehindAppearAmount) ставит окну
     * лаунчера матрицу: setTranslate(left, top + 0.05·h·(1−amount)), затем postScale(0.95 + 0.05·amount) вокруг
     * (ширина/2, 0.66·h). В ColorOS 17 стол не поднимается снизу, а «опускается» на место: 1.08 → 1 вокруг центра.
     * Поле surfaceBehindMatrix подменяем своей матрицей, которая пересчитывает эти два вызова.
     */
    private static void unlock(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.KeyguardUnlockAnimationController", cl);
        if (c == null) {
            XposedBridge.log("derealization: ColorOS 17 — unlock not found");
            return;
        }
        Hook.ctors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                XposedHelpers.setObjectField(p.thisObject, "surfaceBehindMatrix", new DropMatrix());
            }
        });
        // начало показа стола из-под блокировки — лаунчеру: «значки — каскадом» (HomeWave)
        Hook.all(c, "notifyStartSurfaceBehindRemoteAnimation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!on()) return;
                android.app.Application app = android.app.AndroidAppHelper.currentApplication();
                if (app != null) app.sendBroadcast(new android.content.Intent(HomeWave.ACTION_UNLOCK).setPackage("com.nothing.launcher"));
            }
        });
        XposedBridge.log("derealization: ColorOS 17 — unlock hooked");
    }

    private static final class DropMatrix extends android.graphics.Matrix {
        private float tx, ty;

        @Override
        public void setTranslate(float dx, float dy) {
            tx = dx;
            ty = dy;
            super.setTranslate(dx, dy);
        }

        @Override
        public boolean postScale(float sx, float sy, float px, float py) {
            if (!on() || sx >= 1f || sx < 0.949f || py <= 0f) return super.postScale(sx, sy, px, py);
            float amount = (sx - 0.95f) / 0.05f;
            float h = py / 0.66f;
            float top = ty - 0.05f * h * (1f - amount);              // подъём снизу Nothing — убираем
            float s = 1f + 0.08f * (1f - amount);
            super.setTranslate(tx, top);
            return super.postScale(s, s, px, top + h / 2f);
        }
    }

    /**
     * VolumeDialogImpl: показ — onShow-слушатель (lambda$initDialog$4), скрытие — dismissH; оба запускают
     * mDialogView.animate(). Подмена в момент start(): пружина и масштаб ColorOS на тот же аниматор — слушатели и
     * действия в конце (а с ними закрытие окна) остаются родными.
     */
    private static void volume(ClassLoader cl) {
        Class<?> dlg = XposedHelpers.findClassIfExists("com.android.systemui.volume.VolumeDialogImpl", cl);
        if (dlg == null) {
            XposedBridge.log("derealization: ColorOS 17 — volume panel not found");
            return;
        }
        Hook.all(dlg, "lambda$initDialog$4", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String mode = vol();
                if (mode == null) {
                    IosVolume.detach(p.thisObject);
                    return;
                }
                View v = (View) XposedHelpers.getObjectField(p.thisObject, "mDialogView");
                if (v == null) return;
                boolean right = XposedHelpers.getBooleanField(p.thisObject, "mIsVolumeKeyOnRight");
                volRight = right;
                try {
                    Object rows = XposedHelpers.getObjectField(p.thisObject, "mDialogRowsView");
                    if (rows instanceof View) volRows = new java.lang.ref.WeakReference<>((View) rows);
                } catch (Throwable ignored) {
                }
                if (AnimPrefs.IOS.equals(mode)) IosVolume.show(p.thisObject);
                else IosVolume.detach(p.thisObject);
                if (AnimPrefs.COLOROS.equals(mode)) {
                    v.setPivotX(right ? v.getWidth() : 0f);       // вырастает из края, у которого кнопки громкости
                    v.setPivotY(v.getHeight() / 2f);
                    v.setScaleX(0.5f);
                    v.setScaleY(0.5f);
                }
                VOL_SHOW.set(v);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                VOL_SHOW.remove();
            }
        });
        Hook.all(dlg, "dismissH", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (vol() != null) VOL_HIDE.set((View) XposedHelpers.getObjectField(p.thisObject, "mDialogView"));
                if (AnimPrefs.IOS.equals(vol())) IosVolume.hide();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                VOL_HIDE.remove();
            }
        });
        // VolumeAnimUtil (уровень анимаций 1–3): показ COUISpringInterpolator(0.7, 0.3) за 700 мс,
        // скрытие (0.7, 0) за 500 мс — к краю, до половины размера, с растворением
        Hook.all(ViewPropertyAnimator.class, "start", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View show = VOL_SHOW.get(), hide = VOL_HIDE.get();
                if (show == null && hide == null) return;
                ViewPropertyAnimator a = (ViewPropertyAnimator) p.thisObject;
                Object v = XposedHelpers.getObjectField(a, "mView");
                boolean ios = AnimPrefs.IOS.equals(vol());
                if (v != null && v == show && ios) {
                    // iPhone: панель Nothing невидима (IosVolume), её сразу на место — по ней считается зона касаний
                    // Анимировать её нельзя: пока идёт анимация Nothing, невидимая панель на миг просвечивает размытым
                    // «призраком» — поэтому аниматор пустой, только держит родные слушатели
                    View sv = (View) v;
                    sv.setTranslationX(0f);
                    sv.setAlpha(1f);
                    sv.setScaleX(1f);
                    sv.setScaleY(1f);
                    a.translationX(0f).alpha(1f).setDuration(1);
                } else if (v != null && v == hide && ios) {
                    View sv = (View) v;                  // окно живёт 420 мс, пока капсула уезжает за край
                    a.translationX(sv.getTranslationX()).alpha(sv.getAlpha()).setDuration(420);
                } else if (v != null && v == show) {
                    a.scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(new CouiSpring(0.7, 0.3));
                } else if (v != null && v == hide) {
                    a.scaleX(0.5f).scaleY(0.5f).setDuration(500).setInterpolator(new CouiSpring(0.7, 0.0));
                }
            }
        });
        // «резиновая» громкость (VolumeSpringAnimation.startCustomDeformation): на максимуме очередное «+» вытягивает
        // ползунок вверх за край, на минимуме «−» — вниз; затем он пружинит обратно
        // на iPhone ещё и каждое нажатие при открытой панели — ползунок чуть «вздрагивает», раздаваясь от края
        Hook.all(dlg, "showH", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                WAS_SHOWING.set(XposedHelpers.getBooleanField(p.thisObject, "mShowing"));
                // iPhone: панель Nothing прячем до показа окна — onShowListener (IosVolume.show) приходит уже после
                // первого кадра, и без этого панель успевает мелькнуть
                if (AnimPrefs.IOS.equals(vol())) {
                    Object dv = XposedHelpers.getObjectField(p.thisObject, "mDialogView");
                    if (dv instanceof View) ((View) dv).setTransitionAlpha(0f);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                boolean was = Boolean.TRUE.equals(WAS_SHOWING.get());
                WAS_SHOWING.remove();
                if (vol() == null || !(p.args[0] instanceof Integer) || (Integer) p.args[0] != 1) return;   // 1 — volume_changed
                try {
                    Object row = XposedHelpers.callMethod(p.thisObject, "getActiveRow");
                    android.widget.SeekBar sb = (android.widget.SeekBar) XposedHelpers.getObjectField(row, "slider");
                    if (sb == null) return;
                    // край — по уровню из состояния, а не по ползунку: ползунок доезжает анимацией и на миг отстаёт.
                    // Резина — только если уровень не сдвинулся (уже упёрлись), шаг на край — обычное нажатие
                    Object ss = XposedHelpers.getObjectField(row, "ss");
                    int level = XposedHelpers.getIntField(ss, "level");
                    Integer prev = LAST_LEVEL.put(row, level);
                    boolean stuck = prev != null && prev == level;
                    boolean max = level >= XposedHelpers.getIntField(ss, "levelMax"), min = level <= XposedHelpers.getIntField(ss, "levelMin");
                    if (AnimPrefs.IOS.equals(vol())) {
                        if (was) IosVolume.press(p.thisObject, stuck && max, stuck && min);
                    } else if (stuck && (max || min)) rubber(sb, max);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: ColorOS 17 — volume stretch — " + t);
                }
            }
        });
        // iPhone: уровень и поток меняются и без нажатий (из приложений, ползунком) — капсула следом
        Hook.all(dlg, "onStateChangedH", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                if (AnimPrefs.IOS.equals(vol())) IosVolume.sync(p.thisObject);
            }
        });
        XposedBridge.log("derealization: ColorOS 17 — volume panel hooked");
    }

    private static final int DROP_TAG = 0x7e4e5404;

    /**
     * Как баннер iPhone: карточка падает целиком из-за верхнего края (высота + 100 dp) и садится пружиной с заметным
     * перелётом (отклик 0.45 с, затухание 0.68 — ~6 % пути вниз и обратно).
     */
    private static void drop(View v) {
        Object prev = v.getTag(DROP_TAG);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        float lift = v.getHeight() + 100 * v.getResources().getDisplayMetrics().density;
        ShellAnims.Spring sp = new ShellAnims.Spring(0.45, 0.68);
        android.graphics.Matrix m = new android.graphics.Matrix();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(sp.durationMs());
        va.setInterpolator(sp);
        va.addUpdateListener(a -> {
            float x = (Float) a.getAnimatedValue();
            m.reset();
            m.postTranslate(0f, -lift * (1f - x));
            v.setAnimationMatrix(m);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.setAnimationMatrix(null);
            }
        });
        v.setTag(DROP_TAG, va);
        va.start();
    }

    private static final long FLY_UP_MS = 330;

    /** Карточка улетает вверх за край (низ карточки + 40 dp) с разгоном, затем сдвиг снимается — строка вернётся в шторку. */
    private static void flyUp(View v) {
        Object prev = v.getTag(DROP_TAG);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float dist = loc[1] + v.getHeight() + 40 * v.getResources().getDisplayMetrics().density;
        android.graphics.Matrix m = new android.graphics.Matrix();
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(FLY_UP_MS);
        va.setInterpolator(new android.view.animation.PathInterpolator(0.4f, 0f, 0.85f, 0.6f));
        va.addUpdateListener(a -> {
            m.reset();
            m.postTranslate(0f, -dist * (Float) a.getAnimatedValue());
            v.setAnimationMatrix(m);
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator an) {
                v.postDelayed(() -> v.setAnimationMatrix(null), 60);   // Nothing к этому моменту строку уже спрятал
            }
        });
        v.setTag(DROP_TAG, va);
        va.start();
    }

    private static final int RUBBER_TAG = 0x7e4e5403;

    /**
     * Рамка ползунка вытягивается вдоль себя (+5 % в длину, −3 % в ширину — «объём» сохраняется) в сторону нажатия:
     * вверх на максимуме, вниз на минимуме. Туда — 120 мс, обратно — пружина COUI (0.9, 0.1) за 500 мс.
     */
    private static void rubber(View slider, boolean up) {
        View v = pill(slider);
        Object prev = v.getTag(RUBBER_TAG);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        // растянутая таблетка выходит за свои границы — родители (у Nothing они ровно её размера) её бы обрезали
        for (android.view.ViewParent x = v.getParent(); x instanceof android.view.ViewGroup; x = x.getParent()) {
            ((android.view.ViewGroup) x).setClipChildren(false);
            ((android.view.ViewGroup) x).setClipToPadding(false);
        }
        v.setPivotX(columnCenter(v));
        v.setPivotY(up ? v.getHeight() : 0f);
        ValueAnimator out = ValueAnimator.ofFloat(0f, 1f);
        out.setDuration(120);
        out.setInterpolator(new android.view.animation.DecelerateInterpolator());
        ValueAnimator back = ValueAnimator.ofFloat(1f, 0f);
        back.setDuration(500);
        back.setInterpolator(new CouiSpring(0.9, 0.1));
        ValueAnimator.AnimatorUpdateListener l = a -> {
            float k = (Float) a.getAnimatedValue();
            v.setScaleY(1f + 0.05f * k);
            v.setScaleX(1f - 0.03f * k);
        };
        out.addUpdateListener(l);
        back.addUpdateListener(l);
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(out, back);
        v.setTag(RUBBER_TAG, set);
        set.start();
    }

    /**
     * «Таблетка» ползунка — volume_dialog_content_container: у Nothing тёмная подложка колонки — это фоны-соседи
     * (volume_dialog_bg_container_collapse, settings_container), а не фон самого ползунка; качаем их общего родителя.
     * Запасной путь — ближайший предок с фоном той же ширины, что рамка ползунка.
     */
    private static View pill(View slider) {
        for (View x = slider; x != null && x.getParent() instanceof View; x = (View) x.getParent()) {
            if (x.getId() != View.NO_ID && "volume_dialog_content_container".equals(entry(x))) return x;
        }
        View frame = slider.getParent() instanceof View ? (View) slider.getParent() : slider;
        for (View x = frame; x != null && x.getParent() instanceof View; x = (View) x.getParent()) {
            if (x.getWidth() != frame.getWidth()) break;
            if (x.getBackground() != null) return x;
        }
        return frame;
    }

    /** Середина колонки ползунков в координатах контейнера (контейнер шире колонки — в нём место для раскрытой панели). */
    private static float columnCenter(View container) {
        View rows = volRows != null ? volRows.get() : null;
        if (rows != null && rows.getParent() instanceof View && ((View) rows.getParent()).getParent() == container) {
            View col = (View) rows.getParent();
            return col.getLeft() + col.getWidth() / 2f;
        }
        return container.getWidth() / 2f;
    }

    private static String entry(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Нажатие на плитку (PressFeedbackHelper / COUIPressFeedbackHelper): сжатие пружиной 0.3 с без отскока до 0.92 у
     * маленьких плиток и до 0.97 у широких; отпускание — та же пружина с отскоком 0.25 («резиновый вес» ColorOS 17).
     */
    private static void tiles(ClassLoader cl) {
        Class<?> tile = XposedHelpers.findClassIfExists("com.android.systemui.qs.tileimpl.QSTileViewImpl", cl);
        if (tile == null) {
            XposedBridge.log("derealization: ColorOS 17 — tiles not found");
            return;
        }
        Hook.all(tile, "onTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (!on() || !(p.args[0] instanceof MotionEvent)) return;
                View v = (View) p.thisObject;
                switch (((MotionEvent) p.args[0]).getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        press(v, true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        press(v, false);
                        break;
                    default:
                        break;
                }
            }
        });
        XposedBridge.log("derealization: ColorOS 17 — tile presses hooked");
    }

    private static void press(View v, boolean down) {
        float d = v.getResources().getDisplayMetrics().density;
        float area = v.getWidth() * v.getHeight() / (d * d);            // dp²
        // у ColorOS сжатие зависит от площади: маленькая карточка 0.92 … большая 0.98
        float k = Math.max(0f, Math.min(1f, (area - 5000f) / 25000f));
        float target = down ? 0.92f + 0.05f * k : 1f;
        v.setPivotX(v.getWidth() / 2f);
        v.setPivotY(v.getHeight() / 2f);
        ShellAnims.Spring s = down ? new ShellAnims.Spring(0.3, 1.0) : new ShellAnims.Spring(0.3, 0.75);
        v.animate().scaleX(target).scaleY(target).setDuration(s.durationMs()).setInterpolator(s).start();
    }
}
