# Project Zomboid on the AYN Thor, with Knox Link on the bottom screen

This is how to run Project Zomboid (Build 42) natively on a dual-screen Android handheld such as the
**AYN Thor** (Snapdragon 8 Gen 2), with Knox Link on the second screen, and optionally the 3D first/third
person view of Project Viewpoint. It's the setup [@cadeambrose](https://github.com/cadeambrose) made Knox Link
on and tests it with, on their own AYN Thor Max (Android 13, Project Zomboid 42.21, Zomdroid 1.5.2).

> You need your own copy of Project Zomboid on Steam. Nothing here shares game files: you download the
> game's Linux files from your own Steam account.

---

## 1. The game on the handheld (Zomdroid)

1. Install **Zomdroid** 1.5.2 or newer (release APK from the `udarmolota/zomdroid` GitHub page). Its notes
   list the Build 42 versions it supports.
2. Download the game's Linux files with Steam on your PC: open the Steam console (`steam://open/console`)
   and run `download_depot 108600 108603`. Steam saves them under
   `Steam\steamapps\content\app_108600\depot_108603\`.
3. Zip the `projectzomboid` folder, copy the zip to the handheld, and import it in Zomdroid.
4. In Zomdroid's instance settings:
   - **Renderer: MOBILEGLUES** for the game's normal top-down view: about 90 fps, cool, and other apps on
     the bottom screen draw cleanly next to it.
   - Resolution around 60 %, Java heap 4 GB.

## 2. Mods (Zomdroid > Import Mods)

| Mod | Why |
| --- | --- |
| **ZombieBuddy** (Zomdroid > Optimization > ZombieBuddy) | lets mods run Java; Knox Link's server needs it |
| **Knox Link - Second Screen** and **Knox Link Server** (release zip) | the second screen |
| *optional* **PZ Optimization** | cooler and faster everyday view (about 59 °C instead of 67 °C at 90 fps) |

Import a mod as a zip of its folder, then turn it on for your save in Zomdroid's **Mods** screen and press
**Sort**. Build 42 mods that only have a `42.x` folder may show as "not for this game build": copying their
`mod.info` into a `42/` folder next to it fixes that.

## 3. Knox Link on the bottom screen

1. Install `KnoxLink.apk` from the release.
2. Open Knox Link once on the bottom screen. In **Settings > Dual screen**, allow:
   - **usage access** and **display over other apps**: Knox Link then opens on the bottom screen by itself
     when the game starts, and closes when the game closes;
   - **"Let Knox Link stay ready"**: Android otherwise stops it while the game loads (it needs a lot of
     memory) and won't let it start again on its own.
3. Start a save. Knox Link connects by itself (the game's server starts with a save, not at the main menu).

Good to know:

- Tapping Knox Link never takes the controls from the game. For notes and chat it uses its own keyboard
  (the system keyboard would take the controls, and with the 3D renderer it shows streaks).
- The launcher's **"Clear all"** force-stops every app, Knox Link's game watcher too. Open Knox Link once and
  it's ready again; or start the game from Knox Link's own icon, which opens both screens.
- To close Knox Link: press **Close** on its waiting screen or in Settings, or Back twice on the map; it
  also closes with the game.
- **Shortcut keys** for handheld buttons: game Options > Mods > Knox Link (first/third person, bandage, eat,
  drink, light, best weapon). Map spare buttons to those keys with the handheld's key mapper.

## 4. Optional: the 3D view (Project Viewpoint)

Project Viewpoint's renderer needs desktop OpenGL 4.5, which only some of Zomdroid's renderers provide.

1. Import **Viewpoint**, **Viewpoint Texture Fix**, **Viewpoint Stability Fix**, and **Viewpoint on Android**
   (`KnoxThor3D`, from the Knox Link release). Turn Viewpoint on Android on in **both** the main-menu mod list
   and your save's. On renderers that can't run Viewpoint it leaves Viewpoint out for that session, so the
   renderer setting is the only switch between the normal view and 3D. It also fills in the GL 4.5
   functions ZINK_ZFA lacks, and makes Viewpoint's crosshair test much cheaper.
2. For **furniture that looks right** (the plain view draws some cabinets black or see-through), import
   **6277 3D models for Viewpoint [sour_kisel]** (Workshop 3810302175, about 430 MB).
3. In Zomdroid set **Renderer: ZINK_ZFA** (Vulkan driver TURNIP_740). For the normal view, switch back to
   MOBILEGLUES.
4. Faster and sharper (the author's Thor since 2026-10-27; experimental, see below how to go back):
   - **Vulkan driver: DD-Turnip v0.3.4** (Droid-Deck/Drivers on GitHub, Mesa 27.0-devel). Take
     `android/libvulkan_freedreno.so` out of its zip, import it with Zomdroid > Import/Export Custom Driver,
     and pick **CUSTOM_DRIVER**. TURNIP_740 and the other Turnip builds before Mesa's fix of 2026-10-08
     (commit e984ef294eaf) turn the game's "is the GPU done yet?" checks into waits, so the CPU and the GPU
     take turns instead of working side by side.
   - **Environment variables** (instance settings > Advanced):
     `TU_DEBUG=sysmem KNOXTHOR3D_RENDER_SCALE=60 KNOXTHOR3D_HASH=2`. `TU_DEBUG=sysmem` draws straight to
     memory instead of in tiles (Viewpoint's passes are heavy on geometry, and tiling sorts all of it first).
     The other two are KnoxThor3D's (from this repository's `extras/KnoxThor3D`, newer than the 1.1.0
     download): the 3D view is drawn at 60 % and rebuilt to full size by Viewpoint's TAA+, and the world's
     chunks are left out of Viewpoint's translucent pass, which sent them all through the GPU a second time
     for their glass and soft edges (about 4 ms a frame). Window panes are then dithered into the main pass
     (they look a little grainy); `KNOXTHOR3D_HASH=1` drops the panes for clear glass, `0` is Viewpoint's own.
   - **Resolution 100 %** and Viewpoint's anti-aliasing on **TAA+** (both of the above need TAA).
   - Frame cap 60.

   Measured in a house, third person, playing: **60 fps held** standing and walking (lowest 41–50), where
   TURNIP_740 at 60 % resolution made 43–47. It draws about 9–10 W, so the fan runs high with the stock
   curve (see Heat and battery). To go back: Vulkan driver TURNIP_740, clear the environment variables,
   resolution 60 %.
5. Viewpoint settings that pay off on the Thor (its settings window, or Knox Link's modes):

| Setting | Value | Effect |
| --- | --- | --- |
| `lod.reliefEnabled` (terrain relief) | off | the biggest win: about 39 → 51 fps indoors |
| `lod.packRing` (3D furniture reach) | 2 | furniture models in the rooms around you; 3 costs about 7 fps more |
| `memory.packMiB` | 384 | room for the furniture art |
| `lod.shellFullBlocks`, `lod.shellLiteBlocks` | 0 | far buildings drawn the cheap way |
| `lod.farBlocks` | 2 | |
| `graphics.preset` | Custom | otherwise Viewpoint puts its preset back at start |

With these, the 3D view runs at about 40–50 fps in first/third person at 60–75 °C.

## 5. Heat and battery

- **Knox Link > Settings > Game**: **Cool** (30 fps), **Balanced** (60) or **Smooth** (90), and
  **"Cool down automatically when hot"**: the frame cap steps down while the chip stays above 88 °C and back
  up once it's below 76 °C.
- **"Rest while paused"** (on by default): a few seconds into a pause the game draws 30 frames a second instead
  of up to 90, and goes back the moment you unpause. Measured in the 3D view: 6.2 W → 4.5 W while paused.
- What the power goes into, measured on the Thor in the 3D view (USB power with a full battery): the frame rate
  is the big lever (about 0.08 W per frame per second). A cap above what the game reaches (90 when the 3D view
  makes ~50) costs nothing extra. Knox Link's app uses 2–5 % of one CPU core.
- The Thor's **High Performance** mode gave the most frames for its power in 3D: 51 fps, against 48 in
  Performance and 38 in Standard (which saved about half a watt).
- The fan: the curve in the handheld's settings decides how loud it gets. A steeper one (the author's: 100 %
  at 85 °C) keeps the chip cooler; a gentler one is quieter and lets the chip run warmer, which it is made for
  (it slows itself down only near 95 °C).

## Troubleshooting

| Problem | Fix |
| --- | --- |
| Knox Link says "Waiting for a save…" | The game is at its menu; its server starts with a save. |
| Knox Link didn't open with the game | "Clear all" stopped it: open it once. Check the three permissions in Settings > Dual screen. |
| Item pictures missing | Update Knox Link (old versions gave up when they started before the save). |
| Streaks on the bottom screen in 3D | Other apps drawn by the GPU streak next to ZINK_ZFA; Knox Link draws on the CPU and stays clean. |
| The game won't start with Viewpoint on MOBILEGLUES | Install Viewpoint on Android (KnoxThor3D), which leaves Viewpoint out on that renderer. |
