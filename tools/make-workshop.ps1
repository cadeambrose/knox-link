# Stages Knox Link for the Steam Workshop in %UserProfile%\Zomboid\Workshop\KnoxLink: the two mods from dist\,
# the preview and workshop.txt. Upload it from the game's main menu (Workshop > Create and update items).
# Run .\build.ps1 and .\package.ps1 first. -Visibility: public, friendsOnly, private or unlisted.
param([string]$Visibility = 'private')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $env:USERPROFILE 'Zomboid\Workshop\KnoxLink'
New-Item -ItemType Directory -Force "$stage\Contents\mods" | Out-Null
foreach ($m in 'KnoxLink', 'KnoxLinkServer') {
    $dst = "$stage\Contents\mods\$m"
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item (Join-Path $root "dist\$m") $dst -Recurse
}
& "$PSScriptRoot\make-workshop-preview.ps1" -Out "$stage\preview.png" | Out-Null

$description = @'
[h1]A second screen for Project Zomboid[/h1]
Your map, your body, your bag, the radio, your base and your journal, live on a phone, a tablet, a second monitor or the bottom screen of a dual-screen handheld, while you play. Made and tested on an [b]AYN Thor Max[/b], with the game running on the handheld itself.

[h2]What's on the second screen[/h2]
[list]
[*][b]Map[/b]: the game's world map in its paper style, following you. Waypoints, symbols and notes on the same map the game saves, fog of war, your base, teammates as the server allows. Zoom all the way in for a [b]floor plan[/b] of what your character has seen (walls, doors, windows, furniture, cars, the zombies they noticed), made for first person.
[*][b]Where did I see...?[/b] Knox Link remembers every container your character looked in. Search "hammer" or tap Medicine: nearest first, in which container, room and town, and how long ago.
[*][b]Vitals[/b]: every wound in the game's own words, and every treatment its health panel offers there (disinfect, bandage, stitch, remove glass or a bullet, splint...), only with what you actually have. Moodles, quick actions, and your character live in 3D.
[*][b]Gear[/b]: in your hands, wearing, carrying, one section per bag. [b]Move items[/b] in batches to any bag, your key ring, a container around you or the floor. The game's own right-click menu for any item, every body within a few steps with its loot, and the things around you (doors, windows, curtains, light switches) with their own menus.
[*][b]Weapons[/b]: your loadout, like a shooter's. Open a gun for the [b]gunsmith[/b]: its slots around it, and every stat change shown before you attach a part (better in green, worse in red), done the game's own way. Melee weapons and throwables too.
[*][b]Skills, Radio[/b] (what you heard, the forecast, multiplayer chat), [b]Base[/b] (generators, rain collectors, crops), [b]Car[/b] (the game's dashboard with live needles).
[*][b]Journal[/b]: a diary written in your character's voice from their day. Free and offline; optionally by an AI service you pick, with your own key. Photos for a scrapbook (with immersion on you need one of the game's cameras).
[*][b]When you die[/b]: the journal becomes a diary on your body that your next survivor can find and read, and the second screen shows the run's recap.
[*][b]Ask[/b]: questions about the game, answered by the AI you set up, with your situation in mind.
[*][b]Danger radar[/b] of the zombies your character has seen or heard, and [b]alerts[/b] with a one-tap fix.
[/list]
It looks like the game: its own panels, buttons, tabs, colours and fonts, and its own art on every tab.

[h2]How to use it[/h2]
[olist]
[*]Subscribe to this and to [url=https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853]ZombieBuddy[/url] (required: it lets mods run Java).
[*]Enable [b]Knox Link - Second Screen[/b], [b]Knox Link Server (Java)[/b] and [b]ZombieBuddy[/b] for your save.
[*]In the game, press [b]F8[/b]: the address, the PIN and a QR code. Open it in any browser on the same Wi-Fi, or install the Android app.
[/olist]
The Android app, the source code and a guide for running the game on an AYN Thor (with Project Viewpoint's 3D view): [url=https://github.com/cadeambrose/knox-link]github.com/cadeambrose/knox-link[/url]

[h2]Good to know[/h2]
[list]
[*]It listens on your local network only, never the internet, and other devices need the PIN.
[*]Immersion is on by default: the second screen only knows what your character knows (no watch, no clock; needs as moodles). Turn it off in the mod options.
[*]Light on the game: it only sends what changed; on a handheld the app uses a few percent of one CPU core.
[*]With [url=https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528]Project Viewpoint[/url]: first/third person from the app, dead bodies in 3D, and a pause that lets a handheld cool down.
[*]Build 42.21. The Java part is open source with a reproducible build. Multiplayer is supported but has had little testing so far: please report how it goes.
[/list]
Not affiliated with The Indie Stone. Knox Link reads the game's artwork from your own copy of the game; it ships no game files.
'@
$lines = @('version=1', 'title=Knox Link - Second Screen')
foreach ($l in ($description -split "`r?`n")) { $lines += "description=$l" }
$lines += 'tags=Build 42;Interface;QoL;Multiplayer'
$lines += "visibility=$Visibility"
[IO.File]::WriteAllLines("$stage\workshop.txt", [string[]]$lines, (New-Object Text.UTF8Encoding $false))
"staged $stage ($Visibility)"
Get-ChildItem $stage -Recurse -Depth 2 | Select-Object -First 12 | ForEach-Object { '  ' + $_.FullName.Substring($stage.Length + 1) }
