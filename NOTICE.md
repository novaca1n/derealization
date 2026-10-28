# Third-party notices

derealization is licensed under the GNU General Public License v3.0 (see `LICENSE`). It includes or is derived from
the following works, under their own licenses:

## Code

| Work | Author | License | Where |
|---|---|---|---|
| Box2D-lite | Erin Catto | zlib | `app/.../anim/Physics.java` (ported to Java) |
| Clock styles of crDroid Android | crDroid Android contributors | Apache-2.0 | `faces/src/main/res/layout/crd_*`, `faces/.../crd/` (ported) |
| Lottie for Android | Airbnb | Apache-2.0 | dependency of `faces` |
| AndroidX, Jetpack Compose | The Android Open Source Project | Apache-2.0 | dependencies |
| ML Kit subject segmentation | Google | [ML Kit Terms](https://developers.google.com/ml-kit/terms) | custom depth mask |
| Xposed API | rovo89 | Apache-2.0 | compile-only |

`sysui/libs/systemui-clock-api.jar` holds compile-only signatures (no code) of the SystemUI clock plugin API — the
AOSP API (Apache-2.0) as extended in Nothing OS — so the clock plugin can be compiled against it. It is not packaged
into the app.

## Fonts

All bundled fonts are openly licensed:

| Font | Author | License |
|---|---|---|
| Geist | Vercel / The Geist Project Authors | SIL OFL 1.1 (`OFL.txt`) |
| Inter | Rasmus Andersson | SIL OFL 1.1 (`app/src/main/assets/fonts/OFL-inter.txt`) |
| Outfit | Rodrigo Fuenzalida | SIL OFL 1.1 (`app/src/main/assets/fonts/OFL-outfit.txt`) |
| Bebas Neue | Ryoichi Tsunekawa | SIL OFL 1.1 |
| Encode Sans | Impallari Type | SIL OFL 1.1 |
| Montserrat | Julieta Ulanovsky | SIL OFL 1.1 |
| Playfair Display | Claus Eggers Sørensen | SIL OFL 1.1 |
| Electroharmonix | Ray Larabie (Typodermic) | CC0 1.0 |
| Brighton Sans SC | Nate Halley (FontStruct) | CC BY-SA |

Some crDroid clock styles were designed for fonts that can't be redistributed (OEM and commercial typefaces) and four
use third-party artwork. Public builds show those styles with an open font of a similar character and a plain
gradient instead (`faces/src/oss-res`).

Nothing, Nothing OS, CMF and their fonts are trademarks or property of Nothing Technology Limited. derealization is an
independent project, not affiliated with or endorsed by Nothing.
