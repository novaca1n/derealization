package dev.novacain.ntclocks.anim;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Настройки анимаций. Лежат в Settings.Global (ключ ntweaker_anim, JSON): их читают процессы лаунчера и SystemUI,
 * пишет приложение (право WRITE_SECURE_SETTINGS у него есть). Стиль «nothing» = ничего не подменять.
 */
public final class AnimPrefs {
    public static final String KEY = "ntweaker_anim";
    public static final String NOTHING = "nothing", IOS = "ios", COLOROS = "coloros", COLOROS_FAST = "coloros_fast", COLOROS17 = "coloros17", ORIGIN = "origin", CUSTOM = "custom";
    public static final String IN_NATIVE = "native";

    public String style = NOTHING;
    // пружина вылета окна из иконки (открытие) и обратно в иконку / жест «домой» (закрытие)
    public float openStiffness = 342f, openDamping = 1f;
    public float closeStiffness = 342f, closeDamping = 1f;
    public float homeScale = 0.97f;        // насколько отдаляется рабочий стол при открытии приложения
    public float blur = 34f;               // размытие фона, dp (на CMF Phone 2 Pro не поддерживается железом — в интерфейсе нет)
    public boolean depth = false;          // обои уходят вглубь при открытии (у Nothing на этой модели выключено)
    // переходы внутри и между приложениями (WM Shell)
    public String inApp = IN_NATIVE;       // native / ios / coloros / origin
    public float inAppSpeed = 1f;          // множитель длительности (>1 — медленнее)
    // OriginOS 17: стол (иконки и обои) размывается, пока открыто/открывается приложение или меню; 0 — выключено
    public float homeBlur = 0f;            // dp
    public String drawer = NOTHING;        // меню приложений: nothing — выезд снизу (Nothing), origin — из глубины, ios — наплывает
    public String shade = NOTHING;         // шторка: nothing, origin — вырастает от верха, coloros — как ColorOS 16, ios — догоняет палец
    public boolean stretch = false;        // окно «растягивается»: ближние к краям экрана стороны прилетают раньше (как в iOS)
    public boolean stretchBottom = false;  // «ColorOS 17»: дальний от иконки край окна приходит первым (иконка внизу — верх, вверху — низ)
    public boolean iconPress = false;      // нажатие на иконку как в ColorOS: сжатие до 85 % и затемнение
    public boolean debug = false;          // журнал кадров анимации окна в logcat (тег ntanim); только вручную, в приложении нет
    public boolean warp = false;
    public boolean iconResize = true;
    public String sysMotion = NOTHING;      // папки, панель громкости, нажатия на плитки: nothing / coloros17
    public boolean gravity = false;         // «Гравитация»: встряхнул на столе — значки падают и пересыпаются (Gravity)
    public String volume = NOTHING;         // панель громкости: nothing, coloros — растёт от края, ios — выезжает из-за края
    public boolean splitShade = false;     // шторка как в NOS 5: справа — быстрые настройки, слева — уведомления      // значки на столе растягиваются как виджеты (2×1, 1×2, 1×3…) — пункт «Увеличить»           // интерфейс приложения тянется по форме летящего окна (неравномерный масштаб)

    public static AnimPrefs preset(String style) {
        AnimPrefs p = new AnimPrefs();
        p.style = style;
        switch (style) {
            case IOS:
                // iOS 26: открытие мягкое, почти без перелёта; закрытие «прилетает» в иконку с лёгким отскоком
                p.openStiffness = 300f; p.openDamping = 0.92f;
                p.closeStiffness = 330f; p.closeDamping = 0.8f;
                p.homeScale = 0.92f; p.blur = 36f;
                p.inApp = IOS; p.depth = true; p.stretch = true;
                break;
            case COLOROS:
                // Настоящие параметры ColorOS 16 (OplusLauncher, SpringAnimConstants, режим «Умеренная»):
                // запуск AppLaunchParam 420 / 1.12 (без отскока, мягкий доезд), закрытие AppCloseToHomeParam 160 / 0.86,
                // стол уходит до LauncherContentAnimManager.MIN_SCALE = 0.9
                p.openStiffness = 420f; p.openDamping = 1.12f;
                p.closeStiffness = 160f; p.closeDamping = 0.86f;
                p.homeScale = 0.9f; p.blur = 40f;
                p.inApp = COLOROS; p.depth = true; p.shade = COLOROS;
                break;
            case COLOROS_FAST:
                // ColorOS 16, режим «Быстрая» (ENHANCE): запуск AppLaunchParam 420 / 1.04, закрытие 250 / 0.86
                p.openStiffness = 420f; p.openDamping = 1.04f;
                p.closeStiffness = 250f; p.closeDamping = 0.86f;
                p.homeScale = 0.9f; p.blur = 40f;
                p.inApp = COLOROS; p.depth = true; p.shade = COLOROS;
                break;
            case COLOROS17:
                // «ColorOS 17»: пружины ColorOS, окно открывается неравномерно, как в iOS 26/27 (сначала упирается
                // в боковые края, ближний бок — первым), шторка — точно как в ColorOS 16, стол за приложением размыт
                p.openStiffness = 260f; p.openDamping = 0.9f;
                p.closeStiffness = 270f; p.closeDamping = 0.86f;
                p.homeScale = 0.9f; p.blur = 40f;
                // обои вглубь — выкл.: масштабированный слой обоев композитор собирает видеочипом, замер FrameTimeline
                // (6 повторов): запуск 4.65 → 3.18 % пропусков, жест 5.12 → 3.77 % (сток 2.8 / 3.75)
                p.inApp = COLOROS; p.depth = false;
                p.stretch = true; p.stretchBottom = true; p.iconPress = true; p.warp = true; p.homeBlur = 22f;
                // остальная система — по коду ColorOS 16 (OplusFolderAnimationManager, VolumeAnimUtil, PressFeedback)
                // с отскоком при отпускании, как обещает ColorOS 17 («Fluid Fusion»); шторка — ColorOS, меню — родное
                p.shade = COLOROS; p.drawer = NOTHING; p.sysMotion = COLOROS17; p.volume = IOS;
                break;
            case ORIGIN:
                // OriginOS 17 (по видео): окно быстро и упруго вылетает из иконки, стол уходит вглубь и размывается
                // целиком — иконки и обои; меню приложений проявляется из глубины, шторка вырастает от верха
                // плавнее: мягче пружины, окно «растягивается» — ближние к краям стороны прилетают раньше
                p.openStiffness = 330f; p.openDamping = 0.86f;
                p.closeStiffness = 360f; p.closeDamping = 0.8f;
                p.homeScale = 0.88f; p.blur = 44f;
                p.inApp = ORIGIN; p.depth = true;
                p.homeBlur = 26f; p.drawer = ORIGIN; p.shade = COLOROS; p.stretch = true;
                break;
            default:
                break;
        }
        return p;
    }

    public static AnimPrefs fromJson(String json) {
        AnimPrefs p = new AnimPrefs();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject o = new JSONObject(json);
            p.style = o.optString("style", p.style);
            p.openStiffness = clamp((float) o.optDouble("openStiffness", p.openStiffness), 50f, 1500f);
            p.openDamping = clamp((float) o.optDouble("openDamping", p.openDamping), 0.55f, 1.2f);
            p.closeStiffness = clamp((float) o.optDouble("closeStiffness", p.closeStiffness), 50f, 1500f);
            p.closeDamping = clamp((float) o.optDouble("closeDamping", p.closeDamping), 0.55f, 1.2f);
            p.homeScale = clamp((float) o.optDouble("homeScale", p.homeScale), 0.7f, 1f);
            p.blur = clamp((float) o.optDouble("blur", p.blur), 0f, 64f);
            p.inApp = o.optString("inApp", p.inApp);
            p.depth = o.optBoolean("depth", p.depth);
            p.inAppSpeed = clamp((float) o.optDouble("inAppSpeed", p.inAppSpeed), 0.4f, 2.5f);
            p.homeBlur = clamp((float) o.optDouble("homeBlur", p.homeBlur), 0f, 48f);
            p.drawer = o.optString("drawer", p.drawer);
            p.shade = o.optString("shade", p.shade);
            p.stretch = o.optBoolean("stretch", p.stretch);
            p.stretchBottom = o.optBoolean("stretchBottom", p.stretchBottom);
            p.iconPress = o.optBoolean("iconPress", p.iconPress);
            p.warp = o.optBoolean("warp", p.warp);
            p.iconResize = o.optBoolean("iconResize", p.iconResize);
            p.splitShade = o.optBoolean("splitShade", p.splitShade);
            p.sysMotion = o.optString("sysMotion", p.sysMotion);
            // до 3.6 громкость шла вместе с sysMotion
            p.volume = o.optString("volume", COLOROS17.equals(p.sysMotion) ? COLOROS : p.volume);
            p.gravity = o.optBoolean("gravity", p.gravity);
            p.debug = o.optBoolean("debug", false);
        } catch (JSONException ignored) {
        }
        return p;
    }

    public String toJson() {
        try {
            return new JSONObject().put("style", style)
                    .put("openStiffness", openStiffness).put("openDamping", openDamping)
                    .put("closeStiffness", closeStiffness).put("closeDamping", closeDamping)
                    .put("homeScale", homeScale).put("blur", blur)
                    .put("inApp", inApp).put("inAppSpeed", inAppSpeed).put("depth", depth)
                    .put("homeBlur", homeBlur).put("drawer", drawer).put("shade", shade).put("stretch", stretch)
                    .put("stretchBottom", stretchBottom).put("iconPress", iconPress).put("warp", warp).put("iconResize", iconResize).put("splitShade", splitShade).put("sysMotion", sysMotion).put("volume", volume).put("gravity", gravity).toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public AnimPrefs copy() {
        return fromJson(toJson());
    }

    /** ColorOS (обычный и быстрый) — X, Y и размер окна на одной жёсткости, как в SpringAnimConstants. */
    public boolean colorOs() {
        return COLOROS.equals(style) || COLOROS_FAST.equals(style);   // «ColorOS 17» — свои пропорции (растягивание)
    }

    /** Папки, панель громкости и нажатия — как в ColorOS 17. */
    public boolean coloros17Motion() {
        return COLOROS17.equals(sysMotion);
    }

    /** Лаунчер подменяет пружины, только если стиль не «родной». */
    public boolean overridesLauncher() {
        return !NOTHING.equals(style);
    }

    public static AnimPrefs read(ContentResolver cr) {
        try {
            return fromJson(Settings.Global.getString(cr, KEY));
        } catch (Throwable t) {
            return new AnimPrefs();
        }
    }

    public static void write(ContentResolver cr, AnimPrefs p) {
        Settings.Global.putString(cr, KEY, p.toJson());
    }

    static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
