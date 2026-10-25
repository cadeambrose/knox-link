# Extras

- **KnoxThor3D/** - "Viewpoint on Android", a ZombieBuddy mod for Zomdroid (source in src/, built mod in mod/;
  released as `KnoxThor3D.zip`). It leaves Project Viewpoint out of a session on renderers that can't run it
  (it needs to be in the main menu's mod list too), fills in ZINK_ZFA's missing GL 4.5 functions
  (ZfaFunctions), reads Viewpoint's crosshair depth with a GPU copy instead of a stalling glReadPixels
  (DepthRead), and reads it only when the view moved. See [the Thor setup guide](../docs/THOR-SETUP.md).
