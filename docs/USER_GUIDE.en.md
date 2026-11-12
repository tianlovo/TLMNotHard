# 0.1.0 usage, configuration and updates

[简体中文](USER_GUIDE.md) | English

This guide describes `0.1.0` gameplay and updates. The stable release is being prepared;
the current public download remains `0.1.0-beta.2`.
See [Development progress](../PROGRESS.md) for evidence, preserved failures and final publication records.

## Installation and updates

Use the complete GTNH 2.9.0 client and dedicated server. The game runs on Java 25; mod bytecode targets Java 8.

1. Download the target main JAR and verify it against `SHA256SUMS.txt` from the same GitHub Release.
2. Stop the client and server normally and wait for world saving to finish.
3. Back up the entire world, both `config/` directories, old JARs and update records.
4. Put the identical new main JAR in both `mods/` directories and remove the old TLM JAR.
5. Verify matching digests before starting. `tlmnhdevtools` is a separate development addon.

The [distribution guide](../scripts/distribution/README.md) covers the local verification bundle's `update.py`,
Rich menu, backups and rollback. It manages registered TLM files and preserves conflicts with unknown or altered JARs.

## Acquisition and basic controls

Current progression starts with a reusable empty soul frame, charged with a GT baked cake in an alloy smelter.
The core route is available in the steam era; the shrine requires an LV assembler.
Use the current NEI recipes for ingredients, processing time and energy.

Using a core on a suitable block top first binds its credential to the player, then attempts to release a maid.
An obstructed attempt retains the bound slab for its owner to retry; copies do not grant additional redemptions.
A successful release returns the empty frame, and charging it again consumes materials.
Optional first-player gifts and wild spawning are independent acquisition routes, both disabled in new configurations.
When wild spawning is enabled, an unbound maid can be tamed with cake. Ownership transfer is outside the first release.

- Owner main-hand right-click opens the maid page for tasks, schedules, home areas, equipment, models and voices.
- Sneak-right-click with an empty hand toggles sitting. If the maid is riding or has a passenger, it dismounts first;
  the next interaction toggles sitting. Right-click with a saddle carries or puts down the maid.
- An unnamed name tag opens the naming page; an already named tag uses native naming interaction.
- Sneak-use a golden apple, drinkable potion or milk for care, using the actual item's consumption and container rules.
- Empty glass bottles extract maid experience at 12 XP per bottle; sneaking allows a batch exchange.
- The voice page selects this maid's pack or previews it locally;
  global settings edit local sound, bubbles and icon cache.

Writes require the owner, range, current window and actual permission checks. Spectators cannot modify the maid.
Armour, both hands and normal inventory have separate slots; built-in attachments differ between models.
Switching between melee and ranged tasks keeps a suitable held weapon; otherwise it selects the first suitable weapon
from the 36 backpack slots in slot order and swaps the whole stack with the current main-hand stack.
Durability and full NBT are retained. If none is suitable, the task still changes and the held item stays in place.
With the current GTNH settings, closing a window first returns a cursor stack to player inventory when space exists.
A full inventory may leave an actual dropped item.
Check player inventory and ground items after closing to confirm where the stack went.

Death films are collected through the tombstone flow and inserted into a shrine for revival.
Revival costs owner health; check the player's condition and the available space before proceeding.
Films, held inventory, experience and revival credentials have separate records.
Copies grant no extra inventory or rights.

## Inventory, baubles and carried backpacks

All 36 backpack slots are available by default. Hands and armour use separate slots;
back-mounted item visuals follow the existing display rules. The bauble page contains 30 slots.

| Favourability level | Points from | Available bauble slots |
| --- | --- | --- |
| 0 | 0 | 10 |
| 1 | 64 | 10 |
| 2 | 192 | 20 |
| 3 | 384 | 30 |

Items in rows locked by a favourability decrease remain saved, while their equipment effects stop.
Baubles include the two bells and supported flight, oxygen and thermal equipment.
Carrying an item in ordinary storage does not equip it.

Pickup and inventory transfers first try a carried backpack's internal storage, then merge into the maid's
inventory and hands. Eydamos Backpack, Adventure Backpack, Forestry Backpack, Extra Utilities Golden Bag
and ProjectRed backpacks are supported. Provider capacity, item restrictions and locking modes remain effective.
Golden Bag is disabled in GTNH's default configuration and requires the pack setting to expose the item.
Locked Forestry backpacks retain their contents but reject automatic insertion and extraction; unlock them before work.

## Bells and maintenance work

Wear bells in unlocked bauble slots, select the work task and use a work schedule.
Work operates in loaded chunks.

| Operation | Maintenance bell | Breeding bell |
| --- | --- | --- |
| Bind | Sneak-right-click the ground to toggle its chunk | Sneak-right-click the centre soil of a same-level 3×3 plot; a crop-stick click resolves to its soil |
| Limits | Up to 16 chunks in one dimension, gaps allowed; max minus min chunk coordinate is at most 8 on each axis | One 3×3 plot in one dimension |
| Unbind | Click a bound chunk again; removing the last chunk clears the binding | Click the same plot centre again |
| Clear | Use the registered recipe with the bell alone in a crafting grid | Same |

The clearing recipe removes binding data and retains other item NBT.
A maintenance bell cannot mix dimensions. Its nearby owner can sneak and look at the maid within 8 blocks
to preview the worn bell's range. Leaving range, stopping sneaking, removing the bell or locking its row closes the
preview.

Maintenance patrols scan ordinary GregTech maintenance hatches in the bound loaded chunks and repair their six
actual maintenance states. Put a valid GT wrench in the offhand; repairs approach within 3.5 blocks.
Automatic maintenance hatches are excluded. Repairs consume no wrench, materials, durability or energy.
Bells do not force-load chunks, and patrols need a loaded, traversable route.

## CropsNH world breeding

1. Prepare a clear 3×3 plot. Irrigate farmland with a real water source and provide the required soil and subsoil for
   stone or ore crops.
2. Bind and wear a breeding bell, select the breeding task and open its configuration page.
3. Choose one world-growable crop or a mutation pool. Configure Ga (gain), Gr (growth), Re (resistance) and OR/AND
   logic.
4. Supply actual seeds or accepted alternate fruit, a CropsNH spade, crop sticks, soil, subsoil and any required
   mining tool.
5. Keep a work schedule and an accessible plot. The maid works within 4 blocks of the centre, using natural growth
   for propagation, weeding, crossing, seed harvest and parent replacement.

OR accepts any configured stat threshold; AND requires all three. Max-31 mode requires all three stats to reach 31.
The usual plot has five single-stick parents and four double-stick crosses, requiring 13 sticks.
Four-parent recipes use four parents and a central double cross.
Crossing consumes the second stick according to CropsNH's rules, so keep spare sticks available.

Work materials may come from raw slots or extractable carried backpacks. Borrowed tools are returned with their actual
wear.
Offspring can enter a backpack and later be extracted as new parents.
Daily meals preserve recognised planting seeds and alternate fruit; emergency healing and breathing recovery retain
their own rules.
Foundation changes check actual tool type and harvest level, apply real wear and recover block drops.
A high-level wrench cannot replace a suitable mining tool.

Not every NEI entry supports world breeding. Machine-only crops or targets without usable parents produce a reminder.
Missing bells, binding, dimension, plot, target, spade, parents, sticks, soil, subsoil or harvesting tools are reported
to the online owner using the maid's name. An unchanged missing set repeats after 1200 ticks;
changes are reported immediately. Production uses natural growth without test acceleration.

## Hunger, hot items and survival equipment

Food caps at 20. Movement, swimming, jumping and effective combat actions add exhaustion,
which drains saturation before food. Eating consumes actual food and applies nutrition and container returns;
extractable backpacks can supply food. Regeneration and starvation follow current game rules, difficulty and pack
integration.
Carry ordinary food as well as planting fruit for daily meals.

The Extra Utilities Healing Axe in the offhand restores 1 food point and corresponding saturation every 40 ticks.
Main-hand use follows provider semantics for undead damage, zombie-villager conversion and exhaustion.
Other creatures receive neither an ordinary axe strike nor healing from it; the axe takes no durability loss.

Hot-item checks cover all 36 raw slots and both hands; contents inside carried backpacks are protected from this scan.
Repeated actual heat damage causes exposed hot items to be dropped, followed by a 200-tick pickup cooldown.
Items that can safely enter a carried backpack may still be collected.
Complete heat-protection equipment follows provider immunity rules.
GT++ hot ingots cool through their actual recipe; the mod adds no passive inventory conversion.

### Flight and armour

| Equipment group | Where to wear it and what it provides |
| --- | --- |
| Adventure Backpack | Copter or coal jetpack in the chest or an eligible unlocked bauble slot; copter flight stops underwater |
| IC2 | Fuel/electric jetpacks, or quantum chest armour with its actual embedded-jetpack tag; standalone packs support eligible bauble slots |
| GraviSuite advanced equipment | Advanced jetpack or advanced nano chest armour, worn according to its actual item type |
| GraviSuite gravitation chest armour | Chest slot, providing its flight and hover capability |

Flight supports following an airborne owner, hovering and collision-aware three-dimensional movement.
Flight propulsion itself consumes no EU or fuel. Armour defence, oxygen and special effects settle independently
and may consume real energy or oxygen. Nano/quantum protection retains actual defence and costs.
Applicable helmets support underwater air, status removal, canned food and empty-can returns, and bright-light
night-vision penalties. Applicable chest armour and full hazmat protection support their provider's fire handling.

### Space and fall rescue

Place oxygen masks, breathing gear, tanks and supported thermal padding in unlocked bauble slots.
Non-breathable environments require a valid oxygen setup and actual oxygen consumption;
infinite tanks and breathable regions follow provider rules. Thermal protection combines the four actual padding
parts with the environment, including GalaxySpace tier-two padding. Locked rows provide no protection.

Fall rescue checks existing protection, available water and equipment before attempting a real water placement or
rescue boat.
It needs an actual water bucket or supported boat, a usable landing position and owner permissions.
Cancelled placement events preserve the item, and boat recovery verifies the actual entity and state.
Equip for the environment before entering space or working at height.

## Configuration

The server creates `config/tlmnh.cfg` during normal startup. Restart the server after changing startup settings.
Existing explicit values remain in place; updating the JAR does not replace the old configuration.

| Setting | Default | Purpose |
| --- | --- | --- |
| `acquisition.B:naturalSpawning` | `false` | Wild spawning |
| `acquisition.B:giveInitialSlab` | `false` | One owner-bound slab gift per player; waits for inventory space |
| `acquisition.I:naturalWeight` | `1` | Spawn weight, 1–100, one maid per group |
| `acquisition.I:naturalBiomes` | `1, 4` | Allowed numeric biome IDs |
| `acquisition.I:naturalDimensions` | `0` | Allowed numeric dimension IDs |
| `behavior.S:profile` | `enhanced` | Server startup policy: `legacy_prototype` or `enhanced` |
| `combat.I:ownerCombatAcquireRange` | `20` | Enhanced non-home target acquisition radius around the owner, 8–24 blocks |
| `combat.I:ownerCombatRetainRange` | `24` | Radius around the owner for the same target in an active combat session, 8–24 blocks |

If acquisition exceeds retention, the server reduces acquisition to retention, saves the normalized value and logs a
warning.
After combat cancellation, a target must qualify for acquisition again.
The maid and combat movement destinations use the owner's 16-block activity domain.
Targets must be within 24 blocks of the maid. Home mode uses its WORK area.
Equipment, damage clues, periodic scouting and the current target determine detection range.
Actual discovery depends on sight, loaded-chunk scanning and the available budget.

Forge stores a category and typed key. The following example selects the enhanced policy.

```text
behavior {
    S:profile=enhanced
}
```

The policy applies to existing and new maids loaded under that server configuration.
It is not a per-maid policy migration field.
Unknown or development-only values are preserved and logged, with the current legacy policy used as fallback.
Research components are not production configuration options.
New configurations and a missing profile key default to Enhanced. Existing explicit `legacy_prototype` values
continue to use Legacy. Enhanced is the chosen default for the first release.
Use the generated comments for other home, sound, food, perception, navigation and combat settings.

Local `config/tlmnh-client.cfg` stores these global-page preferences without changing the server maid's task.

| Setting | Default |
| --- | --- |
| `sound.I:GlobalMaidSoundFrequency` | `100`, range 0–100 |
| `render.B:GlobalMaidShowChatBubble` | `true` |
| `model.B:EnableModelIconCache` | `false` |

A maid's own voice pack, frequency and task are configured separately on its page.
Saving or cancelling the global page affects local preferences only.

## World rollback and fault preservation

The updater's rollback restores TLM JARs, documents and management records. It does not restore a game world.
After a newer version has saved the world, downgrading requires a normal shutdown followed by the matching old JARs,
the complete pre-upgrade world and its configuration.
The world includes every dimension, `playerdata`, entity regions and `data` ledgers; one player file is insufficient.
Retain a separate copy of the newer world. Editing a schema number is not a migration.

When a diagnostic appears, retain the candidate version, both JAR SHAs, diagnostic ID, logs and original save backup.
The mod preserves uncertain raw item NBT and disables the affected capability.
Keep the inventory and compatibility baseline available for diagnosis.
The client baseline is `config/tlmnh-client-compatibility.dat`; server compatibility records are in world data.
After restoring a reviewed compatible environment, right-click a protected package to attempt unpacking.
Its nearby owner can also use the following command.

```text
/tlmnhprotect recover <numeric maid entity ID>
```

It retrieves usable held items individually, retains them when inventory is full or compatibility remains unresolved,
and does not bypass fingerprint checks. A snack cabinet's held items use its sneak interaction.

## Content and release boundaries

Retained resources include 435 voice files, 10 base sound effects, 332 supplemental original assets and
254 model choices. The wine-fox series contains 21 base entries and 14 additional texture choices;
named easter eggs retain their original activation conditions.
See [Third-party notices](../THIRD_PARTY_NOTICES.md), [Source provenance](../SOURCE_PROVENANCE.md) and `licenses/`
for authors, file digests and licences.

External model import, YSM, LLMs and ownership transfer are outside this release.
Final statistics, two-sided networking, combined scenarios and player release acceptance are tracked in the
[release notes](releases/0.1.0.md). Asset retention and individual tests cover their stated scopes.
Original adaptation code uses [GPL-3.0-only](../LICENSE); upstream assets retain their own terms.
