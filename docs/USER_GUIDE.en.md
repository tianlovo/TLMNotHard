# Indev usage, configuration and updates

[简体中文](USER_GUIDE.md) | English

This guide covers TLMNotHard development candidates for GTNH 2.9.0. No stable release is available.
See the repository's `PROGRESS.md` for implemented features, candidate-specific evidence and outstanding failures.
The included `audit-report.json` checks package contents.
Gameplay acceptance and voice publication are recorded separately.

## Installation and updates

Use a complete GTNH 2.9.0 client and dedicated server with byte-identical TLM JARs on both sides.
The game runtime is Java 25; the mod's bytecode target remains Java 8.
The mod requires APIs supplied by the complete GTNH 2.9.0 pack.

1. Exit both sides normally and confirm that the server has saved and stopped.
2. Back up the entire server world, both configurations, the old TLM JARs and update records.
3. Extract the correct side's ZIP outside the target instance and check `SHA256SUMS.txt` and the candidate JAR SHA.
4. Install Python 3, then run `python -m pip install rich` to install Rich.
5. Run `python update.py` in the extracted directory and select the actual client or server directory in its menu.
6. Start the server and client only after both updates finish and their JAR hashes match.

A client target contains `mmc-pack.json`; its `.minecraft` directory is also accepted.
A server target contains `lwjgl3ify-forgePatches.jar`.
The updater manages the TLM JAR and `.tlmnh-distribution/<side>` documents only.
It preserves pack configuration and `.gtnh-managed-*.json`.
An old JAR must match an approved filename and SHA or the exact version in an existing TLM management record.
Unknown versions, changed bytes and user edits are retained and rejected for review.

Recognized process records are checked, but users must still exit any other launcher normally.
A 1.7 `session.lock` check cannot prove that a multiplayer client or server has stopped.
Applying the same package again preserves its existing files.
Backups and rollback records are under `.tlmnh-update-backups/<side>`.
The ZIP's root `README.md` describes the update menu and its precise scope.

## Acquisition and basic controls

Current progression starts with a reusable empty soul frame, charged with a GT baked cake in an alloy smelter.
The core route is available in the steam era; the shrine requires an LV assembler.
Consult the candidate's registered NEI recipes for ingredients. Costs and machine times remain Indev balance values.

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

The information icon shows the actual name and food-level attribute. The attribute defaults to 20 and,
following the original base mod, does not decrease on its own.

Writes require the owner, range, current window and actual permission checks. Spectators cannot modify the maid.
Armor, both hands, the back item and normal inventory have separate slots; built-in attachments differ between models.
Switching between melee and ranged tasks keeps a suitable held weapon; otherwise it selects the first suitable weapon
from the six backpack slots in slot order and swaps the whole stack with the current main-hand stack.
Durability and full NBT are retained. If none is suitable, the task still changes and the held item stays in place.
With the current GTNH settings, closing a window first returns a cursor stack to player inventory when space exists.
A full inventory may leave an actual dropped item.
Check player inventory and ground items after closing to confirm where the stack went.

Death films are collected through the tombstone flow and inserted into a shrine for revival.
Revival costs owner health; check the player's condition and the available space before proceeding.
Films, held inventory, experience and revival credentials have separate records.
Copies grant no extra inventory or rights.

## Configuration

The server creates `config/tlmnh.cfg` during normal startup. Restart the server after changing startup settings.
Existing explicit values remain in place; updating the JAR does not replace the old configuration.

| Setting | Current Indev default | Purpose |
| --- | --- | --- |
| `acquisition.B:naturalSpawning` | `false` | Wild spawning |
| `acquisition.B:giveInitialSlab` | `false` | One owner-bound slab gift per player; waits for inventory space |
| `acquisition.I:naturalWeight` | `1` | Spawn weight, 1–100, one maid per group |
| `acquisition.I:naturalBiomes` | `1, 4` | Allowed numeric biome IDs |
| `acquisition.I:naturalDimensions` | `0` | Allowed numeric dimension IDs |
| `behavior.S:profile` | `enhanced` | Server startup policy: `legacy_prototype` or `enhanced` |
| `combat.I:ownerCombatAcquireRange` | `20` | Enhanced non-home target acquisition radius around the owner, 8–24 blocks |
| `combat.I:ownerCombatRetainRange` | `24` | Radius around the owner for the same target in an active combat session, 8–24 blocks |

If acquisition exceeds retention, the server reduces acquisition to retention, saves the normalized value and logs a warning.
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

| Setting | Current default |
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

Candidates retain 435 voice files, 10 base sounds, 332 additional original resources and 254 built-in catalog entries.
The wine-fox series has 21 base entries and 14 extra textures.
The naming easter egg retains its original gate; the ordinary placeholder cannot select it.
Keeping resource files does not enable every original feature, and byte integrity does not certify all runtime scenes.

The first release excludes external model imports, YSM integration, LLMs,
ownership transfer and jobs beyond idle/combat.
Machine maintenance and CropsNH breeding remain future work.
Packages are currently local validation only. Voice publication steps, the stable version
and final acceptance remain pending.
Original adaptation code uses `GPL-3.0-only`; see [LICENSE](../LICENSE) for the full text.
Upstream code, models and sounds retain their own terms and attribution,
as detailed in [third-party notices](../THIRD_PARTY_NOTICES.md).
