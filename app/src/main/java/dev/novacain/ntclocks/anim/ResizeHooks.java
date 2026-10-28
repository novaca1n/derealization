package dev.novacain.ntclocks.anim;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Растягивание значков приложений на рабочем столе, как виджетов: 2×1, 3×1, 4×1, 1×2, 1×3 (и родной 2×2 Nothing).
 *
 * Основа — механизм Nothing для «Увеличить/Уменьшить» (ResizeManager, q7.y): он ищет место и раздвигает соседей для
 * любого размера — размер берётся из двух полей (обычно 2 и 2), их подставляем на время расчёта. Пункты меню
 * «Увеличить» / «Уменьшить» по долгому нажатию открывают выбор размера. Растянутый значок рисуется капсулой цвета фона
 * иконки — от иконки первой ячейки до иконки последней, иконка в центре, подпись под капсулой (ряды остаются ровными).
 * Размер хранится в базе лаунчера (spanX/spanY), как у родного большого значка.
 */
public final class ResizeHooks {
    private ResizeHooks() {
    }

    /** Предлагаемые размеры (ширина × высота в ячейках). */
    private static final int[][] SIZES = {{1, 1}, {2, 1}, {3, 1}, {4, 1}, {1, 2}, {1, 3}, {2, 2}};
    /** Папки: обычная, капсула 2×1 (значки одним рядом) и родная большая Nothing 2×2. */
    private static final int[][] FOLDER_SIZES = {{1, 1}, {2, 1}, {2, 2}};
    private static Class<?> folderInfo;

    private static boolean isFolder(Object tag) {
        return folderInfo != null && folderInfo.isInstance(tag);
    }

    private static int[][] sizes(Object tag) {
        return isFolder(tag) ? FOLDER_SIZES : SIZES;
    }

    private static Field fSpanX, fSpanY, fContainer, fBitmap;
    private static java.lang.reflect.Method mGetIcon;
    private static int iconSize, drawPad;
    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF CAPS = new RectF();
    private static final Rect SAVED = new Rect();

    public static void hook(ClassLoader cl) {
        Class<?> expand = XposedHelpers.findClassIfExists("com.nothing.launcher.popup.z$b", cl);
        Class<?> reduce = XposedHelpers.findClassIfExists("com.nothing.launcher.popup.z$d", cl);
        Class<?> bubble = XposedHelpers.findClassIfExists("com.android.launcher3.BubbleTextView", cl);
        Class<?> item = XposedHelpers.findClassIfExists("com.android.launcher3.model.data.ItemInfo", cl);
        Class<?> wsItem = XposedHelpers.findClassIfExists("com.android.launcher3.model.data.WorkspaceItemInfo", cl);
        if (expand == null || reduce == null || bubble == null || item == null || wsItem == null) {
            XposedBridge.log("derealization: icon resize — not found");
            return;
        }
        folderInfo = XposedHelpers.findClassIfExists("com.android.launcher3.model.data.FolderInfo", cl);
        fSpanX = XposedHelpers.findField(item, "spanX");
        fSpanY = XposedHelpers.findField(item, "spanY");
        fContainer = XposedHelpers.findField(item, "container");
        fBitmap = XposedHelpers.findFieldIfExists(wsItem, "bitmap");
        mGetIcon = XposedHelpers.findMethodExactIfExists(bubble, "getIcon");

        // «Увеличить» / «Уменьшить» в меню значка — как у Nothing (2×2 и обратно); размер выбирается рамкой за угол.
        // «Уменьшить» у растянутого нами значка (2×1, 1×3 …) Nothing не умеет — возвращаем его в 1×1 сами
        Hook.all(reduce, "onClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object sc = p.thisObject;
                    View view = (View) XposedHelpers.getObjectField(sc, "b");
                    Object tag = view.getTag();
                    if (!wsItem.isInstance(tag)) return;
                    int sx = fSpanX.getInt(tag), sy = fSpanY.getInt(tag);
                    if ((sx == 1 && sy == 1) || (sx == 2 && sy == 2)) return;
                    Activity launcher = (Activity) XposedHelpers.getObjectField(sc, "mTarget");
                    try {
                        XposedHelpers.callMethod(XposedHelpers.getObjectField(sc, "a"), "hideHighlightIconView");
                    } catch (Throwable ignored) {
                    }
                    XposedHelpers.callMethod(sc, "E", true);       // закрыть меню значка
                    view.post(() -> apply(launcher, view, 1, 1));
                    p.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: shrink icon — " + t);
                }
            }
        });

        XposedHelpers.findAndHookMethod(bubble, "onDraw", Canvas.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    Object tag = v.getTag();
                    if (!wsItem.isInstance(tag)) return;
                    int sx = fSpanX.getInt(tag), sy = fSpanY.getInt(tag);
                    if ((sx == 1 && sy == 1) || (sx == 2 && sy == 2) || fContainer.getInt(tag) != -100) return;
                    if (drawStretched(v, (Canvas) p.args[0], tag, sx, sy)) p.setResult(null);
                } catch (Throwable ignored) {
                }
            }
        });
        // запуск и закрытие приложения: лаунчер берёт место иконки из getIconBounds, а для ширины > 1 считает её большой
        // иконкой 2×2 (и окно вылетает не оттуда). Для растянутых — квадрат иконки в центре капсулы
        XposedHelpers.findAndHookMethod(bubble, "getIconBounds", int.class, Rect.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    Object tag = v.getTag();
                    if (!wsItem.isInstance(tag)) return;
                    int sx = fSpanX.getInt(tag), sy = fSpanY.getInt(tag);
                    if ((sx == 1 && sy == 1) || (sx == 2 && sy == 2) || fContainer.getInt(tag) != -100) return;
                    if (!capsule(v, sx, sy, BOUNDS)) return;
                    int ix = Math.round(BOUNDS.centerX() - iconSize / 2f), iy = Math.round(BOUNDS.centerY() - iconSize / 2f);
                    ((Rect) p.args[1]).set(ix, iy, ix + iconSize, iy + iconSize);
                } catch (Throwable ignored) {
                }
            }
        });
        // размер иконки (подсвеченная копия при долгом нажатии, анимации): для ширины > 1 у Nothing — большой 2×2
        XposedHelpers.findAndHookMethod(bubble, "getIconSize", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object tag = ((View) p.thisObject).getTag();
                    if (iconSize == 0 || !wsItem.isInstance(tag)) return;
                    int sx = fSpanX.getInt(tag), sy = fSpanY.getInt(tag);
                    if ((sx == 1 && sy == 1) || (sx == 2 && sy == 2) || fContainer.getInt(tag) != -100) return;
                    p.setResult(iconSize);
                } catch (Throwable ignored) {
                }
            }
        });
        cornerFrame(cl, wsItem);
        folderCapsule(cl);
        XposedBridge.log("derealization: icon resize hooked");
    }

    // ───────── растягивание за угол ─────────

    private static java.lang.ref.WeakReference<Frame> frameRef = new java.lang.ref.WeakReference<>(null);

    /**
     * Долгое нажатие на значок стола: вместе с меню Nothing вокруг значка появляется рамка с ручкой в правом нижнем
     * углу, как у виджета. Тянешь ручку — рамка прилипает к ближайшему допустимому размеру, отпускаешь — значок
     * растягивается (apply, механизм Nothing). Касание ручки закрывает меню и достаётся рамке: иначе меню съело бы его.
     */
    private static void cornerFrame(ClassLoader cl, Class<?> wsItem) {
        Class<?> popup = XposedHelpers.findClassIfExists("com.android.launcher3.popup.PopupContainerWithArrow", cl);
        Class<?> arrow = XposedHelpers.findClassIfExists("com.android.launcher3.popup.ArrowPopup", cl);
        // у Nothing меню значка — своё: NTPopupContainer.showForView(View), со своим closeComplete
        Class<?> nt = XposedHelpers.findClassIfExists("com.nothing.launcher.popup.NTPopupContainer", cl);
        if (popup == null || arrow == null || nt == null) return;
        // showForView — крошечный статический метод (ART встраивает его, хук не срабатывает); стол открывает меню
        // значка в Workspace.handlePopupContainer(DragOptions, View): меню открыто ⇔ у DragOptions есть preDragCondition
        // меню любого элемента стола (значок, папка, большая папка) открывает Workspace.showPopupView, но он и
        // handlePopupContainer мелкие — ART встраивает их; ловим крупный beginDragShared(View, …, DragOptions)
        Class<?> ws = XposedHelpers.findClass("com.android.launcher3.Workspace", cl);
        Hook.all(ws, "beginDragShared", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.args.length < 6 || !(p.args[0] instanceof View)) return;
                    View icon = (View) p.args[0];
                    if (XposedHelpers.getObjectField(p.args[5], "preDragCondition") == null) return;
                    if (!HomeHooks.prefs().cornerResize || !AnimHooks.prefs().iconResize) return;
                    Object tag = icon.getTag();
                    if (!(wsItem.isInstance(tag) || isFolder(tag)) || fContainer.getInt(tag) != -100) return;
                    Activity launcher = activity(icon.getContext());
                    if (launcher == null) return;
                    ViewGroup layer = (ViewGroup) XposedHelpers.callMethod(launcher, "getDragLayer");
                    Frame old = frameRef.get();
                    if (old != null) old.remove();
                    Frame f = new Frame(launcher, layer, icon);
                    layer.addView(f, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    frameRef = new java.lang.ref.WeakReference<>(f);
                } catch (Throwable t) {
                    XposedBridge.log("derealization: icon frame — " + t);
                }
            }
        });
        // палец на ручке: закрыть меню, но касание не съедать — оно уйдёт рамке
        Hook.all(popup, "onControllerInterceptTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                Frame f = frameRef.get();
                MotionEvent e = (MotionEvent) p.args[0];
                if (f == null || e.getActionMasked() != MotionEvent.ACTION_DOWN || !f.onHandle(e.getX(), e.getY())) return;
                f.grabbed = true;
                XposedHelpers.callMethod(p.thisObject, "close", true);
                p.setResult(false);
            }
        });
        // меню закрылось само (касание мимо, запуск, перетаскивание) — рамка тоже, если её не тянут
        XC_MethodHook closed = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Frame f = frameRef.get();
                if (f != null && !f.grabbed) f.remove();
            }
        };
        Hook.all(arrow, "closeComplete", closed);
        Hook.all(nt, "closeComplete", closed);
    }

    private static final class Frame extends View {
        private final Activity launcher;
        private final ViewGroup layer;
        private final View icon;
        private final ViewGroup cellLayout;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), cur = new RectF();
        private final float d;
        private float cellW, cellH, gapX, gapY, originX, originY, scale = 1f;
        private int curX, curY, sx, sy;
        private float downX, downY;
        boolean grabbed;
        private boolean dragging;
        // Material 3 Expressive: цвета — тона Material You системы, движение — пружины
        private final int primary, onPrimary, container, onContainer;
        private final Spring w = new Spring(), h = new Spring(), show = new Spring(), knob = new Spring(), pill = new Spring();
        private long last;
        private final RectF drawR = new RectF();
        private int labelX = -1, labelY = -1;
        private String label = "";
        private static final android.graphics.Typeface MEDIUM = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 500, false);

        Frame(Activity launcher, ViewGroup layer, View icon) {
            super(launcher);
            this.launcher = launcher;
            this.layer = layer;
            this.icon = icon;
            this.cellLayout = (ViewGroup) icon.getParent().getParent();
            d = getResources().getDisplayMetrics().density;
            try {
                Object tag = icon.getTag();
                curX = fSpanX.getInt(tag);
                curY = fSpanY.getInt(tag);
            } catch (Throwable t) {
                curX = curY = 1;
            }
            sx = curX;
            sy = curY;
            measure();
            boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            primary = launcher.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            onPrimary = launcher.getColor(night ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
            container = launcher.getColor(night ? android.R.color.system_accent1_700 : android.R.color.system_accent1_100);
            onContainer = launcher.getColor(night ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900);
            w.snap(box.width());
            h.snap(box.height());
            show.snap(0f);
            show.target = 1f;
            knob.snap(0f);
            knob.target = 1f;
            pill.snap(0f);
            invalidate();
        }

        /** Пружина (M3 Expressive: упругая, с лёгким перелётом) — шаг по времени кадра. */
        private static final class Spring {
            float value, velocity, target;

            void snap(float v) {
                value = target = v;
                velocity = 0f;
            }

            /** true — ещё в движении. stiffness/damping — как у «быстрой пространственной» пружины M3 Expressive. */
            boolean step(float dt, float stiffness, float dampingRatio) {
                float damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);
                float a = -stiffness * (value - target) - damping * velocity;
                velocity += a * dt;
                value += velocity * dt;
                if (Math.abs(value - target) < 0.001f * Math.max(1f, Math.abs(target)) && Math.abs(velocity) < 0.01f) {
                    value = target;
                    velocity = 0f;
                    return false;
                }
                return true;
            }
        }

        /** Клетка значка (и шаг сетки) в координатах слоя перетаскивания. */
        private void measure() {
            try {
                Object lp = icon.getLayoutParams();
                int cx = (Integer) XposedHelpers.callMethod(lp, "getCellX"), cy = (Integer) XposedHelpers.callMethod(lp, "getCellY");
                Rect a = new Rect(), b = new Rect();
                XposedHelpers.callMethod(cellLayout, "cellToRect", cx, cy, 1, 1, a);
                XposedHelpers.callMethod(cellLayout, "cellToRect", cx + 1, cy + 1, 1, 1, b);
                // шаг сетки — по клеткам, сама рамка — по иконке (как капсула растянутого значка, без места под подпись)
                int[] pt = {0, 0};
                scale = (Float) XposedHelpers.callMethod(layer, "getDescendantCoordRelativeToSelf", icon, pt);
                cellW = (b.left - a.left) * scale;
                cellH = (b.top - a.top) * scale;
                RectF cap = new RectF();
                if (curX == 1 && curY == 1 || curX == 2 && curY == 2 || isFolder(icon.getTag())) {
                    // где иконка нарисована на самом деле (с подписями и без них — по-разному): getIconBounds значка
                    if (!isFolder(icon.getTag())) capsule(icon, 1, 1, cap);    // заодно размер иконки из профиля
                    Rect ib = new Rect();
                    if (capsule2x1(icon)) {
                        cap.set(capsuleRect(icon));                             // папка-капсула: её прямоугольник
                    } else {
                        if (isFolder(icon.getTag())) XposedHelpers.callMethod(icon, "getPreviewBounds", ib);
                        else XposedHelpers.callMethod(icon, "getIconBounds", (Integer) XposedHelpers.callMethod(icon, "getIconSize"), ib);
                        cap.set(ib);
                    }
                } else {
                    capsule(icon, curX, curY, cap);
                }
                originX = pt[0] + cap.left * scale;
                originY = pt[1] + cap.top * scale;
                cur.set(originX, originY, originX + cap.width() * scale, originY + cap.height() * scale);
                gapX = cellW - (cur.width() - (curX - 1) * cellW);      // gapX/gapY: сколько не хватает до полной клетки
                gapY = cellH - (cur.height() - (curY - 1) * cellH);
                box.set(cur);
            } catch (Throwable t) {
                XposedBridge.log("derealization: icon frame, grid — " + t);
            }
        }

        /** Скругление рамки: значки, папки 1×1 и капсулы — круги и «таблетки», большая папка 2×2 — скруглённый квадрат. */
        private float corner() {
            float m = Math.min(box.width(), box.height());
            return isFolder(icon.getTag()) && sx == 2 && sy == 2 ? m * 0.22f : m / 2f;
        }

        /** Ручка — на скруглённом углу рамки (рамка шире значка на 6 dp). */
        private float handleX() {
            return box.right + 6 * d - (corner() + 6 * d) * 0.29f;
        }

        private float handleY() {
            return box.bottom + 6 * d - (corner() + 6 * d) * 0.29f;
        }

        boolean onHandle(float x, float y) {
            float r = 30 * d;
            return Math.abs(x - handleX()) < r && Math.abs(y - handleY()) < r;
        }

        private boolean removing;

        /**
         * Убрать рамку — после текущей раздачи касаний: removeView прямо из onTouchEvent ломает список целей касания
         * у слоя (NullPointerException в ViewGroup.removeFromArray — лаунчер падал).
         */
        void remove() {
            if (removing) return;
            removing = true;
            if (frameRef.get() == this) frameRef = new java.lang.ref.WeakReference<>(null);
            layer.post(() -> {
                if (getParent() != null) layer.removeView(this);
            });
        }

        /**
         * Рамка считает в координатах слоя перетаскивания, а сама сдвинута им на высоту строки состояния
         * (InsettableFrameLayout отдаёт детям отступы) — касания и рисование переводим на этот сдвиг.
         */
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX() + getLeft(), y = e.getY() + getTop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!onHandle(x, y)) { remove(); return false; }
                    dragging = true;
                    grabbed = true;
                    downX = x;
                    downY = y;
                    knob.target = 1.3f;
                    pill.target = 1f;
                    invalidate();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    snap(x - downX, y - downY);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging && (sx != curX || sy != curY)) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                        apply(launcher, icon, sx, sy);
                    }
                    remove();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    remove();
                    return true;
            }
            return dragging;
        }

        /** Сдвиг пальца → желаемые клетки → ближайший допустимый размер. Рамка тянется за пальцем, размер — прилипает. */
        private void snap(float dx, float dy) {
            float wantX = curX + dx / cellW, wantY = curY + dy / cellH;
            int bx = sx, by = sy;
            float best = Float.MAX_VALUE;
            for (int[] s : sizes(icon.getTag())) {
                float dist = (s[0] - wantX) * (s[0] - wantX) + (s[1] - wantY) * (s[1] - wantY);
                if (dist < best) { best = dist; bx = s[0]; by = s[1]; }
            }
            if (bx != sx || by != sy) {
                sx = bx;
                sy = by;
                performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
                pill.velocity += 6f;                                     // подпись размера «подпрыгивает»
            }
            w.target = sx * cellW - gapX;
            h.target = sy * cellH - gapY;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = last == 0 ? 0.016f : Math.min(0.032f, (now - last) / 1000f);
            last = now;
            boolean moving = w.step(dt, 700f, 0.62f) | h.step(dt, 700f, 0.62f) | show.step(dt, 500f, 0.7f)
                    | knob.step(dt, 900f, 0.5f) | pill.step(dt, 800f, 0.55f);
            box.set(cur.left, cur.top, cur.left + w.value, cur.top + h.value);

            c.translate(-getLeft(), -getTop());
            float a = Math.max(0f, Math.min(1f, show.value));
            float grow = 4 * d + 4 * d * a;                                  // рамка «раскрывается» от значка
            RectF r = drawR;
            r.set(box);
            r.inset(-grow, -grow);
            float rad = corner() + grow;
            // слой состояния: лёгкая заливка тоном primary
            p.setStyle(Paint.Style.FILL);
            p.setColor((primary & 0x00FFFFFF) | (Math.round(0x29 * a) << 24));
            c.drawRoundRect(r, rad, rad, p);
            // обводка primary, 3 dp
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setColor((primary & 0x00FFFFFF) | (Math.round(255 * a) << 24));
            c.drawRoundRect(r, rad, rad, p);
            // ручка: круг primary с иконкой «растянуть» цветом onPrimary; при захвате пружинит крупнее
            float hx = handleX(), hy = handleY(), kr = 13 * d * Math.max(0f, knob.value);
            p.setStyle(Paint.Style.FILL);
            p.setColor(primary);
            c.drawCircle(hx, hy, kr, p);
            if (kr > 4 * d) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(onPrimary);
                float k = kr * 0.42f;
                c.drawLine(hx - k, hy - k, hx + k, hy + k, p);
                c.drawLine(hx + k, hy + k, hx + k * 0.1f, hy + k, p);
                c.drawLine(hx + k, hy + k, hx + k, hy + k * 0.1f, p);
                c.drawLine(hx - k, hy - k, hx - k * 0.1f, hy - k, p);
                c.drawLine(hx - k, hy - k, hx - k, hy - k * 0.1f, p);
            }
            // размер — тональная «таблетка» над рамкой (primaryContainer)
            float ps = Math.max(0f, pill.value);
            if (ps > 0.02f) {
                if (labelX != sx || labelY != sy) { labelX = sx; labelY = sy; label = sx + " × " + sy; }
                String t = label;
                p.setStyle(Paint.Style.FILL);
                p.setStrokeWidth(0f);
                p.setTextSize(14 * d);
                p.setTypeface(MEDIUM);
                float tw = p.measureText(t), ph = 32 * d, pw = tw + 28 * d;
                float px = r.centerX(), py = r.top - 12 * d - ph / 2;
                if (py - ph / 2 < 8 * d) py = r.bottom + 12 * d + ph / 2;
                c.save();
                c.scale(ps, ps, px, py);
                p.setColor(container);
                c.drawRoundRect(px - pw / 2, py - ph / 2, px + pw / 2, py + ph / 2, ph / 2, ph / 2, p);
                p.setColor(onContainer);
                c.drawText(t, px - tw / 2, py - (p.descent() + p.ascent()) / 2, p);
                c.restore();
            }
            if (moving) postInvalidateOnAnimation();
            else last = 0;
        }
    }

    // ───────── рисование растянутого значка ─────────

    private static final RectF BOUNDS = new RectF();

    /** Капсула растянутого значка в координатах вида: от иконки первой ячейки до иконки последней. */
    private static boolean capsule(View v, int sx, int sy, RectF out) throws Exception {
        if (iconSize == 0) {
            Activity a = activity(v.getContext());
            if (a == null) return false;
            Object dp = XposedHelpers.callMethod(a, "getDeviceProfile");
            iconSize = XposedHelpers.getIntField(dp, "iconSizePx");
            drawPad = XposedHelpers.getIntField(dp, "iconDrawablePaddingPx");
        }
        Paint.FontMetrics fm = ((android.widget.TextView) v).getPaint().getFontMetrics();
        float textH = fm.descent - fm.ascent;
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return false;
        float cw = (float) w / sx, ch = (float) h / sy;
        float iconTop = Math.max(0f, (ch - (iconSize + drawPad + textH)) / 2f);
        float left = (cw - iconSize) / 2f;
        out.set(left, iconTop, w - left, h - ch + iconTop + iconSize);
        return true;
    }

    private static boolean drawStretched(View v, Canvas c, Object tag, int sx, int sy) throws Exception {
        Drawable icon = mGetIcon != null ? (Drawable) mGetIcon.invoke(v) : null;
        if (icon == null || !capsule(v, sx, sy, CAPS)) return false;
        android.widget.TextView tv = (android.widget.TextView) v;
        TextPaint tp = tv.getPaint();
        Paint.FontMetrics fm = tp.getFontMetrics();
        int w = v.getWidth();
        float left = CAPS.left, bottom = CAPS.bottom;
        float r = Math.min(CAPS.width(), CAPS.height()) / 2f;
        FILL.setColor(background(icon));
        c.drawRoundRect(CAPS, r, r, FILL);
        // иконка — по центру капсулы, своего обычного размера
        SAVED.set(icon.getBounds());
        int ix = Math.round(CAPS.centerX() - iconSize / 2f), iy = Math.round(CAPS.centerY() - iconSize / 2f);
        icon.setBounds(ix, iy, ix + iconSize, iy + iconSize);
        icon.draw(c);
        icon.setBounds(SAVED);
        // подпись — под капсулой, как у соседних значков
        CharSequence text = tv.getText();
        if (text != null && text.length() > 0) {
            tp.setColor(tv.getCurrentTextColor());
            CharSequence t = TextUtils.ellipsize(text, tp, w - left, TextUtils.TruncateAt.END);
            float tw = tp.measureText(t, 0, t.length());
            c.drawText(t, 0, t.length(), (w - tw) / 2f, bottom + drawPad - fm.ascent, tp);
        }
        return true;
    }

    private static final java.util.WeakHashMap<Drawable, Integer> COLORS = new java.util.WeakHashMap<>();

    /**
     * Цвет фона иконки. Тематические значки (ThemedIconDrawable) хранят его сами — colorBg. У обычных — пиксель у
     * левого края внутри формы исходной картинки (она аппаратная — копируем один раз), запасной — основной цвет иконки.
     * Один раз на иконку.
     */
    private static int background(Drawable icon) {
        Integer cached = COLORS.get(icon);
        if (cached != null) return cached;
        int color = 0xFF2A2A2E;
        try {
            Field bg = XposedHelpers.findFieldIfExists(icon.getClass(), "colorBg");
            if (bg != null) {
                color = bg.getInt(icon) | 0xFF000000;
            } else {
                Object info = XposedHelpers.getObjectField(icon, "mBitmapInfo");
                color = XposedHelpers.getIntField(info, "color") | 0xFF000000;
                Bitmap b = (Bitmap) XposedHelpers.getObjectField(info, "icon");
                if (b != null && !b.isRecycled()) {
                    Bitmap sw = b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
                    for (float fx : new float[]{0.14f, 0.2f, 0.28f}) {
                        int px = sw.getPixel(Math.round(sw.getWidth() * fx), sw.getHeight() / 2);
                        if (Color.alpha(px) > 220) { color = px | 0xFF000000; break; }
                    }
                    if (sw != b) sw.recycle();
                }
            }
        } catch (Throwable ignored) {
        }
        COLORS.put(icon, color);
        return color;
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ───────── смена размера ─────────

    private static void apply(Activity launcher, View view, int sx, int sy) {
        if (isFolder(view.getTag())) {
            applyFolder(launcher, view, sx, sy);
            return;
        }
        try {
            Object info = view.getTag();
            int cx = fSpanX.getInt(info), cy = fSpanY.getInt(info);
            if (cx == sx && cy == sy) return;
            Object rm = XposedHelpers.callMethod(launcher, "getResizeManager");
            // родной большой значок 2×2 из 1×1 — целиком механизмом Nothing (своя картинка и анимация)
            if (cx == 1 && cy == 1 && sx == 2 && sy == 2) {
                XposedHelpers.callMethod(rm, "E", view);
                return;
            }
            // любой другой размер: сначала обратно в 1×1 на исходное место (без анимации), потом растягиваем
            if (cx != 1 || cy != 1) {
                if (fBitmap != null) fBitmap.set(info, XposedHelpers.callMethod(rm, "s", info, false));
                XposedHelpers.callMethod(rm, "z", info, view, true);
            }
            if (sx == 1 && sy == 1) {
                pop(view);
                return;
            }
            Object ws = XposedHelpers.callMethod(launcher, "getWorkspace");
            Object cell = XposedHelpers.callMethod(ws, "getScreenWithId", XposedHelpers.getIntField(info, "screenId"));
            if (cell == null) return;
            Object lp = view.getLayoutParams();
            int ow = XposedHelpers.getIntField(rm, "b"), oh = XposedHelpers.getIntField(rm, "c");
            Object b;
            try {
                XposedHelpers.setIntField(rm, "b", sx);
                XposedHelpers.setIntField(rm, "c", sy);
                b = XposedHelpers.callMethod(rm, "n", cell, lp);
                if (!(Boolean) XposedHelpers.callMethod(b, "d")) XposedHelpers.callMethod(rm, "l", cell, view, lp, b);
            } finally {
                XposedHelpers.setIntField(rm, "b", ow);
                XposedHelpers.setIntField(rm, "c", oh);
            }
            if ((Integer) XposedHelpers.callMethod(b, "c") <= -1) {
                int id = launcher.getResources().getIdentifier("out_of_space", "string", launcher.getPackageName());
                Toast.makeText(launcher, id != 0 ? launcher.getString(id) : L.t("No room", "Нет места"), Toast.LENGTH_SHORT).show();
                return;
            }
            XposedHelpers.setIntField(info, "resizeCellX", XposedHelpers.getIntField(info, "cellX"));
            XposedHelpers.setIntField(info, "resizeCellY", XposedHelpers.getIntField(info, "cellY"));
            XposedHelpers.setBooleanField(info, "useResizeCoords", true);
            int nx = (Integer) XposedHelpers.callMethod(b, "a"), ny = (Integer) XposedHelpers.callMethod(b, "b");
            XposedHelpers.setIntField(info, "cellX", nx);
            XposedHelpers.setIntField(info, "cellY", ny);
            fSpanX.setInt(info, sx);
            fSpanY.setInt(info, sy);
            XposedHelpers.setIntField(info, "minSpanX", sx);
            XposedHelpers.setIntField(info, "minSpanY", sy);
            XposedHelpers.callMethod(lp, "setCellX", nx);
            XposedHelpers.callMethod(lp, "setCellY", ny);
            XposedHelpers.setIntField(lp, "cellHSpan", sx);
            XposedHelpers.setIntField(lp, "cellVSpan", sy);
            // обновить вид, занятость ячеек и базу — штатным z(…, false)
            XposedHelpers.callMethod(rm, "z", info, view, false);
            pop(view);
        } catch (Throwable t) {
            XposedBridge.log("derealization: icon resize — " + t);
        }
    }

    /**
     * Папка: механизм Nothing «Увеличить» (ResizeManager.E) делает из неё большую папку размера (b × c) — подставляем
     * 2×1 для капсулы или 2×2; «Уменьшить» (F) возвращает обычную. Из одного большого размера в другой — сначала F,
     * потом, когда новый вид встанет на место, E.
     */
    private static void applyFolder(Activity launcher, View view, int sx, int sy) {
        try {
            Object info = view.getTag();
            int cx = fSpanX.getInt(info), cy = fSpanY.getInt(info);
            if (cx == sx && cy == sy) return;
            Object rm = XposedHelpers.callMethod(launcher, "getResizeManager");
            Runnable grow = () -> {
                if (sx == 1 && sy == 1) return;
                try {
                    Object ws = XposedHelpers.callMethod(launcher, "getWorkspace");
                    View v = (View) XposedHelpers.callMethod(ws, "getViewByItemId", XposedHelpers.getIntField(info, "id"));
                    if (v == null) return;
                    int ow = XposedHelpers.getIntField(rm, "b"), oh = XposedHelpers.getIntField(rm, "c");
                    try {
                        XposedHelpers.setIntField(rm, "b", sx);
                        XposedHelpers.setIntField(rm, "c", sy);
                        XposedHelpers.callMethod(rm, "E", v);
                    } finally {
                        XposedHelpers.setIntField(rm, "b", ow);
                        XposedHelpers.setIntField(rm, "c", oh);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("derealization: folder — enlarge " + t);
                }
            };
            if (cx > 1 && sx > 1) {
                bigToBig(launcher, view, info, sx, sy);                // капсула ↔ большая: на месте, соседей не трогаем
            } else if (cx > 1) {
                XposedHelpers.callMethod(rm, "F", view);               // в обычную папку
            } else {
                grow.run();
            }
        } catch (Throwable t) {
            XposedBridge.log("derealization: folder size — " + t);
        }
    }

    /**
     * Капсула 2×1: большая папка Nothing раскладывает превью сеткой под квадрат 2×2. Если она высотой в одну клетку —
     * кладём до четырёх значков одним рядом по центру, остальные прячем.
     */
    static void folderCapsule(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.nothing.launcher.folder.BigFolderIconContainer", cl);
        Class<?> big = XposedHelpers.findClassIfExists("com.android.launcher3.folder.BigFolderIcon", cl);
        Class<?> bg = XposedHelpers.findClassIfExists("com.android.launcher3.folder.PreviewBackground", cl);
        if (c == null || big == null || bg == null) return;
        // большая папка меряет и рисует фон квадратом от ширины — у капсулы 2×1 свой фон и своё место для значков
        Hook.all(big, "onMeasure", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                if (!capsule2x1(v)) return;
                RectF r = capsuleRect(v);
                View box = (View) XposedHelpers.callMethod(v, "getBigFolderIconContainer");
                int in = Math.round(r.height() * 0.06f);
                box.measure(View.MeasureSpec.makeMeasureSpec(Math.round(r.width()) - 2 * in, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(Math.round(r.height()) - 2 * in, View.MeasureSpec.EXACTLY));
            }
        });
        Hook.all(big, "onLayout", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                if (!capsule2x1(v)) return;
                RectF r = capsuleRect(v);
                View box = (View) XposedHelpers.callMethod(v, "getBigFolderIconContainer");
                int in = Math.round(r.height() * 0.06f);
                box.layout(Math.round(r.left) + in, Math.round(r.top) + in, Math.round(r.right) - in, Math.round(r.bottom) - in);
                // раскладка значков в контейнере — в его layout; фон-превью выставим после неё
                v.post(() -> {
                    if (!capsule2x1(v)) return;
                    View last = lastLaunched.get();
                    ViewGroup g = (ViewGroup) box;
                    previewTo(v, last != null && last.getParent() == g ? last : g.getChildCount() > 0 ? g.getChildAt(0) : null);
                });
            }
        });
        Hook.all(big, "dispatchDraw", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                View v = (View) p.thisObject;
                if (!capsule2x1(v)) return;
                try {
                    Object back = XposedHelpers.getObjectField(v, "background");          // PreviewBackground папки
                    RectF r = capsuleRect(v);
                    int col = (Integer) XposedHelpers.callMethod(back, "getBgColor");
                    LucentPrefs l = LucentHooks.prefs();
                    if (l.enabled && l.applies(LucentHooks.itemId(v))) {
                        // прозрачная папка: размытые обои под капсулой и фон с убавленной альфой
                        if (l.blur) LucentBlur.draw((Canvas) p.args[0], back, v, v, r, r.height() / 2f, l);
                        col = Color.argb(Math.round(Color.alpha(col) * l.opacity), Color.red(col), Color.green(col), Color.blue(col));
                    }
                    FILL.setColor(col);
                    ((Canvas) p.args[0]).drawRoundRect(r, r.height() / 2f, r.height() / 2f, FILL);
                    capsuleDrawing = true;                     // родной квадратный фон — пропустить
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                capsuleDrawing = false;
            }
        });
        Hook.all(bg, "drawBackground", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (capsuleDrawing) p.setResult(null);
            }
        });
        // закрытие приложения из папки: лаунчер целится в саму папку — getPreviewBounds = квадрат фона (поля
        // PreviewBackground: сдвиг и previewSize; методы мелкие, встроены — хуки на них молчат). У капсулы окно садилось
        // квадратом мимо значков. Пишем в эти поля квадрат значка открытого приложения (по умолчанию — первого)
        Class<?> click = XposedHelpers.findClassIfExists("com.android.launcher3.touch.ItemClickHandler", cl);
        if (click != null) Hook.all(click, "onClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 1 && p.args[0] instanceof View && c.isInstance(((View) p.args[0]).getParent())) {
                    View child = (View) p.args[0];
                    lastLaunched = new java.lang.ref.WeakReference<>(child);
                    View folder = (View) child.getParent().getParent();
                    if (capsule2x1(folder)) previewTo(folder, child);
                }
            }
        });
        // масштаб значка папки для анимаций запуска/закрытия приложения: у Nothing — под сетку 2×2, у капсулы значки
        // другого размера → окно садилось вдвое крупнее значка. Даём масштаб от реального значка капсулы
        Hook.all(c, "getChildScale", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    ViewGroup box = (ViewGroup) p.thisObject;
                    Object info = XposedHelpers.callMethod(box, "getInfo");
                    int i = (Integer) p.args[0];
                    if (fSpanY.getInt(info) != 1 || fSpanX.getInt(info) != 2 || i >= Math.min(4, box.getChildCount())) return;
                    Activity a = activity(box.getContext());
                    if (a == null) return;
                    int full = XposedHelpers.getIntField(XposedHelpers.callMethod(a, "getDeviceProfile"), "iconSizePx");
                    View child = box.getChildAt(i);
                    if (full > 0 && child.getWidth() > 0) p.setResult((float) child.getWidth() / full);
                } catch (Throwable ignored) {
                }
            }
        });
        Hook.all(c, "layoutChild", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    ViewGroup box = (ViewGroup) p.thisObject;
                    Object info = XposedHelpers.callMethod(box, "getInfo");
                    View child = (View) p.args[0];
                    if (fSpanY.getInt(info) != 1 || fSpanX.getInt(info) != 2) {
                        // папка снова не капсула — вернуть значку родной размер иконки и отступы
                        int[] orig = (int[]) XposedHelpers.getAdditionalInstanceField(child, "ntCapsule");
                        if (orig != null) {
                            XposedHelpers.removeAdditionalInstanceField(child, "ntCapsule");
                            child.setPadding(orig[1], orig[2], orig[3], orig[4]);
                            iconSize(child, orig[0]);
                        }
                        return;
                    }
                    int i = box.indexOfChild(child);
                    int w = box.getWidth(), h = box.getHeight();
                    float gap = h * 0.05f;
                    int size = Math.round(Math.min(h * 0.92f, (w - h * 0.16f - 3 * gap) / 4f));
                    int n = Math.min(4, box.getChildCount());
                    float total = n * size + (n - 1) * gap;
                    int left = Math.round((w - total) / 2f + i * (size + gap)), top = (h - size) / 2;
                    if (DEBUG_CAPSULE) XposedBridge.log("derealization: capsule — " + i + "/" + box.getChildCount() + " " + child.getTag()
                            + " vis " + child.getVisibility() + " alpha " + child.getAlpha() + " scrollX " + box.getScrollX());
                    if (i >= 4) child.layout(0, 0, 0, 0);
                    else {
                        // без масштаба (его анимирует сама Nothing при запуске и закрытии приложения): значок получает
                        // размер иконки под свою клетку в ряду и рисует её штатно, без отступов
                        if (XposedHelpers.getAdditionalInstanceField(child, "ntCapsule") == null) {
                            XposedHelpers.setAdditionalInstanceField(child, "ntCapsule", new int[]{
                                    XposedHelpers.getIntField(child, "mIconSize"), child.getPaddingLeft(), child.getPaddingTop(),
                                    child.getPaddingRight(), child.getPaddingBottom()});
                            child.setPadding(0, 0, 0, 0);
                        }
                        iconSize(child, size);
                        child.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
                        child.layout(left, top, left + size, top + size);
                    }
                    p.setResult(null);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * Большая папка → большая другого размера (2×1 ↔ 2×2) — без «уменьшить, потом увеличить»: Nothing при увеличении
     * расталкивал соседей. Если клетки свободны — меняем размер на месте (занятость клеток, вид, база), иначе «нет места».
     */
    private static void bigToBig(Activity launcher, View view, Object info, int sx, int sy) {
        try {
            ViewGroup cell = (ViewGroup) view.getParent().getParent();
            int x = XposedHelpers.getIntField(info, "cellX"), y = XposedHelpers.getIntField(info, "cellY");
            XposedHelpers.callMethod(cell, "markCellsAsUnoccupiedForView", view);
            if (!(Boolean) XposedHelpers.callMethod(cell, "isRegionVacant", x, y, sx, sy)) {
                XposedHelpers.callMethod(cell, "markCellsAsOccupiedForView", view);
                int id = launcher.getResources().getIdentifier("out_of_space", "string", launcher.getPackageName());
                Toast.makeText(launcher, id != 0 ? launcher.getString(id) : L.t("No room", "Нет места"), Toast.LENGTH_SHORT).show();
                return;
            }
            fSpanX.setInt(info, sx);
            fSpanY.setInt(info, sy);
            Object lp = view.getLayoutParams();
            XposedHelpers.setIntField(lp, "cellHSpan", sx);
            XposedHelpers.setIntField(lp, "cellVSpan", sy);
            XposedHelpers.callMethod(cell, "markCellsAsOccupiedForView", view);
            view.requestLayout();
            View box = (View) XposedHelpers.callMethod(view, "getBigFolderIconContainer");
            box.requestLayout();
            Object writer = XposedHelpers.callMethod(launcher, "getModelWriter");
            XposedHelpers.callMethod(writer, "modifyItemInDatabase", info, XposedHelpers.getIntField(info, "container"),
                    XposedHelpers.getIntField(info, "screenId"), x, y, sx, sy);
            pop(view);
        } catch (Throwable t) {
            XposedBridge.log("derealization: folder, resize in place — " + t);
        }
    }

    private static boolean capsuleDrawing;
    private static java.lang.ref.WeakReference<View> lastLaunched = new java.lang.ref.WeakReference<>(null);
    private static final boolean DEBUG_CAPSULE = false;

    /** Квадрат «превью» папки-капсулы = значок child (в координатах папки) — туда садится окно закрытого приложения. */
    private static void previewTo(View folder, View child) {
        if (child == null || child.getWidth() <= 0) return;
        try {
            Object back = XposedHelpers.getObjectField(folder, "background");
            View box = (View) child.getParent();
            XposedHelpers.setIntField(back, "basePreviewOffsetX", box.getLeft() + child.getLeft());
            XposedHelpers.setIntField(back, "basePreviewOffsetY", box.getTop() + child.getTop());
            XposedHelpers.setIntField(back, "previewSize", child.getWidth());
        } catch (Throwable t) {
            XposedBridge.log("derealization: capsule, preview — " + t);
        }
    }

    /** Размер иконки значка (BubbleTextView.mIconSize) и перекладка картинки штатным applyCompoundDrawables. */
    private static void iconSize(View v, int size) {
        try {
            if (XposedHelpers.getIntField(v, "mIconSize") == size) return;
            XposedHelpers.setIntField(v, "mIconSize", size);
            Object icon = XposedHelpers.callMethod(v, "getIcon");
            if (icon != null) XposedHelpers.callMethod(v, "applyCompoundDrawables", icon);
            v.invalidate();
        } catch (Throwable ignored) {
        }
    }

    private static boolean capsule2x1(View bigFolder) {
        try {
            Object info = bigFolder.getTag();
            return isFolder(info) && fSpanX.getInt(info) == 2 && fSpanY.getInt(info) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Капсула папки 2×1: во всю ширину двух клеток, высотой как виджеты 2×1 Nothing (~¾ клетки), по центру. */
    private static RectF capsuleRect(View v) {
        float w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
        float capH = h * 0.76f, pad = (h - capH) / 2f;
        return new RectF(pad * 0.6f, pad, w - pad * 0.6f, pad + capH);
    }

    /** Лёгкий «пружинящий» отклик значка после смены размера. */
    private static void pop(View v) {
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }
}
