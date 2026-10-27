# Extras

- **KnoxThor3D/** - "Viewpoint on Android", a ZombieBuddy mod for Zomdroid (source in src/, built mod in mod/;
  released as `KnoxThor3D.zip`). It leaves Project Viewpoint out of a session on renderers that can't run it
  (it needs to be in the main menu's mod list too), fills in ZINK_ZFA's missing GL 4.5 functions
  (ZfaFunctions), reads Viewpoint's crosshair depth with a GPU copy instead of a stalling glReadPixels
  (DepthRead), and reads it only when the view moved. On ZINK_ZFA it shows each frame without waiting for
  the GPU to finish it (Present), and it writes Viewpoint's per-draw data into a persistently mapped ring so
  Zink doesn't split the GPU's passes for every upload (Stream). It holds the main menu at 30 fps. Switchable:
  the 3D view at a lower resolution rebuilt by Viewpoint's TAA (Scale, `KNOXTHOR3D_RENDER_SCALE`), the world's
  chunks left out of Viewpoint's translucent pass (Hash, `KNOXTHOR3D_HASH`), a sky, weather and night for
  Viewpoint's Vanilla pack (Sky, `KNOXTHOR3D_SKY`: clouds and their shadows, sun, moon, stars, Milky Way,
  shooting stars, rainbow, lightning, haze, night colour, vibrance, wet ground) and Viewpoint's frame
  generation (FrameGen, Knox Link's switch). On a PC none of it does anything. See
  [the Thor setup guide](../docs/THOR-SETUP.md).
