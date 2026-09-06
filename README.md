# SoldierMC

Team Fortress 2's Soldier in Minecraft 26.2 (Fabric).

## Requirements

- JDK 25
- A local Team Fortress 2 install

## Build

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

Jar lands in `build/libs/`. `build` also runs `checkServerSafe`, which byte-scans compiled common
classes and fails on any reference to `net/minecraft/client/` or `com/soldiermc/client/`.

## Assets

No TF2 asset is committed or shipped in the jar. `tools/extract-tf2-assets.ps1` pulls the model,
textures and audio out of a local install into `tf2-assets-staging/`, from which the mod loads them
at runtime and builds a resource pack under `resourcepacks/` for the audio. Without that directory
the mod runs with vanilla rendering and no audio, and the tests that need assets skip.

## Layout

| Path | |
|---|---|
| `com.soldiermc.source` | Movement, weapon state machine, blast. Imports `java.*` only. |
| `com.soldiermc.bridge` | Engine to Minecraft: units, collision queries, the substep pump. |
| `com.soldiermc.studio` | MDL/VVD/VTX readers, animation decode, skinning. |
| `com.soldiermc.animstate` | `CMultiPlayerAnimState` port. |
| `com.soldiermc.client` | Rendering, HUD, input, audio. |
| `tools/` | Asset extraction, and a Python reader for the same model formats. |

Constants in `com.soldiermc.source` carry a `file.cpp:LINE` citation into the Source SDK 2013 tree.
Hammer units throughout that package; `Units` is the only conversion site. Source is z-up and
right-handed, and the map to Minecraft is a rotation (`mx = ex, my = ez, mz = -ey`) — the naive
axis swap is a reflection.
