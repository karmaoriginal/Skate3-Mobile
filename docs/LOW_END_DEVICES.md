# Low-end Android devices (Honor X5 Plus and class)

This guide covers running Skate 3 Mobile on entry-level phones, especially the
**Honor X5 Plus** (MediaTek Helio G36, PowerVR GE8320, 4 GB RAM, 720x1612).
The same profile also targets Helio G25/G35/P22/A22 devices and any Android
phone whose kernel reports under ~4.6 GB of RAM.

> **Nota rápida (Español):** en un Honor X5 Plus la app detecta el celular
> automáticamente y aplica el perfil **Ultra Low** (216p, LOD 0.35x, 30 FPS
> estables). En el juego, toca el botón ⚙ junto a HIDE para cambiar el
> tamaño y la opacidad de los botones, el diseño (Default / Compact / Big
> buttons / Mirrored) y activar **Edit layout** para arrastrar cada control
> donde te quede cómodo. Toda tu configuración se guarda sola.

## What the app does automatically

On Android, before any saved settings load, the app now picks one of three
device profiles from two signals:

| Signal | Source |
| --- | --- |
| Reported RAM (not marketed capacity) | `sysinfo()` |
| SoC identity | `/proc/cpuinfo` (`hardware` / `model name` / `processor` / `soc`) |

| Profile | Trigger | Scene target | Draw/LOD | Frame cap |
| --- | --- | --- | --- | --- |
| **0 — Performance** | RAM < 4.6 GB | 288p (512x288) | 0.50x | 60 |
| **1 — Quality** | 4.6 GB+, strong SoC | 720p (1280x720) | 1.00x | 60 |
| **2 — Ultra Low** | Entry SoC marker (Helio G36 etc.) **or** RAM < 3.3 GB | 216p (384x216) | 0.35x | **30 (locked)** |

A 4 GB phone never reports a full 4096 MiB through `sysinfo()` (the kernel
keeps part of it), so the Low and Ultra tiers key off a 4.6 GB ceiling rather
than marketed capacity. On the Honor X5 Plus specifically, `/proc/cpuinfo`
reports the MT6765-class MediaTek hardware and the PowerVR GE8320, which is
what selects profile 2 automatically.

The Ultra Low profile enables everything the Performance profile enables, plus:

- 216-line scene target (288x216 at 3:4, 384x216 at 16:9) — about 55% the
  pixels of the 288p Performance target.
- Draw distance and LOD distance at 0.35x.
- Guest static re-render every 16th frame instead of every 8th.
- Texture base mip 128 px / second mip 512 px (the Performance profile uses
  256/1024): the two largest mip levels of big textures are never uploaded
  or decoded. Roughly 16x less texture decode work for the largest assets.
- Texture cache budgeted at 192 MB soft / 288 MB hard / 16 MB render-to-texture.
- Guest FPS cap locked at 30. A steady capped 30 beats a 60 FPS cap that
  drops into the teens: motion stays even and the 90 Hz panel samples a
  regular frame stream. `show_fps_counter` stays on so you can see it.
- 4 ms prewarm budget (was 8 ms).

Shader shadows, SSAO, HDR, bloom, volumetric shafts, decals, lightmaps,
selection outlines, smooth camera and all other heavy post/material work stay
off, exactly as in Performance mode. Physics, player input, the board, menus
and the HUD remain full rate.

Saved settings always win over these defaults (they are applied first, then
your saved `settings.toml` is layered on top), so you can override any of it.

## How to check which profile applied

- The on-screen FPS counter is on in both low-end profiles; menu and log line
  say `Android device profile: Ultra Low / Honor X5 Plus class (384x216, ...)`.
- The in-game bug reporter (RB + Start > Report) prints
  `Ultra Low / Honor X5 Plus class` in its device summary.
- Or check `settings.toml` in the app files directory for
  `skate3_android_quality_profile = 0|1|2`.

## Forcing a profile

If auto-detection picks wrong on your device (for example a 4 GB phone with a
stronger SoC), copy `config/skate3.example.toml` next to your launcher settings
as `settings.toml` and set:

```toml
# 0 = Performance (288p), 1 = Quality (720p), 2 = Ultra Low (216p, 30 FPS)
skate3_android_quality_profile = 2
```

It takes effect on the next app start. Set it back to `1` if you ever move
to a faster phone.

Fine-tuning knobs you can also set there (see the comments in
`config/skate3.example.toml`):

```toml
skate3_draw_distance_scale = 0.30   # even shorter draw distance
skate3_lod_distance_scale = 0.30
skate3_guest_fps_cap = 30           # or 60 if your device holds it
skate3_native_render_scene_tex_base_mip_px = 128
skate3_native_render_scene_tex_base_mip2_px = 512
```

## Reducing texture memory (mods and loose assets)

The retail game textures are inside the Xbox 360 archives inside your ISO, so
no script can rewrite them safely. What a script **can** do — and what this
repo ships — is optimize the *loose* images that ship with the APK and with
your mods, and audit folders of unpacked assets:

```sh
# Report only (nothing is written)
python3 android/tools/optimize_textures.py android/app/src/main/assets/mods

# Apply: cap the longest side at 1024 px and rewrite in place
python3 android/tools/optimize_textures.py android/app/src/main/assets/mods \
    --in-place --max-size 1024 --report out/texture-manifest.json

# Aggressive pass for a 512 px budget, powers of two, separate output tree
python3 android/tools/optimize_textures.py /path/to/loose-textures \
    --output /path/to/loose-textures-small --max-size 512 --pow2
```

- Requires Python 3.9+ and [Pillow](https://pypi.org/project/Pillow/)
  (`python3 -m pip install pillow`).
- Only downscales and recompresses; never upscales; never rewrites a file
  that would grow.
- Xbox 360 container formats (`.dds .gtf .gxt .xpr .ktx .bh` ...) are
  measured and reported but never modified — those bytes belong to your ISO.
- The bundled Seiyu Paradise Penguin texture already went through the tool:
  2048x2048 / 2.1 MB → 1024x1024 / 574 KB in the APK.

For the in-game renderer, texture memory is managed by the Ultra Low profile
itself (mip drop + smaller cache budgets), so no manual texture surgery is
needed to play.

## Touch controls: comfortable and fully custom

Tap the **⚙ gear pill** next to the HIDE/TOUCH pill (top center) during gameplay.
While the panel is open the game never receives stray inputs. Tap a row to
cycle its value; everything is saved automatically.

| Row | What it does |
| --- | --- |
| **Layout** | `Default` → `Compact` (controls pulled toward the bottom corners, smaller) → `Big buttons` (+22% size, larger spacing) → `Mirrored` (left-handed: face buttons and D-pad on the left, sticks on the right) |
| **Button size** | S (0.80x) / M (1.0x) / L (1.25x) — scales sticks, buttons, shoulders and the D-pad |
| **Opacity** | Low (55%) / Medium (80%) / High (100%) of the default fill alpha |
| **Haptics** | Light vibration on every button press (Android keyboard-tap haptic) |
| **Edit layout** | Tap **Edit**: every control gets an orange ring. Drag any control with a finger to reposition it (saved automatically as a fraction of the screen, so it survives rotation and resolution changes). Tap **Done** to finish; *the game never receives inputs in this mode* |
| **Reset positions** | Clears all manual control positions back to the preset defaults |

Custom positions survive app restarts and are stored per control. Changing
**Button size**, **Opacity**, **Layout** or **Haptics** never discards your
custom positions; **Reset positions** does.

All placement math is resolution-independent (it derives from the display
size), and the four presets were verified to keep every control fully on
screen at 1612x720.

## Tips to reach a stable 30 FPS on the Honor X5 Plus

1. Let the Ultra Low profile engage (it is automatic). Do not force
   `skate3_android_quality_profile = 1`.
2. Close other apps before playing; 4 GB leaves little room and Android will
   otherwise page the game out of memory (stutter, not slowness).
3. In MagicOS, enable *Game Space* / performance mode and turn off battery
   saver for the app; the Honor X5 Plus throttles old-process background
   work aggressively.
4. Keep the phone cool and out of the sun; the Helio G36 loses clocks fast
   when hot.
5. Keep about 8 GiB free storage; a nearly-full eMMC dramatically slows
   level streaming.
6. If dense plazas still dip below 30, set `skate3_draw_distance_scale = 0.30`
   and `skate3_lod_distance_scale = 0.30` in `settings.toml` (see above).
7. FMV/boot screens are already rendered natively; don't be alarmed if the
   first boot is slower while pipelines compile — later boots are faster.

## Honest expectations

The Honor X5 Plus is far weaker than the previously verified handhelds. Menus
and skater gameplay are the target for a steady 30 FPS; very dense areas,
replays and the most particle-heavy scenes may dip. If you need more, this
class of device will never hold 60 — the locked 30 is the stable choice.
Physics, input and HUD always run full-rate; only world rendering is cut.
