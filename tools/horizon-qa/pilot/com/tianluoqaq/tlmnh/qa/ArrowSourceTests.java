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
import com.tianluoqaq.tlmnh.common.combat.MaidArrowImpactState;
import com.tianluoqaq.tlmnh.common.combat.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.MaidBowAttack.ModernFlightProfile;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileImpactProtection;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileSource;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;
import com.tianluoqaq.tlmnh.common.protection.ProtectionEvents;
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
                    && saved[0].getTag(MaidProjectileSource.TAG)
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
                raw.getCompoundTag(MaidProjectileSource.TAG)
                    .setInteger("Schema", 91);
                raw.getCompoundTag(MaidProjectileSource.TAG)
                    .setByteArray("FutureOpaque", new byte[] { 7, 2, 9 });
            }
            if (index == 2) raw.setTag(MaidProjectileSource.TAG, new NBTTagString("future noncompound source"));
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
                                .getTag(MaidProjectileSource.TAG)
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
                    .getTag(MaidProjectileSource.TAG)
                    .equals(save(arrows.get(index), helper).getTag(MaidProjectileSource.TAG)),
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
