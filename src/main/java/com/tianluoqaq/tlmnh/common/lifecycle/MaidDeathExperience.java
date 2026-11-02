package com.tianluoqaq.tlmnh.common.lifecycle;

import java.util.UUID;

import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 复用死亡账本保管余额，确认实际原生经验球后逐份扣除；大int余额分轮投放 */
public final class MaidDeathExperience {

    private static final int MAX_ORBS_PER_PASS = 64;

    private MaidDeathExperience() {}

    public static void retryPending(World world) {
        if (world == null || world.isRemote) return;
        var ledger = MaidLifecycleData.get(world);
        int attempts = 0;
        for (var token : ledger.pendingDeathExperience(world.provider.dimensionId, world.getTotalWorldTime())) {
            var position = ledger.tombstonePosition(token);
            if (!IntegrationGuard.available(Side.SERVER, "death_experience/" + token) || position == null
                || !world.blockExists((int) Math.floor(position.xCoord), 5, (int) Math.floor(position.zCoord)))
                continue;
            while (attempts < MAX_ORBS_PER_PASS && ledger.remainingDeathExperience(token) > 0) {
                attempts++;
                if (!releaseOne(world, ledger, token)) break;
            }
            if (attempts >= MAX_ORBS_PER_PASS) return;
        }
    }

    private static boolean releaseOne(World world, MaidLifecycleData ledger, UUID token) {
        String key = "death_experience/" + token;
        if (!IntegrationGuard.available(Side.SERVER, key)) return false;
        var position = ledger.tombstonePosition(token);
        if (position == null
            || !world.blockExists((int) Math.floor(position.xCoord), 5, (int) Math.floor(position.zCoord)))
            return false;
        int remaining = ledger.reserveDeathExperience(token);
        if (remaining <= 0) return false;
        EntityXPOrb orb = null;
        UUID identity = null;
        int id = -1, amount = 0;
        boolean committed = false;
        try {
            amount = EntityXPOrb.getXPSplit(remaining);
            if (amount <= 0 || amount > remaining)
                throw new IllegalStateException("Invalid native XP split: " + amount);
            orb = new EntityXPOrb(world, position.xCoord, position.yCoord, position.zCoord, amount);
            identity = orb.getUniqueID();
            id = orb.getEntityId();
            boolean spawned = world.spawnEntityInWorld(orb);
            if (orb.worldObj != world || orb.getEntityId() != id
                || !identity.equals(orb.getUniqueID())
                || orb.xpValue != amount
                || orb.isDead) throw new IllegalStateException("Death XP spawn callback changed the owned orb");
            if (!spawned) return false;
            if (!MaidSpawnChecks.present(orb, world) || orb.getEntityId() != id
                || !identity.equals(orb.getUniqueID())
                || orb.xpValue != amount)
                throw new IllegalStateException("Death XP spawn did not retain the actual registered orb");
            ledger.finishDeathExperience(token, remaining, amount);
            committed = true;
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(Side.SERVER, key, failure, ledger.owner(token, MaidLifecycleData.Kind.FILM));
            return false;
        } finally {
            try {
                if (!committed && orb != null) discardOwned(world, ledger, token, orb, identity, id, amount);
            } finally {
                if (!committed) ledger.rollback(token);
            }
        }
    }

    private static void discardOwned(World world, MaidLifecycleData ledger, UUID token, EntityXPOrb orb, UUID identity,
        int id, int amount) {
        try {
            if (orb.worldObj != world || orb.getEntityId() != id
                || !identity.equals(orb.getUniqueID())
                || orb.xpValue != amount
                || world.getEntityByID(id) != null && world.getEntityByID(id) != orb)
                throw new IllegalStateException("Death XP orb changed ownership; leaving external state intact");
            world.removeEntity(orb);
        } catch (Exception | LinkageError | AssertionError failure) {
            IntegrationGuard.throwIfFatal(failure);
            IntegrationGuard.report(
                Side.SERVER,
                "death_experience/" + token + "/cleanup",
                failure,
                ledger.owner(token, MaidLifecycleData.Kind.FILM));
        }
    }
}
