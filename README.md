# All Under Heaven

A [NeoForge](https://neoforged.net/) content mod for **Minecraft Java 26.2**, structured to grow into many features.

## Features

- **Villages Redesign** (`feature/villages/`) — villages generate ~4× closer
  (structure-set override), every village has a deterministic name shown on the
  action bar when entered, first discoveries are announced in chat, and players
  get a nearest-civilization briefing on login. With Xaero's Minimap/World Map
  installed (bundled in dev runs), discoveries come with a clickable waypoint
  that marks the village on the map.
- **Roads** (`feature/roads/`) — a seed-deterministic road network generated
  as part of worldgen itself (a feature at the `top_layer_modification` step,
  so roads appear with the terrain, off the server thread, exactly like
  villages do). A concave wrap road hugs each village's buildings (a
  configurable distance off the walls — `roadWrapMarginBlocks`, default 4:
  building footprints are morphologically closed, margin-dilated
  and boundary-traced into a straightened loop); stretches where a vanilla
  street already runs are left to the street, so wrap and streets merge into
  one network. Villages link to each other with meandering 3-wide shovel-path
  roads (max slope 1 block, biome-flavored materials, lamp posts every 10–20
  blocks) that re-anchor onto the village's natural outer street ends on
  arrival. The network stays sparse: every
  village gets its nearest-neighbor road, extra links only between mutual
  k-nearest neighbors (`maxRoadsPerVillage`), further pruned by the
  triangle-bias rule (skip A–C when |AB|+|BC| < |AC|+s, s configurable).
- **City Tiers & Walls** (`feature/villages/VillageTier`, `feature/roads/WallBuilder`)
  — every village rolls a deterministic tier from the world seed: 5 in 10 stay
  plain villages (tier 1), 4 in 10 are walled towns (tier 2), 1 in 10 is
  reserved as a future city (tier 3, stub — currently unmodified). Tier-2
  towns get stone streets (their vanilla dirt paths, the wrap road and the
  incoming connectors all switch to a worn stone-brick/cobble/andesite mix)
  and a medieval city wall: two courses thick, ~2 blocks outside the wrap
  road, 4 blocks tall on a cobblestone plinth with a chiseled cornice and
  alternating merlons on the outer face. Where the wall meets water it
  **crosses on a cobblestone causeway** — the footing sinks to the bed and the
  body rides the water surface — so a town straddling a river or ponds keeps an
  unbroken ring; only dry ravine rims still break the wall (roads crossing a
  ravine keep their grade and bridge across). Where a road crosses the wall
  line a **constant gate arch** is cut instead: each crossing is resolved to a
  single anchor (the point on the wall line nearest the road centerline, plus
  the road's travel direction and surface height), and a rigid 5-wide passage —
  arcing from 3 high at the jambs to 5 at the crown, with a merloned gatehouse
  lintel over it — is stamped relative to the road surface, identical no matter
  which chunk generates which half. Each town also raises 1–3 **guard towers**
  (seed-rolled, evenly spaced along the wall, kept clear of gates): rigid
  diameter-7 cylinders stamped off a single base height (interiors force-cleared,
  gaps below force-filled, so terrain never shifts the structure), 6 high with a
  merlon crown and a **ladder** climbing the interior face of the town-facing
  doorway up to the parapet. Towers over water build on a cobble pier; tower
  centers over a ravine are skipped. Roads cutting through a hillside carve a
  4-block-tall opening.

> NeoForge for 26.2 is currently a **beta** line (`26.2.0.x-beta`). Expect occasional breaking
> changes until it stabilizes — see [Updating versions](#updating-versions).

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 25 (LTS) | Minecraft 26.2 ships on Java 25. A portable Temurin JDK lives at `C:\Users\Nez\.jdks\jdk-25.0.3+9` and `JAVA_HOME` points to it. Gradle also auto-provisions a matching JDK via the Foojay resolver if needed. |
| Gradle | 9.2.1 | Bundled — always use `gradlew`, never a system Gradle. |
| IDE | VS Code or IntelliJ IDEA | VS Code: install the **Extension Pack for Java**. IntelliJ: just open the folder, it syncs Gradle automatically (the **Minecraft Development** plugin is a nice extra). |

No Minecraft launcher installation is required — the toolchain downloads the game for development runs.

## Quick start

```powershell
# from the project root
.\gradlew build          # compile + package -> build/libs/allunderheaven-0.1.0.jar
.\gradlew runClient      # launch a dev Minecraft client with the mod loaded
```

First invocation downloads the toolchain and decompiles Minecraft — it takes several minutes and ~2 GB of cache. Subsequent runs are fast.

## Gradle tasks

| Task | Purpose |
|------|---------|
| `.\gradlew build` | Full build; jar lands in `build/libs/` |
| `.\gradlew runClient` | Start the dev client |
| `.\gradlew runServer` | Start a dev dedicated server (`--nogui`) |
| `.\gradlew runData` | Run data generators → writes JSON into `src/generated/resources` |
| `.\gradlew runGameTestServer` | Boot a headless server, run registered gametests, exit |
| `.\gradlew --stop` | Stop lingering Gradle daemons |

## Project layout

```
src/main/java/dev/nez/allunderheaven/
├── AllUnderHeaven.java      ← entrypoint; wires registries + config, nothing else
├── Config.java              ← common config (ModConfigSpec)
├── client/
│   └── AllUnderHeavenClient.java  ← client-only entrypoint (config screen, client setup)
├── registry/                ← ALL game objects are declared here
│   ├── ModBlocks.java
│   ├── ModItems.java
│   └── ModCreativeTabs.java
├── feature/                 ← one subpackage per gameplay feature
│   └── greeting/            ← example: event-driven, config-gated feature
└── datagen/                 ← data generators (models, recipes, loot, tags)

src/main/resources/
├── assets/allunderheaven/lang/en_us.json   ← translations (hand-written)
└── ...                      ← textures go in assets/allunderheaven/textures/

src/generated/resources/     ← OUTPUT of `runData` — commit it, don't hand-edit it
src/main/templates/          ← neoforge.mods.toml (placeholders filled from gradle.properties)
```

## Adding a new feature

1. **Content** (items/blocks): declare in `registry/ModItems` / `ModBlocks`, add to the tab in
   `ModCreativeTabs`, name it in `en_us.json`.
2. **Behavior**: create `feature/<name>/` with an `@EventBusSubscriber` class or custom
   item/block classes (see `feature/greeting/` for the pattern).
3. **Data**: register models/recipes/loot/tags in the matching `datagen/` provider, then run
   `.\gradlew runData` and commit what appears in `src/generated/resources`.
4. **Texture**: drop a 16×16 PNG at `assets/allunderheaven/textures/item/<id>.png` (or
   `textures/block/<id>.png`).
5. Optional: gate it behind a new option in `Config.java` (+ translation key
   `allunderheaven.configuration.<option>`).

## Updating versions

All versions live in [`gradle.properties`](gradle.properties):

- `neo_version` — pick from <https://projects.neoforged.net/neoforged/neoforge>
- `minecraft_version` / `minecraft_version_range` — must agree with the NeoForge version
- ModDevGradle plugin version — in [`build.gradle`](build.gradle) (`net.neoforged.moddev`)

After bumping, re-sync the IDE and run `.\gradlew build`.

## Useful references

- NeoForge docs: <https://docs.neoforged.net/>
- NeoForge version index: <https://projects.neoforged.net/neoforged/neoforge>
- ModDevGradle: <https://github.com/neoforged/ModDevGradle>
- Mojang version manifest (Java requirements per MC version): <https://piston-meta.mojang.com/mc/game/version_manifest_v2.json>

## License

All Rights Reserved (template default) — change `mod_license` in `gradle.properties` and this
section if you want something permissive; <https://choosealicense.com/> helps.
