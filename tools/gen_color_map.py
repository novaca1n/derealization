#!/usr/bin/env python3
"""Таблица перекраски Nothing: из ресурсов SystemUI/лаунчера (values и values-night) берём цвета, которые в итоге
ссылаются на @android:color/system_neutral1_N, и пишем ColorMap.java: имя ресурса, роль, тон днём и ночью.
Роли: ACCENT — «включённое» (станет accent1), SURFACE — серые подложки (станут тонированным neutral2),
ICON_BG / ICON_FG — монохромные значки лаунчера."""
import re, sys
TARGETS = {
    "com.android.systemui": ("/mnt/build/nothing-ui/systemui/resources/res", {
        "ACCENT": ["qs_tile_active_background_color", "qs_page_indicator_active_color",
                   "bluetooth_page_indicator_nothing_active_color", "brightness_color_foreground", "volume_slider_color",
                   "color_music_progress_bar", "systemui_dialog_progress_foreground", "systemui_dialog_switch_track_on",
                   "qs_twin_button_divider_active_color", "page_indicator_selected_color",
                   "color_media_page_indicator_selected", "volume_select_bg_color"],
        "NOTIF": ["notification_background_color"],
        "WBG": ["widget_state_off_color"],
        "WFG": ["widget_icon_state_off_color"],
        "SURFACE": ["qs_tile_inactive_background_color", "qs_tile_unavailable_background_color",
                    "brightness_color_background", "color_music_progress_bar_bg", "progress_background",
                    "color_media_switch_icon_bg", "qs_edit_panel_background", "qs_edit_panel_tile_item_background",
                    "tile_request_dialog_background", "systemui_dialog_progress_background",
                    "nt_dialog_switch_corner_background_color",
                    # панель громкости: фон и точки шкалы
                    "volume_dialog_color", "volume_dot_color"],
    }),
    "com.nothing.launcher": ("/mnt/build/nothing-ui/launcher/resources/res", {
        "ICON_BG": ["mono_nothing_background_color", "nt_widgets_full_sheet_themed_icon_bg_color"],
        "ICON_FG": ["mono_nothing_foreground_color"],
        "SURFACE": ["folder_cover_item_bg_color", "folder_type_icon_bg_color",
                    # большая папка на рабочем столе (селекторы res/color)
                    "nt_folder_background", "color_folder_background",
                    # меню приложений: карточки групп, поиск, панель стиля
                    "nt_all_apps_content_background_color", "color_all_apps_content_background_color",
                    "color_all_apps_content_background_color_in_icon_pack", "all_search_item_color",
                    "setting_drawer_style_panel_bg"],
        # плитки списка виджетов НЕ трогаем: их цвет совпал бы с фоном виджетов, и превью «растворились» бы
    }),
}

def load(path):
    """Цвета из values*/colors.xml и (рядом) однозначные селекторы res/color*/<имя>.xml — у них один <item android:color>."""
    import os, glob
    out = {}
    try:
        out.update(re.findall(r'<color name="([^"]+)">([^<]+)</color>', open(path).read()))
    except FileNotFoundError:
        pass
    res = os.path.dirname(os.path.dirname(path))
    night = "values-night" in path
    for f in glob.glob(res + ("/color-night/*.xml" if night else "/color/*.xml")):
        items = re.findall(r'<item[^>]*android:color="([^"]+)"', open(f).read())
        if len(items) == 1:
            out.setdefault(os.path.basename(f)[:-4], items[0])
    return out

SH = [0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]

def lstar(h):
    """Светлота L* серого цвета #RRGGBB/#FFRRGGBB; не серый или прозрачный — None."""
    h = h.lstrip('#')
    if len(h) == 8:
        if h[:2].lower() != 'ff': return None
        h = h[2:]
    if len(h) != 6: return None
    r, g, b = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    if max(r, g, b) - min(r, g, b) > 0.06: return None
    lin = lambda c: c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    return 116 * (y ** (1 / 3) if y > 0.008856 else (7.787 * y + 16 / 116)) - 16

def resolve(name, tables):
    for _ in range(8):
        v = None
        for t in tables:
            if name in t:
                v = t[name].strip(); break
        if v is None:
            return None
        m = re.match(r"@android:color/system_neutral1_(\d+)$", v)
        if m: return int(m.group(1))
        if v == "@android:color/white": return 0
        if v == "@android:color/black": return 1000
        if v.startswith('#'):
            L = lstar(v)
            return None if L is None else min(SH, key=lambda x: abs(x - (1000 - L * 10)))
        m = re.match(r"@color/(.+)$", v)
        if not m: return None
        name = m.group(1)
    return None

# Виджеты Nothing (их встраивает лаунчер из ресурсов этих приложений): берём серые цвета с widget/clock/card в имени.
# Фон → WBG (глубокий тонированный accent2), цифры/значки/точки → WFG (accent1), вторичный текст средних тонов →
# SURFACE (тонированный серый). Always-on и cardview не трогаем.
WIDGETS = {f"com.nothing.{a}": f"/mnt/build/nothing-ui/widgets/{a}/res" for a in [
    "hearthstone", "weather", "aiwidget", "smartcenter", "gallery", "ntessentialspace", "soundrecorder",
    "ntessentialrecorder", "cardservice", "games", "camera",
]}
# приложения, которые целиком — виджеты: берём все серые цвета, а не только с widget/clock/card в имени
WIDGET_ONLY = {"com.nothing.hearthstone", "com.nothing.weather", "com.nothing.aiwidget", "com.nothing.cardservice"}
for pkg, res in WIDGETS.items():
    day = load(res + "/values/colors.xml")
    night = load(res + "/values-night/colors.xml")
    names = []
    for n in sorted(set(day) | set(night)):
        if re.search(r"aod|cardview|frosted|ucrop|settings_card|sign_in|^abc_|^material_|^m3_|^mtrl_|^design_|^notification_|^ripple", n): continue
        if pkg not in WIDGET_ONLY and not re.search(r"widget|clock|card|lucent_fallback_", n): continue
        d, ni = resolve(n, [day]), resolve(n, [night, day])
        if d is None and ni is None: continue
        if re.search(r"bg|background|ring|track|surface|shape|panel|container|frame|cover", n): role = "WBG"
        elif ni is not None and 300 <= ni <= 700: role = "SURFACE"
        else: role = "WFG"
        names.append(n)
        TARGETS.setdefault(pkg, (res, {}))[1].setdefault(role, []).append(n)

rows = []
for pkg, (res, roles) in TARGETS.items():
    day = load(res + "/values/colors.xml")
    night = load(res + "/values-night/colors.xml")
    for role, names in roles.items():
        for n in names:
            d = resolve(n, [day])
            ni = resolve(n, [night, day])
            if d is None and ni is None:
                print("пропуск:", pkg, n, file=sys.stderr); continue
            rows.append((pkg, n, role, d if d is not None else -1, ni if ni is not None else -1))

rows.append(("com.android.systemui", "widget_state_on_color", "WON", 600, 200))
rows.append(("com.android.systemui", "widget_icon_state_on_color", "WON", 50, 800))

out = ['package dev.novacain.ntclocks.anim;', '',
       '/** Сгенерировано tools/gen_color_map.py: цвета Nothing, их роль и исходный тон neutral1 (днём, ночью; −1 — нет). */',
       'final class ColorMap {', '    private ColorMap() {', '    }', '',
       '    static final String[][] ROWS = {']
for pkg, n, role, d, ni in rows:
    out.append(f'            {{"{pkg}", "{n}", "{role}", "{d}", "{ni}"}},')
out += ['    };', '}', '']
open(sys.argv[1], 'w').write('\n'.join(out))
print(len(rows), "цветов")
