package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 一批原生经验球共用写前屏障与实际区块读回；大int余额仍按每轮64球有界投放 */
public final class MaidDeathExperience {

    private MaidDeathExperience() {}

    public static void retryPending(World world) {
        if (world == null || world.isRemote) return;
        var ledger = MaidLifecycleData.get(world);
        int attempts = 0;
        for (var token : ledger.pendingDeathExperience(world.provider.dimensionId, world.getTotalWorldTime())) {
            var position = ledger.tombstonePosition(token);
            if (!IntegrationGuard.available(Side.SERVER, MaidLifecycleIds.ID_DEATH_EXPERIENCE + token)
                || position == null
                || !world.blockExists(
                    (int) Math.floor(position.xCoord),
                    MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM,
                    (int) Math.floor(position.zCoord)))
                continue;
            attempts += releaseBatch(world, ledger, token, MaidLifecycleTuning.MAX_ORBS_PER_PASS - attempts);
            if (attempts >= MaidLifecycleTuning.MAX_ORBS_PER_PASS) return;
        }
    }

    private static int releaseBatch(World world, MaidLifecycleData ledger, UUID token, int limit) {
        String key = MaidLifecycleIds.ID_DEATH_EXPERIENCE + token;
        if (!IntegrationGuard.available(Side.SERVER, key) || limit <= 0) return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        var position = ledger.tombstonePosition(token);
        if (position == null || !world.blockExists(
            (int) Math.floor(position.xCoord),
            MaidLifecycleTuning.DEATH_EXPERIENCE_BLOCK_EXISTS_SECOND_PARAM,
            (int) Math.floor(position.zCoord))) return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        int remaining = ledger.reserveDeathExperience(token);
        if (remaining <= 0) return MaidLifecycleTuning.CORE_INITIAL_VALUE;
        var publications = new ArrayList<MaidLifecycleDurability.Publication>();
        int attempts = 0, released = MaidLifecycleTuning.CORE_INITIAL_VALUE;
        boolean begun = false, settling = false, committed = false;
        try {
            MaidLifecycleDurability.requireEntities(world);
            ledger.beginExperienceAttempt(world, token, remaining);
            begun = true;
            while (attempts < limit && released < remaining) {
                int amount = EntityXPOrb.getXPSplit(remaining - released);
                if (amount <= 0 || amount > remaining - released)
                    throw new IllegalStateException(MaidLifecycleDiagnostics.MSG_INVALID_NATIVE_XP_SPLIT + amount);
                var orb = new EntityXPOrb(world, position.xCoord, position.yCoord, position.zCoord, amount);
                var publication = new MaidLifecycleDurability.Publication(orb, amount);
                publications.add(publication);
                attempts++;
                if (!world.spawnEntityInWorld(orb)) return attempts;
                publication.check(world, true);
                released += amount;
            }
            // 存盘事件也可改写后继；一旦进入目标持久化，未知失败不得撤掉屏障后再次投放
            settling = true;
            MaidLifecycleDurability.entities(world, publications, true);
            ledger.finishExperienceAttempt(world, token, remaining, released);
            committed = true;
            return attempts;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, key, failure, ledger.owner(token, MaidLifecycleData.Kind.FILM));
            return Math.max(attempts, MaidLifecycleTuning.TARGET_STACK_SIZE_THRESHOLD);
        } finally {
            try {
                if (begun && !committed && !settling) retract(world, ledger, token, publications);
            } finally {
                if (!committed) ledger.abandonAttempt(token, true);
                else ledger.rollback(token);
            }
        }
    }

    private static void retract(World world, MaidLifecycleData ledger, UUID token,
        ArrayList<MaidLifecycleDurability.Publication> publications) {
        boolean safe = true;
        for (var publication : publications) {
            try {
                publication.discard(world);
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                safe = false;
                reportCleanup(ledger, token, failure);
            }
        }
        if (safe) {
            try {
                MaidLifecycleDurability.entities(world, publications, false);
                ledger.cancelAttempt(world, token, true);
            } catch (Exception | LinkageError | AssertionError failure) {
                IntegrationGuard.throwIfFatal(failure);
                reportCleanup(ledger, token, failure);
            }
        }
    }

    private static void reportCleanup(MaidLifecycleData ledger, UUID token, Throwable failure) {
        IntegrationGuard.report(
            Side.SERVER,
            MaidLifecycleIds.ID_DEATH_EXPERIENCE + token,
            failure,
            ledger.owner(token, MaidLifecycleData.Kind.FILM));
        IntegrationGuard.report(
            Side.SERVER,
            MaidLifecycleIds.ID_DEATH_EXPERIENCE + token + MaidLifecycleIds.ID_CLEANUP,
            failure,
            ledger.owner(token, MaidLifecycleData.Kind.FILM));
    }
}
