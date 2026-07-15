# Terrain-adaptive villages, walls & roads — research + solution mindstorm

_All Under Heaven (NeoForge, Minecraft Java 26.2, Java 25). Research only — no code changed._

Problem: vanilla villages (and therefore our wrap roads, inter-village roads, and
tier-2 walls) generate straddling rivers/ponds and on broken/steep ground. Buildings
and paths clip into water; our walls and roads crossing water/ravines look wrong. We
want good-looking, terrain-adaptive generation that integrates walls + roads with the
random rivers and ponds.

---

## 0. What we have today (grounding — read from the repo)

The whole system is a single worldgen Feature at `top_layer_modification`
(`RoadFeature.place`), running on worldgen worker threads, fully seed-deterministic.
Nothing is stamped on the server thread; everything a chunk needs is a pure function
of the seed plus already-computed structure references for that chunk.

Key files and the exact hooks that matter for water/terrain:

- **`RoadPlanner.computeNode(cellX, cellZ)`** — decides a village exists in a grid cell
  purely from noise: `placement.getPotentialStructureChunk` → `getLocatePos` →
  `generator.getFirstOccupiedHeight(x,z,WORLD_SURFACE_WG,…)` → `getBiomeSource()
  .getNoiseBiome(...)` compared against `structure.biomes()`. **This is the single
  place where we could sample water and tag a village "wet" at plan time**, and it
  already runs the noise-only queries safely (no chunk loads).
- **`RoadPlanner.computePath(...)`** — already samples both heightmaps per road sample:
  `surface = getFirstOccupiedHeight(WORLD_SURFACE_WG)`, `floor =
  getFirstOccupiedHeight(OCEAN_FLOOR_WG)`, and sets **`wet[i] = surface - floor >= 2`**.
  So **we already detect water on roads** — we just currently only use it to swap the
  surface material to gravel and to lay a fill causeway; we never bridge.
- **`RoadBuilder.terrainHeight(serverLevel,x,z)`** — the uncarved noise surface
  (`generator.getFirstOccupiedHeight(x,z,WORLD_SURFACE_WG,…)`). Used everywhere as the
  "planned Y". Immune to trees/decoration, deterministic for ungenerated chunks.
- **`RoadBuilder.placeRoadColumn(...)`** — for each road block: clears 4 headroom above,
  and for `dy 1..2` below, **if the block is air or fluid it fills with `palette.fill()`
  (dirt/gravel/sandstone)**. Over water this makes a 3-wide **dirt causeway**, and
  `wet` swaps the top to gravel. There is no bridge branch. It never overwrites
  `DIRT_PATH`.
- **`WallBuilder.placeWallColumn(...)`** — `findGround` returns `null` on a fluid column,
  and a ravine is detected as `terrainHeight - ground.getY() > RAVINE_DROP (6)`. In both
  cases **the wall column is simply skipped** → the wall breaks cleanly at rivers/canyon
  rims (currently our only water behaviour for walls). `stampTowers` skips a tower whose
  center is wet/over-ravine.
- **`VillageContour.of(start, wrapMargin, tier, seed)`** — builds the concave wrap loop
  from building footprints (morphological close + dilate + Moore trace + Douglas-Peucker),
  then for tier-2 fills the loop polygon (`fillLoop`, 4-connected flood from the border)
  and dilates it into two wall courses. **`fillLoop` already gives us the exact "inside
  the walls" mask** — reusable to drain/fill the interior. `contains(x,z)` is the
  buildings+margin interior test. Tower spots come from `placeTowers`.
- Per-chunk order in `RoadBuilder.buildForChunk`: inter-village roads → wraps → tier-2
  stone streets → walls+towers. Gate cells are collected geometrically during road
  stamping (`collectGateCells`) into a per-chunk map, chunk-order independent.

The hard performance rule (from memory + code comments): **never call
`getChunk`/`startsForStructure` for far/ungenerated chunks from these events** (a
1,791-chunk cascade once). All the terrain queries we rely on
(`getFirstOccupiedHeight`, `getBaseHeight`, `getBaseColumn`, `getBiomeSource().
getNoiseBiome`, `getSeaLevel`) sample the generator's noise directly and **do not load
chunks** — they are safe and already in use. Local village geometry via
`structureManager.forWorldGenRegion(...).startsForStructure(eventChunkPos, …)` is the
only structure lookup we use and it is chunk-local.

---

## 1. Verified MC 26.2 API facts (from the decompiled 26.2 sources in the gradle cache)

Extracted from `mergeWithSources_*.jar` (`net/minecraft/world/level/levelgen/...`) and
the bundled vanilla data — authoritative for this exact build, not from memory.

### terrain_adaptation (`TerrainAdjustment.java`)
Enum, exactly five values, serialized names in quotes:
`NONE("none")`, `BURY("bury")`, `BEARD_THIN("beard_thin")`, `BEARD_BOX("beard_box")`,
`ENCAPSULATE("encapsulate")`. Field lives on `Structure.StructureSettings` as
`terrain_adaptation` (optional, default `none`). When it is anything but `none`, the
structure's bounding box is inflated by 12 (`Structure.adjustBoundingBox` →
`inflatedBy(12)`; `Beardifier.BEARD_KERNEL_RADIUS = 12`).

**What each actually does** (wiki + `Beardifier.java`): the "beard" is a
`DensityFunction` contribution added to the **solid-terrain density** field within a
24³ kernel (radius 12) around structure pieces.
- `beard_thin` / `beard_box`: **add** solid terrain *under* pieces and **remove** terrain
  *inside* the piece box (box is the stronger/blockier variant; ancient cities use box,
  villages + outposts use thin).
- `bury` / `encapsulate`: **add** terrain *around/over* pieces to bury them (strongholds
  / trial chambers).

**Critical for the water problem:** the beard modifies the density field *before* the
aquifer/sea fills water, so a beard column that becomes solid **displaces water there**
— that is exactly why a house over a pond gets a stone pad under it. But the beard
**only adds solid terrain under/around pieces within radius 12; it never lowers the
water table, never drains the gaps between pieces (streets, yards), and never removes an
existing water surface.** So terrain_adaptation alone cannot fix "streets dip into the
river / the pond still sits inside the village." Villages **already** use `beard_thin`
(see below), so there is little free upside here.

### Villages, as configured in 26.2 (bundled `village_plains.json`)
```json
{ "type":"minecraft:jigsaw", "biomes":"#minecraft:has_structure/village_plains",
  "max_distance_from_center":80, "project_start_to_heightmap":"WORLD_SURFACE_WG",
  "size":6, "start_height":{"absolute":0}, "start_pool":"…/town_centers",
  "step":"surface_structures", "terrain_adaptation":"beard_thin",
  "use_expansion_hack":true }
```
Plains `town_centers` pool elements use `"projection":"rigid"`. Houses/streets pools use
`"projection":"terrain_matching"` (a `GravityProcessor(WORLD_SURFACE_WG,-1)` that drops
each piece onto the surface). So a village is a **rigid flat center + terrain-following
limbs + a thin beard**. Because `project_start_to_heightmap` is `WORLD_SURFACE_WG`
(NOT_AIR predicate → **water counts as surface**), the start projects onto the *water
surface* over a river, which is a big reason villages sit half-in-water.

### JigsawStructure knobs (`JigsawStructure.java`)
`start_pool`, `start_jigsaw_name?`, `size (0..20)`, `start_height`,
`use_expansion_hack`, `project_start_to_heightmap? : Heightmap.Types`,
`max_distance_from_center` (default 80, horizontal ≤128 incl. the +12 beard),
`pool_aliases`, `dimension_padding`, and **`liquid_settings`** (default
`APPLY_WATERLOGGING`). `LiquidSettings` = `IGNORE_WATERLOGGING("ignore_waterlogging")` /
`APPLY_WATERLOGGING("apply_waterlogging")`. With `apply_waterlogging`, pieces placed in
water get waterlogged (why submerged houses fill with water); `ignore_waterlogging`
places them dry (air instead of water inside the piece volume).

### Pool projection (`StructureTemplatePool.Projection`)
`TERRAIN_MATCHING("terrain_matching")` (applies `GravityProcessor(WORLD_SURFACE_WG,-1)`)
and `RIGID("rigid")` (no gravity — anchored to the start plane).

### ChunkGenerator terrain queries (`ChunkGenerator.java`) — all noise-only, no chunk load
- `int getSeaLevel()` — sea level for the dimension.
- `int getBaseHeight(int x,int z, Heightmap.Types, LevelHeightAccessor, RandomState)` —
  raw noise-surface height for that heightmap; `getFirstOccupiedHeight = getBaseHeight-1`.
- `NoiseColumn getBaseColumn(int x,int z, LevelHeightAccessor, RandomState)` — the full
  pre-carve block column (lets you read the actual `BlockState`: water vs lava vs air).
- `Heightmap.Types` (predicates): `WORLD_SURFACE_WG` = NOT_AIR (**water is "surface"**),
  `OCEAN_FLOOR_WG` = MATERIAL_MOTION_BLOCKING (**solid floor under water**).

**Deterministic water probe at any (x,z), zero chunk loads:**
```
surfWG  = generator.getBaseHeight(x,z,WORLD_SURFACE_WG, level, randomState) - 1; // water top or land
floorWG = generator.getBaseHeight(x,z,OCEAN_FLOOR_WG,  level, randomState) - 1;  // solid floor
depth   = surfWG - floorWG;              // >0  ⇒ water (or non-solid) of this depth
belowSea= floorWG < generator.getSeaLevel();  // classify river/ocean vs perched pond
```
This is *identical in spirit to the `wet[]` test already in `computePath`*; it just
needs to be lifted into the planner (per-village footprint) and the wall/wrap passes.
Biome-based recognition is equally cheap and already wired: `getNoiseBiome(...)` +
`BiomeTags.IS_RIVER` / `IS_OCEAN` / `Biomes.RIVER` tells you "this column is river/ocean"
without any block sampling. Use the height probe for ponds (which have no biome) and the
biome tag for rivers/oceans (fast classification of the crossing type).

**Structure relocation is NOT possible by datapack.** Village existence is decided only
by `findValidGenerationPoint` → biome check; `random_spread` placement has no terrain
predicate. Nothing vanilla rejects an underwater candidate. Moving the *vanilla* village
off water requires a custom `StructurePlacement`/structure or a mixin (Tier D). We can
freely decide what *we* add, and we can terraform, but we cannot make Mojang's jigsaw
choose a drier chunk from a datapack.

---

## 2. Tier A — cheap terrain treatment (stamp-time, no placement change)

All of these live in the passes we already run, reuse masks we already compute, and add
only local generator/heightmap queries. Highest look-for-effort. They treat symptoms
(water inside the footprint, walls dead-ending at water) without changing where the
village is.

### A1. Drain/fill the walled interior (the single biggest visual win for tier-2)
Mechanism: we already have `VillageContour.fillLoop` → the exact "inside the walls" mask,
and `contains()` for tier-1 footprints. In a new pass (after wraps, before/with walls),
for each in-chunk cell of that mask: probe the live column; if the surface block is
fluid (or `terrainHeight` sits ≥2 above `OCEAN_FLOOR_WG`), **fill the water up to the
local land/platform level** with a courtyard material (grass/dirt for tier-1, packed
stone for tier-2), i.e. turn the pond inside the walls into dry ground level with the
village. Cap the fill height to sea level + a small delta so you don't build a visible
plateau; if the interior water is deeper than a threshold, prefer A2 (embankment) or a
cosmetic pool instead of a full fill.
- Effort: **low–medium** (one masked pass, water probe + column fill; reuse `fillLoop`).
- Impact: **high** — removes the "river running through the town square" look.
- Risks: over-filling a large lake looks like a scar; gate on interior-water fraction
  and depth. Deterministic (mask + per-column noise probe are seed-pure).
- Interaction: complements walls perfectly — the wall already rings this exact polygon.
  Do it before `WallBuilder` so wall foundations sit on the new fill, and before
  `stoneStreets` so streets tile over drained ground.

### A2. Embankment / revetment ring where the wrap or wall meets water
Mechanism: extend `WallBuilder.placeWallColumn`'s water branch. Instead of skipping a
wet wall column outright, drop a short **cobblestone revetment**: fill the fluid column
from `OCEAN_FLOOR_WG` up to shoreline height with stone, then place the normal wall on
top. Same idea for the wrap road's wet edge (a stone quay instead of a dirt causeway).
- Effort: **low** (change the two `return 0` water branches to a fill-then-build).
- Impact: **medium–high** — walls/roads meet the water on a tidy stone bank instead of
  stopping mid-air or making a dirt smear.
- Risks: for wide/deep water this becomes a giant dam (ugly + expensive). Gate by span:
  only revet when the wet run is short (see A3); otherwise fall through to a bridge/gate.

### A3. Bridge the road across water instead of causeway-filling it
Mechanism: `computePath` already flags `wet[]`. Detect **maximal runs of consecutive wet
samples** on a path (cheap, done once per edge at plan time) and classify: short run
(≤ ~6 blocks) → keep the causeway; longer run → mark it a **bridge span**. In
`placeRoadColumn`, a bridge cell places the deck at road grade (already the interpolated
`ys[i]`, kept ≤1 slope), plained **plank/stone-slab deck + fence rails + support posts**
down to the floor at the span ends, and **leaves the water below untouched** (no fill).
This is the conceptual mechanism of YUNG's Bridges/Roads (see §6), reimplemented.
- Effort: **medium** (span detection on the path; a bridge column variant in the
  stamper; posts need the floor height = `OCEAN_FLOOR_WG`, already sampled).
- Impact: **high** — river crossings read as intentional infrastructure.
- Risks: multi-chunk decks must be chunk-order independent — key each bridge cell off the
  path geometry (as gates already are), never off neighbouring placed blocks.

### A4. Foundation pillars / stilts under buildings & walls over water
Mechanism: generalize the wall foundation loop (`dy 1..2` cobblestone under a column) to
extend all the way down to `OCEAN_FLOOR_WG` when standing in water, so a wall/tower that
*must* cross water stands on visible piers rather than being skipped. Pair with A2 for a
solid bank or A3 for an arch between piers.
- Effort: **low** (deepen the existing foundation loop when fluid detected).
- Impact: **medium**.
- Risks: piers in very deep water are tall/expensive; cap and fall back to "break the
  wall + flank with towers" (current behaviour) beyond a depth limit.

### A5. Local platform / beard-in-code under the wrap and streets
Mechanism: where the wrap road or a tier-2 street sample sits over a 1–3 block dip or
puddle, raise a small stone pad to level it (a hand-rolled mini-beard, since the vanilla
beard doesn't cover our roads). We already carve 4 headroom for hillside cuts; this is
the inverse for small hollows.
- Effort: **low**. Impact: **low–medium** (smooths the "path stair-steps into a divot"
  look). Risks: minimal; keep the pad within a couple blocks so it doesn't terrace.

---

## 3. Tier B — structure-config / datapack tweaks on the villages structure

We already ship `data/minecraft/worldgen/structure_set/villages.json`. We can equally
ship overrides of `data/minecraft/worldgen/structure/village_*.json` (or apply a
**NeoForge structure modifier** — `Structure` is coremod-modifiable via
`modifiableStructureInfo`, so a `neoforge:...` structure-modifier datapack can change
settings without copying the whole vanilla JSON). But note the ceiling: **none of these
drain water.**

| Lever | Effect | Verdict for the water problem |
|---|---|---|
| `terrain_adaptation: beard_thin → beard_box` | Blockier solid pad under pieces; still radius-12, still only *adds* solid under pieces | Marginal. Slightly better pads over shallow water; does nothing for streets/ponds between pieces. Cheap to try. |
| `terrain_adaptation: … → bury/encapsulate` | Buries the village in terrain | Wrong for villages (you'd entomb them). Not recommended. |
| `project_start_to_heightmap: WORLD_SURFACE_WG → OCEAN_FLOOR_WG` | Anchors the start to the **solid floor** instead of the water surface | **Double-edged.** On water it sinks the village to the riverbed (worse — now it's underwater). On land the two are equal. Not a fix. |
| `liquid_settings: apply → ignore_waterlogging` | Pieces placed in water are **dry inside** (air, not water) | **Real, cheap symptom fix** for "submerged houses full of water" — interiors stay usable. Doesn't remove the surrounding water but stops the flooded-house look. Low risk. |
| `size` / `max_distance_from_center` smaller | Smaller village footprint | Statistically less chance of spanning a river, at the cost of shrinking every village everywhere. Blunt; not recommended as a water fix. |

- Effort: **very low** (JSON/modifier only, no code).
- Impact: **low** overall for water; `ignore_waterlogging` is the one worth shipping.
- Risks: changes *all* villages globally; interacts with our wrap/wall which read the
  resulting heightmap, so re-verify wrap hugging after any projection change.
- **Conclusion:** Tier B is a cheap complement, not a solution. Ship `ignore_waterlogging`
  if the flooded-interior look bothers us; otherwise the datapack surface is a dead end
  for water because the engine has no "drain/avoid water" structure option.

---

## 4. Tier C — adaptive recognition & reshaping (our stuff adapts, village stays put)

This is where the best effort/impact sits for a *deterministic worldgen* mod, because we
can't move the vanilla village but we fully control our wrap/wall/road and can terraform.

### C1. Water-aware wall: causeway / bridge-gate / water-gate instead of a blunt break
Mechanism: today a wet wall column is skipped. Instead, walk each wall course and detect
wet runs (same span logic as A3). Classify by span + biome:
- short pond crossing → **A2 revetment causeway** (wall continues on a stone bank);
- river crossing (biome `IS_RIVER`, moderate span) → **water-gate**: wall stops at the
  bank on stone piers, an arch/portcullis spans the river at wall height, towers flank
  both banks (reuse `placeTowers` bias — put a tower at each break end);
- wide/ocean → keep the clean break (current behaviour) but cap it with **bastion
  end-towers** so it reads deliberate.

Reuse: gate-arch geometry already exists (`placeGateColumn`); a water-gate is a taller
arch with piers to the floor. Span detection is a pass over `wallInner`/`wallOuter`
ordered by the loop; store per-village "wet runs" in `VillageContour` at build time
(deterministic, cached) so every chunk agrees.
- Effort: **medium–high**. Impact: **high** (walls at water become a feature, not a bug).
- Risks: keeping multi-chunk arches consistent — derive everything from cached geometry,
  never from placed neighbour blocks.

### C2. Moat / harbour as intentional design
Mechanism: when the walled polygon (`fillLoop`) is adjacent to a river/pond, *embrace* it:
route the wall to the water's edge and treat the water as a **moat** (add a stone-lined
bank, a drawbridge gate where a road crosses); or if water intrudes into the polygon,
carve a tidy rectangular **harbour basin** with a quay (stone-lined) instead of draining
(A1). Choose drain-vs-harbour by interior-water area: small → drain (A1), large → harbour.
- Effort: **high**. Impact: **high, "wow"**. Risks: needs careful masks to avoid ragged
  edges; defer until A1/C1 land.

### C3. Plan-time wetness tag → choose the treatment per village
Mechanism: in `RoadPlanner.computeNode`, after resolving the node, **sample a coarse grid
of the ~80-block village footprint** with the height probe (§1) and the biome tag, and
compute `wetFraction` + `nearestRiver`. Store it on `VillageNode`/`VillageContour`. Then:
- `wetFraction` tiny → today's behaviour;
- moderate → enable A1 drain + C1 water-gates;
- large (village basically in a lake) → **suppress the wall entirely** (a wall in a lake
  never looks good) and fall back to a wrap + bridges only, or downgrade the tier.
This is cheap (a few dozen noise probes per village, cached once) and makes the system
*choose* gracefully instead of applying walls blindly.
- Effort: **medium**. Impact: **high** (kills the worst-looking cases outright).
- Risks: must be deterministic + chunk-order independent — it is, because it's pure
  noise sampling keyed on the village start chunk, cached in the planner like everything
  else. Keep the probe grid coarse (e.g. 8-block spacing over the footprint) to stay cheap.

### C4. River-aware road routing (bias the meander away from long water crossings)
Mechanism: `computePath` currently midpoint-displaces blindly, then flags wet samples.
Add a cheap **penalty term**: when displacing, nudge control points away from columns
that probe as deep water / river-biome (sample a handful of candidate offsets, pick the
drier one). Keeps determinism (seeded RNG + deterministic probe). Roads then *prefer*
narrow crossings and dry ground, so bridges (A3) land at sensible narrow points.
- Effort: **medium**. Impact: **medium–high**. Risks: don't over-constrain or roads get
  wiggly; a light penalty + capped search is enough.

---

## 5. Tier D — fully dynamic, coherent site sculpting (largest effort, defer)

- **D1. Relocate/validate the vanilla village off deep water.** Only real way to stop the
  vanilla village itself from sitting in water: a custom `StructurePlacement` type (or a
  mixin on the village structure's `findValidGenerationPoint`) that rejects a candidate
  whose footprint probes as >X% deep water and re-rolls to the next grid candidate.
  Because our planner *already replicates* the existence decision from noise, we'd keep
  the two in lockstep. Big, invasive, risks desync with vanilla; highest fidelity.
- **D2. Whole-site terraform.** Treat village+wall+road+water as one composition: pick a
  deterministic "water design" per site (moat town / river town with bridges / harbour
  town), then sculpt banks, basins, dams, and bridges to match. This is D1 + C2 + A-tier
  combined into a site planner. Beautiful, expensive, and only worth it after the cheaper
  tiers prove the primitives (water probe, span detection, fill, bridge, revetment).
- Effort: **very high**. Impact: **very high**. Risk: performance + determinism surface
  area grows a lot. Explicitly a longer effort, as the user acknowledged.

---

## 6. Prior art surveyed (mechanism + license)

- **YUNG's Roads** (`YUNG-GANG/YUNGs-Roads`, **LGPL-3.0**) — the closest analogue:
  procedurally generated paths connecting villages, with dedicated bridge segments over
  water/gaps. Mechanism (conceptual, from its design): a POI/road graph between village
  points, path split into segments, water/gap segments swapped for bridge pieces rather
  than filled. Validates our A3/C4 direction. **License note:** LGPL is copyleft — safe
  to run as a dependency and to *reimplement the ideas* (techniques aren't copyrightable),
  but do **not** paste its source into this (currently All-Rights-Reserved) mod; a
  clean-room reimplementation of the mechanism is fine.
- **YUNG's Bridges** (`YUNG-GANG/YUNGs-Bridges`, **LGPL-3.0**) — first mod to add natural
  bridges; biome-variant bridge structures spanning ravines/water. Same licensing stance.
  Directly informs A3's bridge-deck-on-posts idea.
- **ChoiceTheorem's Overhauled Village (CTOV)** (**CC BY-NC-ND 4.0**) — 23 biome-themed
  village variants that "fit the landscape." Mechanism is **datapack-first**: it relies on
  vanilla `terrain_adaptation` + `terrain_matching` projection + carefully authored pool
  templates (stilts, built-in foundations, retaining walls baked into the pieces) rather
  than any runtime water detection. Lesson: much of "fits the terrain" is achieved by
  *template design*, not code. **License note:** NoDerivatives + NonCommercial — we can
  learn from the *approach* (ideas), but must **not** copy its templates/assets.
- **Towns & Towers** (mod/datapack) — 16 biome village variants + reworked outposts;
  again biome-themed jigsaw templates leaning on vanilla terrain adaptation, no special
  water-avoidance mechanism documented. Same "author the templates" lesson.
- **Tectonic / Terralith / William Wythers'** — worldgen (noise/biome) overhauls, not
  village logic; relevant only as a reminder that our probes must read *the active
  generator's* noise (we already do, via `serverLevel.getChunkSource().getGenerator()`),
  so we stay correct under these datapacks automatically.

Copyright/licensing bottom line: **techniques/algorithms are free to reimplement**;
**source code and assets are not.** Keep everything clean-room. The two LGPL YUNG mods are
the best references for bridges/roads; CTOV/T&T are references for "solve it in the
templates." Nothing here requires importing third-party code.

---

## 7. Recommendation — ordered roadmap (best look-for-effort first)

**Do first (foundational primitive):**
0. **Lift the water probe into a shared helper** and cache a per-village `wetFraction` /
   wet-run analysis. Add `RoadBuilder.waterDepth(serverLevel,x,z)` returning
   `WORLD_SURFACE_WG − OCEAN_FLOOR_WG` (and a `belowSea` flag from `getSeaLevel()`), and a
   `VillageContour` field for interior-water fraction + per-course wet runs, computed once
   in `VillageContour.of` from the same noise queries `computePath` already uses. Every
   later tier consumes this. Small, deterministic, unlocks everything.

**Then, in this order (each independently shippable):**
1. **A1 — drain/fill the walled interior** (reuse `fillLoop`; run before `WallBuilder`/
   `stoneStreets` in `RoadBuilder.buildForChunk`). Biggest single win for tier-2 towns.
2. **A2 — revetment banks** at wet wall/wrap columns (rewrite the two `return 0` water
   branches in `WallBuilder.placeWallColumn` and the causeway path in
   `RoadBuilder.placeRoadColumn`). Cheap, makes edges tidy.
3. **A3 — bridge long water spans on roads** (span detection on `RoadPath.wet[]` at plan
   time in `RoadPlanner.computePath`; bridge-column variant in `placeRoadColumn`, posts
   to `OCEAN_FLOOR_WG`). Turns river crossings into infrastructure.
4. **C3 — plan-time wetness tag drives the treatment** (in `RoadPlanner.computeNode` /
   `VillageContour.of`): suppress or downgrade walls for villages sitting in a lake; else
   enable A1+C1. Removes the worst cases entirely with little code.
5. **C1 — water-gates / bastion end-towers** where walls meet rivers (extend
   `placeGateColumn` into a pier+arch; bias `placeTowers` to flank breaks).

**Defer:** C2 moat/harbour, C4 river-aware routing (nice polish once primitives exist),
and all of Tier D (custom placement / full site sculpting) — acknowledged long effort.

**Ship-alongside (independent, code-free):** Tier B `liquid_settings: ignore_waterlogging`
via a structure override/NeoForge structure modifier if flooded interiors bother us. Do
**not** rely on `terrain_adaptation` changes for water — verified that the beard only adds
solid terrain under pieces and cannot drain rivers/ponds or the gaps between pieces.

### Specific files/functions to touch (this repo)
- `RoadBuilder.java`: new `waterDepth(...)`/`belowSea(...)` helpers next to
  `terrainHeight`; interior drain/fill pass in `buildForChunk` (before walls); bridge
  branch + deeper-foundation branch in `placeRoadColumn`; revetment in the causeway path.
- `WallBuilder.java`: `placeWallColumn` water/ravine branches → revetment / pier / clean
  break by span; `placeGateColumn` → add a water-gate variant; `stampTowers`/`placeTowers`
  → flank wall breaks with end-towers.
- `VillageContour.java`: compute + store `wetFraction` and per-course wet runs in `of(...)`
  (reuse `fillLoop` mask); optionally expose the interior mask for the drain pass.
- `RoadPlanner.java`: `computeNode` → attach `wetFraction`/`nearestRiver` to the node;
  `computePath` → wet-run spans for bridges and a light water penalty in `displace`.
- Data (optional, Tier B): a `village_*` structure override or
  `data/allunderheaven/neoforge/structure_modifier/*.json` setting `liquid_settings`.

### MC 26.2 APIs to call (all noise-only, no chunk loads)
`ChunkGenerator.getBaseHeight(x,z,WORLD_SURFACE_WG|OCEAN_FLOOR_WG,level,randomState)`,
`getSeaLevel()`, `getBaseColumn(...)` (only if exact block identity needed),
`getBiomeSource().getNoiseBiome(qx,qy,qz,randomState.sampler())` + `BiomeTags.IS_RIVER/
IS_OCEAN` — obtained via `serverLevel.getChunkSource().getGenerator()` and
`.randomState()`, exactly as `RoadPlanner`/`RoadBuilder` already do.

---

## 8. Citations
- Minecraft Wiki — Structure definition (terrain_adaptation, project_start_to_heightmap):
  https://minecraft.wiki/w/Structure_definition
- Verified against decompiled 26.2 sources in the local gradle cache:
  `TerrainAdjustment.java`, `Structure.java` (`StructureSettings`), `JigsawStructure.java`
  (`project_start_to_heightmap`, `liquid_settings`), `StructureTemplatePool.java`
  (`Projection`), `Beardifier.java` (`BEARD_KERNEL_RADIUS=12`), `ChunkGenerator.java`
  (`getSeaLevel`/`getBaseHeight`/`getBaseColumn`/`getFirstOccupiedHeight`), `Heightmap.java`
  (WG predicates), bundled `data/minecraft/worldgen/structure/village_plains.json` and
  `.../template_pool/village/plains/town_centers.json`.
- YUNG's Roads (LGPL-3.0): https://github.com/YUNG-GANG/YUNGs-Roads
- YUNG's Bridges (LGPL-3.0): https://github.com/YUNG-GANG/YUNGs-Bridges
- ChoiceTheorem's Overhauled Village (CC BY-NC-ND 4.0):
  https://github.com/ChoiceTheorem/ChoiceTheorem-s-overhauled-village ,
  https://modrinth.com/project/fgmhI8kH
- Towns & Towers: https://modrinth.com/mod/towns-and-towers
- ChunkGenerator API reference (NeoForge javadocs, older but signatures stable):
  https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/minecraft/world/level/chunk/ChunkGenerator.html
