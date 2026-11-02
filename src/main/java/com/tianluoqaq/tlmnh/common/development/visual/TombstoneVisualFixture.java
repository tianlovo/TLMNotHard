package com.tianluoqaq.tlmnh.common.development.visual;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidLifecycleData;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidTombstoneEntity;

/** 仅在自有开发虚空建立无账本库存的显示实体，不借用真实死亡凭证 */
public final class TombstoneVisualFixture {

    private static final String TAG = "TLMNHVisualTombstone";
    private static final java.util.Set<MaidTombstoneEntity> SPAWNING = java.util.Collections
        .newSetFromMap(new java.util.IdentityHashMap<>());

    public static boolean isSpawning(net.minecraft.entity.Entity entity) {
        return Boolean.getBoolean("tlmnh.devCommands") && SPAWNING.contains(entity);
    }

    private TombstoneVisualFixture() {}

    public static void set(EntityPlayerMP player, String action) {
        if (!Boolean.getBoolean("tlmnh.devCommands") || !(action.equals("open") || action.equals("clear")))
            throw new IllegalStateException("Unknown development tombstone fixture");
        AnimationLab.ownedSceneMaid(player);
        var world = player.worldObj;
        String owner = player.getUniqueID()
            .toString();
        var tracked = new ArrayList<MaidTombstoneEntity>();
        for (Object value : world.loadedEntityList) {
            if (!(value instanceof MaidTombstoneEntity tombstone) || !tombstone.getEntityData()
                .hasKey(TAG, 10)) continue;
            var data = tombstone.getEntityData()
                .getCompoundTag(TAG);
            if (!owner.equals(data.getString("Owner"))) continue;
            if (data.getInteger("Schema") != 1 || tombstone.getToken() == null
                || !owner.equals(String.valueOf(tombstone.getOwnerId()))
                || !tombstone.getToken()
                    .toString()
                    .equals(data.getString("Token")))
                throw new IllegalStateException("Changed tombstone fixture retained");
            for (var kind : MaidLifecycleData.Kind.values()) if (MaidLifecycleData.get(world)
                .owner(tombstone.getToken(), kind) != null)
                throw new IllegalStateException("A ledger now owns this fixture token; entity retained");
            tracked.add(tombstone);
        }
        for (var tombstone : tracked) world.removeEntity(tombstone);
        if (action.equals("open")) {
            for (int index = 0; index < 2; index++) {
                var tombstone = new MaidTombstoneEntity(world);
                var token = UUID.randomUUID();
                tombstone.initialize(token, player.getUniqueID(), index == 0 ? "TLMNHVisualTombstone" : "");
                tombstone.setPosition(index == 0 ? -2.5 : 3.5, 65, .5);
                var data = new NBTTagCompound();
                data.setInteger("Schema", 1);
                data.setString("Owner", owner);
                data.setString("Token", token.toString());
                tombstone.getEntityData()
                    .setTag(TAG, data);
                SPAWNING.add(tombstone);
                try {
                    if (!world.spawnEntityInWorld(tombstone)
                        || world.getEntityByID(tombstone.getEntityId()) != tombstone)
                        throw new IllegalStateException("Tombstone display fixture was rejected");
                } finally {
                    SPAWNING.remove(tombstone);
                }
                TLMNotHard.LOG.info(
                    "TLMNH_TOMBSTONE_VISUAL_ACTOR id={} uuid={} named={} ledger_inventory=false",
                    tombstone.getEntityId(),
                    tombstone.getUniqueID(),
                    index == 0);
            }
        }
        TLMNotHard.LOG.info(
            "TLMNH_TOMBSTONE_VISUAL_PASS action={} removed={} ledger_entry_created=false",
            action,
            tracked.size());
    }
}
