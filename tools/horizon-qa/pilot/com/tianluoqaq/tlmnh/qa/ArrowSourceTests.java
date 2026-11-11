package com.tianluoqaq.tlmnh.qa;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.EntityList;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagString;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowImpactState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.combat.projectile.ProjectileCombatIds;
import com.tianluoqaq.tlmnh.common.combat.projectile.ProjectileCombatNbtKeys;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.runtime.ProtectionEvents;
import com.tianluoqaq.tlmnh.qa.ProjectileImpactTests.Environment;

import cpw.mods.fml.relauncher.Side;

@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa" })
public final class ArrowSourceTests {

    private ArrowSourceTests() {}

    private static void mode(GameTestHelper helper, boolean missing) {
        helper.assertTrue(
            Boolean.getBoolean("tlmnh.devCommands")
                && Boolean.getBoolean("tlmnh.qaProjectileImpactFallback") == missing,
            "Wrong projectile source JVM mode");
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 85, batch = "arrow_source_restore")
    public static void nativeAndResearchArrowsRestoreOnlyTheirRecordedLiveShooterAfterNativeNbt(GameTestHelper helper) {
        mode(helper, false);
        var e = new Environment(helper);
        int[] phase = { 0 }, shot = { 0 };
        NBTTagCompound[] saved = { null };
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                e.resetActors();
                e.newTarget();
                e.shoot(
                    shot[0] == 0 ? ModernFlightProfile.NATIVE_MOTION_COMPENSATION
                        : ModernFlightProfile.SOURCE_ORIGINAL);
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                helper.assertTrue(
                    e.arrow.ticksExisted > 0 && e.arrow.posX > e.f.maid.posX,
                    "Arrow did not actually fly before its native NBT checkpoint");
                saved[0] = save(e.arrow, helper);
                var type = e.arrow.getClass();
                e.arrow.setDead();
                e.arrow = restore(saved[0], helper);
                helper.assertTrue(
                    e.arrow.getClass() == type && e.arrow.shootingEntity == null,
                    "Native NBT did not actually lose its transient shooter or changed the registered arrow type");
                spawn(e.arrow, helper);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                helper.assertTrue(
                    e.arrow.shootingEntity == e.f.maid && !((MaidArrowSourceState) e.arrow).tlmnh$sourceStopped(),
                    "Recorded UUID/world/owner did not restore the exact live shooter");
                e.cross(shot[0] == 0 ? e.owner : e.wolf);
                phase[0] = 3;
                return false;
            }
            if (e.target.getHealth() == 200) return false;
            var actor = shot[0] == 0 ? e.owner : e.wolf;
            helper.assertTrue(
                actor.getHealth() == 20 && !actor.isBurning()
                    && ((MaidArrowImpactState) e.arrow).tlmnh$ignoredImpacts() > 0
                    && saved[0].getTag(ProjectileCombatNbtKeys.TAG)
                        .equals(((MaidArrowSourceState) e.arrow).tlmnh$source()),
                "Restored flame arrow harmed its ally, skipped no collision, or rewrote its source record");
            e.items();
            e.removeArrowAndTarget();
            phase[0] = 0;
            return ++shot[0] == 2;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 60, batch = "arrow_source_stop")
    public static void missingShooterAndUnknownRawRecordsRemainStoppedAcrossNativeNbt(GameTestHelper helper) {
        mode(helper, false);
        var e = new Environment(helper);
        e.newTarget();
        e.shoot(ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
        var arrowData = save(e.arrow, helper);
        var maidData = new NBTTagCompound();
        e.f.maid.writeToNBT(maidData);
        e.f.maid.setDead();
        e.arrow.setDead();
        var arrows = new ArrayList<EntityArrow>();
        var originals = new ArrayList<NBTTagCompound>();
        for (int index = 0; index < 3; index++) {
            var raw = (NBTTagCompound) arrowData.copy();
            var id = UUID.randomUUID();
            raw.setLong("UUIDMost", id.getMostSignificantBits());
            raw.setLong("UUIDLeast", id.getLeastSignificantBits());
            if (index == 1) {
                raw.getCompoundTag(ProjectileCombatNbtKeys.TAG)
                    .setInteger("Schema", 91);
                raw.getCompoundTag(ProjectileCombatNbtKeys.TAG)
                    .setByteArray("FutureOpaque", new byte[] { 7, 2, 9 });
            }
            if (index == 2) raw.setTag(ProjectileCombatNbtKeys.TAG, new NBTTagString("future noncompound source"));
            var arrow = restore(raw, helper);
            originals.add(raw);
            arrows.add(arrow);
            helper.afterTest(arrow::setDead);
            spawn(arrow, helper);
        }
        EntityMaid[] returned = { null };
        EntityArrow[] reloaded = { null };
        NBTTagCompound[] paused = { null };
        int[] tick = { 0 };
        helper.succeedWhen(() -> {
            if (++tick[0] < 3) return false;
            if (tick[0] == 3) {
                for (int index = 0; index < arrows.size(); index++) {
                    var arrow = arrows.get(index);
                    var source = (MaidArrowSourceState) arrow;
                    helper.assertTrue(
                        !arrow.isDead && source.tlmnh$sourceStopped()
                            && originals.get(index)
                                .getTag(ProjectileCombatNbtKeys.TAG)
                                .equals(source.tlmnh$source()),
                        "Missing shooter or unknown original NBT was discarded/rewritten");
                }
                paused[0] = save(arrows.get(0), helper);
                returned[0] = new EntityMaid(helper.getWorld());
                returned[0].readFromNBT(maidData);
                returned[0].getBrainCoordinator()
                    .setSuspended(true);
                helper.afterTest(returned[0]::setDead);
                helper.assertTrue(
                    helper.getWorld()
                        .spawnEntityInWorld(returned[0]),
                    "Recorded maid did not return");
                IntegrationGuard.resetForDevelopment(Side.SERVER);
                helper.assertTrue(
                    MaidProjectileImpactProtection.available(returned[0]),
                    "Fixture did not clear the separate runtime fault");
                arrows.get(0)
                    .setDead();
                reloaded[0] = restore(paused[0], helper);
                helper.afterTest(reloaded[0]::setDead);
                spawn(reloaded[0], helper);
                return false;
            }
            if (tick[0] < 24) return false;
            helper.assertTrue(
                !reloaded[0].isDead && ((MaidArrowSourceState) reloaded[0]).tlmnh$sourceStopped()
                    && paused[0].equals(save(reloaded[0], helper))
                    && e.owner.getHealth() == 20
                    && !e.owner.isBurning(),
                "Reload automatically resumed a stopped arrow or lost its native persisted data");
            for (int index = 1; index < arrows.size(); index++) helper.assertTrue(
                originals.get(index)
                    .getTag(ProjectileCombatNbtKeys.TAG)
                    .equals(save(arrows.get(index), helper).getTag(ProjectileCombatNbtKeys.TAG)),
                "Opaque unsupported source record changed during stopped world ticks");
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 50, batch = "arrow_source_missing_impact")
    public static void missingImpactRefusesReleaseAndRetainsExistingFlameArrowBeforeAnyDamage(GameTestHelper helper) {
        mode(helper, true);
        var e = new Environment(helper);
        e.drain(e.owner, null);
        e.drain(e.ally, null);
        e.newTarget();
        e.setupBow(ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
        helper.assertTrue(
            !e.bow.fireForModern(e.target, 1, () -> true) && e.bow.getLastArrow() == null,
            "Absent impact hook allowed a production flame arrow");
        e.unspentItems();
        e.arrow = new EntityArrow(
            helper.getWorld(),
            e.f.maid.posX,
            e.f.maid.posY + e.f.maid.getEyeHeight() - .1,
            e.f.maid.posZ);
        e.arrow.shootingEntity = e.f.maid;
        e.arrow.setThrowableHeading(1, 0, 0, 3, 0);
        e.arrow.setFire(100);
        spawn(e.arrow, helper);
        e.cross(e.owner);
        helper.assertTrue(
            !(e.arrow instanceof MaidArrowImpactState) && ((MaidArrowSourceState) e.arrow).tlmnh$source() != null,
            "Missing native hook or retained source fixture is false");
        var damage = new ProjectileImpactTests.DamageObservation(e.owner);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(damage);
        helper.afterTest(() -> net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(damage));
        NBTTagCompound[] frozen = { null };
        int[] tick = { 0 };
        helper.succeedWhen(() -> {
            if (++tick[0] == 1) {
                helper.assertTrue(
                    ((MaidArrowSourceState) e.arrow).tlmnh$sourceStopped() && !e.arrow.isDead,
                    "Existing flame arrow did not stop before native update");
                frozen[0] = save(e.arrow, helper);
                ProtectionEvents.warn(e.owner);
                ProtectionEvents.warn(e.owner);
                ProtectionEvents.warn(e.ally);
                var fault = IntegrationGuard.faults(Side.SERVER)
                    .get("projectile_impact/" + e.f.maid.getUniqueID());
                helper.assertTrue(
                    fault != null && e.drain(e.owner, fault.code) == 1 && e.drain(e.ally, fault.code) == 0,
                    "Missing, repeated or wrongly addressed actual chat warning");
            }
            if (tick[0] < 24) return false;
            helper.assertTrue(
                !damage.received && !e.owner.capabilities.disableDamage
                    && e.owner.getHealth() == 20
                    && !e.owner.isBurning()
                    && e.target.getHealth() == 200
                    && !e.arrow.isDead
                    && frozen[0].equals(save(e.arrow, helper)),
                "Stopped flame arrow moved, harmed or changed persisted data");
            e.unspentItems();
            return true;
        });
    }

    @GameTest(template = "modern_core_cell", timeoutTicks = 55, batch = "arrow_source_player_handoff")
    public static void actualTinkerBattleSignHandoffSurvivesNativeReloadAndKeepsFreshMaidShotsFinite(
        GameTestHelper helper) {
        mode(helper, false);
        var e = new Environment(helper);
        e.newTarget();
        e.shoot(ModernFlightProfile.NATIVE_MOTION_COMPENSATION);
        var originalSource = ((MaidArrowSourceState) e.arrow).tlmnh$source()
            .copy();
        EntityArrow[] reloaded = { null }, fresh = { null }, unverifiedReloaded = { null };
        NBTTagCompound[] transferredNbt = { null }, unverifiedSaved = { null };
        var rejected = new ArrayList<EntityArrow>();
        var rejectedMarkers = new ArrayList<net.minecraft.nbt.NBTBase>();
        int[] phase = { 0 };
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                if (e.arrow.ticksExisted == 0) return false;
                helper.assertTrue(
                    e.arrow.posX > e.f.maid.posX + 1,
                    "Native source arrow did not clear its original shooter");
                e.items();
                reflectThroughActualTinkerCallback(helper, e);
                var sameTick = save(e.arrow, helper);
                helper.assertTrue(
                    sameTick.hasKey(ProjectileCombatIds.HANDOFF_TAG, 10)
                        && originalSource.equals(sameTick.getTag(ProjectileCombatNbtKeys.TAG))
                        && e.arrow.shootingEntity == e.ally,
                    "Same-tick native save lost the new player handoff before its next arrow update");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                var state = (MaidArrowSourceState) e.arrow;
                helper.assertTrue(
                    !e.arrow.isDead && e.arrow.shootingEntity == e.ally
                        && !state.tlmnh$sourceStopped()
                        && state.tlmnh$sourceHandoff() != null
                        && originalSource.equals(state.tlmnh$source())
                        && MaidProjectileImpactProtection.available(e.f.maid)
                        && !IntegrationGuard.faults(Side.SERVER)
                            .containsKey("projectile_impact/" + e.f.maid.getUniqueID()),
                    "Natural native tick froze/reassigned a real Tinker player takeover or disabled the old maid");
                transferredNbt[0] = save(e.arrow, helper);
                e.arrow.setDead();
                reloaded[0] = restore(transferredNbt[0], helper);
                helper.afterTest(reloaded[0]::setDead);
                helper.assertTrue(
                    reloaded[0].shootingEntity == null,
                    "Native arrow reload unexpectedly restored its transient player");
                spawn(reloaded[0], helper);
                phase[0] = 2;
                return false;
            }
            if (phase[0] == 2) {
                var state = (MaidArrowSourceState) reloaded[0];
                helper.assertTrue(
                    !reloaded[0].isDead && reloaded[0].ticksExisted > 0
                        && reloaded[0].shootingEntity == null
                        && !state.tlmnh$sourceStopped()
                        && originalSource.equals(state.tlmnh$source())
                        && transferredNbt[0].getTag(ProjectileCombatIds.HANDOFF_TAG)
                            .equals(state.tlmnh$sourceHandoff()),
                    "Natural reload rebound the old maid, invented a new player, stopped native flight or changed handoff provenance");
                reloaded[0].setDead();
                for (int i = 0; i < 2; i++) {
                    var raw = (NBTTagCompound) transferredNbt[0].copy();
                    var id = UUID.randomUUID();
                    raw.setLong("UUIDMost", id.getMostSignificantBits());
                    raw.setLong("UUIDLeast", id.getLeastSignificantBits());
                    if (i == 0) {
                        raw.getCompoundTag(ProjectileCombatIds.HANDOFF_TAG)
                            .setInteger("Schema", 91);
                        raw.getCompoundTag(ProjectileCombatIds.HANDOFF_TAG)
                            .setByteArray("FutureOpaque", new byte[] { 4, 7, 2 });
                    } else raw.setTag(ProjectileCombatIds.HANDOFF_TAG, new NBTTagString("future noncompound handoff"));
                    var unknown = restore(raw, helper);
                    unknown.setPosition(e.f.maid.posX + 3, e.f.maid.posY + 2, e.f.maid.posZ + 2);
                    rejectedMarkers.add(
                        raw.getTag(ProjectileCombatIds.HANDOFF_TAG)
                            .copy());
                    rejected.add(unknown);
                    helper.afterTest(unknown::setDead);
                    spawn(unknown, helper);
                }
                var unverifiedRaw = (NBTTagCompound) transferredNbt[0].copy();
                unverifiedRaw.removeTag(ProjectileCombatIds.HANDOFF_TAG);
                var unverifiedId = UUID.randomUUID();
                unverifiedRaw.setLong("UUIDMost", unverifiedId.getMostSignificantBits());
                unverifiedRaw.setLong("UUIDLeast", unverifiedId.getLeastSignificantBits());
                var unverifiedArrow = restore(unverifiedRaw, helper);
                helper.afterTest(unverifiedArrow::setDead);
                var unregistered = new net.minecraft.entity.player.EntityPlayerMP(
                    net.minecraft.server.MinecraftServer.getServer(),
                    helper.getWorld(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "NoWorldEntry"),
                    new net.minecraft.server.management.ItemInWorldManager(helper.getWorld()));
                helper.afterTest(unregistered::setDead);
                helper.assertTrue(
                    !helper.getWorld().playerEntities.contains(unregistered) && helper.getWorld()
                        .getEntityByID(unregistered.getEntityId()) != unregistered,
                    "Controlled unregistered player unexpectedly entered the native world");
                unverifiedArrow.shootingEntity = unregistered;
                unverifiedSaved[0] = save(unverifiedArrow, helper);
                helper.assertTrue(
                    unverifiedSaved[0].hasKey(ProjectileCombatIds.STOPPED_TAG)
                        && !unverifiedSaved[0].hasKey(ProjectileCombatIds.HANDOFF_TAG)
                        && originalSource.equals(unverifiedSaved[0].getTag(ProjectileCombatNbtKeys.TAG))
                        && unverifiedArrow.shootingEntity == unregistered,
                    "Same-tick save laundered an unverified player takeover or changed its original data");
                unverifiedArrow.setDead();
                unverifiedReloaded[0] = restore(unverifiedSaved[0], helper);
                helper.afterTest(unverifiedReloaded[0]::setDead);
                spawn(unverifiedReloaded[0], helper);
                e.bow.observe(
                    e.target,
                    true,
                    helper.getWorld()
                        .getTotalWorldTime());
                helper.assertTrue(
                    e.bow.fireForModern(e.target, 1, () -> true),
                    "Old native player handoff refused an independent fresh maid arrow");
                fresh[0] = e.bow.getLastArrow();
                helper.assertTrue(
                    fresh[0] != null && fresh[0].shootingEntity == e.f.maid,
                    "Fresh arrow source changed after independent player takeover");
                helper.afterTest(fresh[0]::setDead);
                phase[0] = 3;
                return false;
            }
            for (int i = 0; i < rejected.size(); i++) {
                var state = (MaidArrowSourceState) rejected.get(i);
                helper.assertTrue(
                    !rejected.get(i).isDead && state.tlmnh$sourceStopped()
                        && originalSource.equals(state.tlmnh$source())
                        && rejectedMarkers.get(i)
                            .equals(state.tlmnh$sourceHandoff()),
                    "Unknown handoff/source data was rewritten, destroyed or treated as valid native authority");
            }
            helper.assertTrue(
                !unverifiedReloaded[0].isDead && unverifiedReloaded[0].shootingEntity == null
                    && ((MaidArrowSourceState) unverifiedReloaded[0]).tlmnh$sourceStopped()
                    && unverifiedSaved[0].equals(save(unverifiedReloaded[0], helper)),
                "Reload resumed an unverified takeover, restored the old maid or rewrote its retained native data");
            if (e.target.getHealth() == 200) return false;
            helper.assertTrue(
                fresh[0].ticksExisted > 0 && !((MaidArrowSourceState) fresh[0]).tlmnh$sourceStopped()
                    && MaidProjectileImpactProtection.available(e.f.maid)
                    && net.minecraft.item.ItemStack.areItemStacksEqual(e.original, e.f.maid.getOffhandItem())
                    && e.weapon.getItemDamage() == 2
                    && e.f.maid.getMaidInventory()
                        .getStackInSlot(0).stackSize == 6,
                "Fresh native arrow failed to fly/hit with exactly two finite payments and intact original resources");
            System.out.println(
                "TLMNH_ARROW_PLAYER_HANDOFF_PASS provider=TConstruct-native-onAttack boundary=controlled-callback naturalWorldTicks=true reload=vanilla-null-shooter sourceRaw=preserved unknownMarker=preserved freshShots=1");
            return true;
        });
    }

    /** Actual installed ToolBuilder/battle-sign callback; controlled damage event, not a claimed natural collision. */
    private static void reflectThroughActualTinkerCallback(GameTestHelper helper, Environment e) {
        try {
            var tools = Class.forName("tconstruct.tools.TinkerTools");
            var head = new net.minecraft.item.ItemStack(
                (net.minecraft.item.Item) tools.getField("signHead")
                    .get(null),
                1,
                0);
            var rod = new net.minecraft.item.ItemStack(
                (net.minecraft.item.Item) tools.getField("toolRod")
                    .get(null),
                1,
                0);
            var builder = Class.forName("tconstruct.library.crafting.ToolBuilder");
            var stack = (net.minecraft.item.ItemStack) builder
                .getMethod(
                    "buildTool",
                    net.minecraft.item.ItemStack.class,
                    net.minecraft.item.ItemStack.class,
                    net.minecraft.item.ItemStack.class,
                    String.class)
                .invoke(
                    builder.getField("instance")
                        .get(null),
                    head,
                    rod,
                    null,
                    "QA native battle-sign reflection");
            helper.assertTrue(
                stack != null && stack.getItem() == tools.getField("battlesign")
                    .get(null)
                    && stack.hasTagCompound()
                    && !stack.getTagCompound()
                        .getCompoundTag("InfiTool")
                        .getBoolean("Broken"),
                "Actual installed Tinker builder did not create its legitimate unbroken battle sign");
            e.ally.setCurrentItemOrArmor(0, stack);
            e.ally.setItemInUse(stack, stack.getMaxItemUseDuration());
            // Source callback accepts any angle with -look.dot(projectileDirection)>=.1; this bounded input keeps the
            // fixture arrow in loaded nearby chunks.
            e.ally.rotationYaw = 12;
            e.ally.rotationPitch = 0;
            var event = new net.minecraftforge.event.entity.living.LivingAttackEvent(
                e.ally,
                net.minecraft.util.DamageSource.causeArrowDamage(e.arrow, e.f.maid),
                2);
            var callback = Class.forName("tconstruct.tools.TinkerToolEvents");
            callback.getMethod("onAttack", net.minecraftforge.event.entity.living.LivingAttackEvent.class)
                .invoke(
                    callback.getDeclaredConstructor()
                        .newInstance(),
                    event);
            helper.assertTrue(
                event.isCanceled() && e.arrow.shootingEntity == e.ally,
                "Actual installed Tinker native onAttack did not cancel/reflect and transfer its arrow to the player");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                "Actual installed Tinker battle-sign input could not be constructed or observed",
                error);
        }
    }

    private static NBTTagCompound save(EntityArrow arrow, GameTestHelper helper) {
        var tag = new NBTTagCompound();
        helper.assertTrue(
            arrow.writeToNBTOptional(tag),
            "Actual live arrow could not serialize through native entity save");
        return tag;
    }

    private static EntityArrow restore(NBTTagCompound tag, GameTestHelper helper) {
        var entity = EntityList.createEntityFromNBT((NBTTagCompound) tag.copy(), helper.getWorld());
        helper.assertTrue(entity instanceof EntityArrow, "Native registry did not recreate the saved arrow");
        return (EntityArrow) entity;
    }

    private static void spawn(EntityArrow arrow, GameTestHelper helper) {
        helper.assertTrue(
            helper.getWorld()
                .spawnEntityInWorld(arrow)
                && helper.getWorld()
                    .getEntityByID(arrow.getEntityId()) == arrow,
            "Actual arrow absent from native world table");
    }
}
