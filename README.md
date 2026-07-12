# All Under Heaven

A [NeoForge](https://neoforged.net/) content mod for **Minecraft Java 26.2**, structured to grow into many features.

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
