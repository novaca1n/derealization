# derealization

A system patcher for **Nothing OS 4.1** on the **CMF Phone 2 Pro** (`galaga`). It injects into SystemUI, the Nothing
launcher, Wallpaper & style and Settings through [Vector](https://github.com/JingMatrix/Vector) (LSPosed), so nothing
on the system partition is modified — patching and unpatching is one tap and fully reversible.

All settings live where Nothing's own are: **Wallpaper & style → derealization**.

## What it does

**Lock screen**
- Own clocks: dot matrix, stretched digits, analog, iOS / ColorOS / OriginOS styles and 67 styles ported from crDroid,
  with live preview, colors, fonts and Always-on behaviour
- Depth effect for any wallpaper, with a custom subject mask (on-device ML + brush)
- Widgets in Material 3 Expressive: CMF earbuds, steps from the watch, music
- iOS-style unlock: the clock slides up and fades as you swipe

**Home screen**
- Dock and widget glass, resize app icons like widgets (2×1 … 2×2), resize by dragging a corner
- Living desktop: cascades after unlock, ripple when an app closes, gravity mode (shake the phone)
- Recents carousel with depth

**Motion and system UI**
- ColorOS 17–style motion system-wide: app launch/close springs, folders, app drawer, rubber-band shade
- iPhone-style heads-up notifications and volume panel, rubber volume slider
- Glass notification shade and app drawer, split shade (quick settings / notifications, like Nothing OS 5)
- Color engine: recolors system UI, launcher and Nothing apps from your palette
- "About phone" card

**TweakStore** — patching also installs [TweakStore](https://github.com/novaca1n/tweakstore), where extra tweaks live
(so derealization itself stays lean, and anyone can publish tweaks in their own source).

## Requirements

- CMF Phone 2 Pro with Nothing OS 4.1 (tested on build `B4.1-260812`; newer builds usually work — the patcher tells
  you if Nothing updated something it hooks)
- Root with [Magisk](https://github.com/topjohnwu/Magisk) (Zygisk enabled)
- [Vector](https://github.com/JingMatrix/Vector)

## Install

1. Download `derealization-<version>.apk` from [Releases](https://github.com/novaca1n/derealization/releases) and
   install it.
2. Open derealization, allow root, tap **Patch**. SystemUI restarts at the end (the phone locks for a moment).
3. Open **Wallpaper & style → derealization** to set things up.

**Safe mode.** If a patched app crashes three times in a row, derealization stops loading into it until you patch
again — your phone keeps working. **Unpatch** in the menu turns everything off.

## Build

JDK 21, Android SDK 37:

```sh
./tools/bundle-tweakstore.sh   # puts tweakstore.apk into assets (builds ../tweakstore or downloads the release)
./build.sh assembleRelease
```

Release signing reads `../keys/keystore.properties` (or the file in `$RELEASE_KEYS`); without it the release is signed
with the debug key.

## License

GPL-3.0 — see [LICENSE](LICENSE). Third-party code and fonts: [NOTICE.md](NOTICE.md).

derealization is an independent project and is not affiliated with Nothing Technology Limited.
