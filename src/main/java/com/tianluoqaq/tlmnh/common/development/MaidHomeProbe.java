package com.tianluoqaq.tlmnh.common.development;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidActionRunner.Owner;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.life.MaidHomeSettings;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 在原生世界手动调度 Root，验证家园边界与生命周期，不代替自然行为或客户端界面验收 */
public final class MaidHomeProbe {

    private MaidHomeProbe() {}

    public static void run(WorldServer world) {
        require(Boolean.getBoolean("tlmnh.devCommands") && !AnimationLab.isLab(world), "test overworld required");
        var spawn = world.getSpawnPoint();
        int x = (spawn.posX >> 4 << 4) + 4, y = 180, z = (spawn.posZ >> 4 << 4) + 4;
        for (int dx = -2; dx <= 17; dx++) for (int dz = -2; dz <= 6; dz++) {
            require(
                world.getChunkProvider()
                    .chunkExists((x + dx) >> 4, (z + dz) >> 4),
                "loaded fixture chunks required");
            for (int dy = -1; dy <= 3; dy++)
                require(world.isAirBlock(x + dx, y + dy, z + dz), "empty fixture volume required");
        }
        long day = world.getWorldTime();
        var existing = new HashSet<>(world.loadedEntityList);
        var maid = new EntityMaid(world);
        var copy = new EntityMaid(world);
        var near = new EntityZombie(world);
        var far = new EntityZombie(world);
        var owner = player(world, "[TLMNH-Home]");
        var stranger = player(world, "[TLMNH-Other]");
        var brain = maid.getBrainCoordinator();
        try {
            for (int dx = -2; dx <= 17; dx++) for (int dz = -2; dz <= 6; dz++)
                require(world.setBlock(x + dx, y - 1, z + dz, Blocks.stone, 0, 2), "fixture floor");
            world.setWorldTime(0);
            owner.setPosition(x + 1.5, y, z + 0.5);
            stranger.setPosition(x + 1.5, y, z + 0.5);
            maid.setPosition(x + 0.5, y, z + 0.5);
            maid.onGround = true;
            require(maid.bindTo(owner.getUniqueID()) && world.spawnEntityInWorld(maid), "registered owner-bound maid");
            brain.setSuspended(true);
            maid.setSchedule(MaidSchedule.DAY);
            world.playerEntities.add(owner);
            var container = new ContainerMaid(owner.inventory, maid);
            var denied = new ContainerMaid(stranger.inventory, maid);
            require(
                !denied.enchantItem(stranger, ContainerMaid.ACTION_HOME) && !maid.getHomeArea()
                    .enabled(),
                "non-owner home command refused");
            action(container, owner, maid, ContainerMaid.ACTION_HOME);
            maid.setPosition(x + 8.5, y, z + 0.5);
            action(container, owner, maid, ContainerMaid.ACTION_HOME_IDLE);
            maid.setPosition(x + 2.5, y, z + 3.5);
            action(container, owner, maid, ContainerMaid.ACTION_HOME_REST);
            var saved = new NBTTagCompound();
            maid.writeToNBT(saved);
            copy.readFromNBT(saved);
            require(
                copy.getHomeArea()
                    .settings()
                    .encode()
                    .equals(
                        maid.getHomeArea()
                            .settings()
                            .encode()),
                "native entity NBT preserves three centers and home flag");
            var future = (NBTTagCompound) saved.copy();
            future.getCompoundTag("TLMNHHome")
                .setInteger("Schema", 99);
            future.getCompoundTag("TLMNHHome")
                .setString("FutureValue", "preserve");
            copy.readFromNBT(future);
            var preserved = new NBTTagCompound();
            copy.writeToNBT(preserved);
            require(
                !copy.getHomeArea()
                    .enabled() && future.getCompoundTag("TLMNHHome")
                        .equals(preserved.getCompoundTag("TLMNHHome")),
                "unknown home schema disabled and preserved");

            world.playerEntities.remove(owner);
            brain.setSuspended(false);
            maid.setPosition(x + 13.5, y, z + 0.5);
            brain.updateTask();
            require(
                brain.getActionRunner()
                    .getOwner() == Owner.HOME
                    && maid.getNavigator()
                        .getPath() != null,
                "offline owner does not prevent actual native return path");
            maid.setSitting(true);
            brain.updateTask();
            require(
                brain.getActionRunner()
                    .getOwner() == null && maid.getNavigator()
                        .noPath(),
                "sit cancels return path");
            maid.setSitting(false);
            require(
                world.setBlock(x + 2, y, z + 3, MaidLifeContent.maidBed, 0, 2)
                    && world.setBlock(x + 2, y, z + 4, MaidLifeContent.maidBed, 8, 2),
                "paired home bed");
            var bed = (TileEntityMaidBed) world.getTileEntity(x + 2, y, z + 4);
            maid.setPosition(x + 3.5, y, z + 4.5);
            world.setWorldTime(16000);
            drive(maid, 30);
            require(
                brain.getLifeController()
                    .isSleeping() && bed.isOwnedBy(maid),
                "offline home sleep uses sleep center");
            var oldSleep = brain.getActionRunner()
                .getCurrentRun();
            world.setWorldTime(12000);
            brain.updateTask();
            require(
                !brain.getLifeController()
                    .isSleeping() && bed.available(maid)
                    && oldSleep.isCancelled()
                    && brain.getActionRunner()
                        .getOwner() == Owner.HOME
                    && maid.getNavigator()
                        .getPath() != null,
                "schedule change releases old bed before returning to idle center");

            world.setWorldTime(0);
            maid.setPosition(x + 1.5, y, z + 1.5);
            require(
                world.setBlock(x, y, z + 1, MaidLifeContent.snackCabinet, 0, 2)
                    && world.setBlock(x, y + 1, z + 1, Blocks.cake, 0, 2),
                "home snack setup");
            int bites = brain.getLifeController()
                .getBites();
            drive(maid, 40);
            require(
                brain.getLifeController()
                    .getBites() == bites + 1 && world.getBlockMetadata(x, y + 1, z + 1) == 1,
                "offline home snack uses current activity area");
            world.setBlockToAir(x, y + 1, z + 1);
            maid.setTask(MaidTask.ATTACK);
            maid.setCurrentItemOrArmor(0, new ItemStack(Items.diamond_sword));
            near.setPosition(x + 4.5, y, z + 0.5);
            far.setPosition(x + 13.5, y, z + 0.5);
            require(world.spawnEntityInWorld(near) && world.spawnEntityInWorld(far), "actual combat targets");
            require(
                MaidCombatTargeting.canAttack(maid, null, near) && !MaidCombatTargeting.canAttack(maid, null, far),
                "offline combat accepts in-area hostile and refuses outside hostile");
            drive(maid, 20);
            require(
                maid.getAttackTarget() == near && brain.getActionRunner()
                    .getOwner() == Owner.COMBAT,
                "home combat acquires shared control");
            world.setWorldTime(12000);
            brain.updateTask();
            require(
                maid.getAttackTarget() == null && !MaidCombatTargeting.canAttack(maid, null, near),
                "idle activity cancels work combat");
            var old = maid.getHomeArea()
                .settings();
            var foreign = new NBTTagCompound();
            maid.writeToNBT(foreign);
            foreign.getCompoundTag("TLMNHHome")
                .setString(
                    "Settings",
                    new MaidHomeSettings(true, true, world.provider.dimensionId + 1000000, old.work, old.idle, old.rest)
                        .encode());
            maid.readFromNBT(foreign);
            int chunks = world.theChunkProviderServer.loadedChunks.size();
            brain.updateTask();
            require(
                !maid.getHomeArea()
                    .containsMaid()
                    && brain.getActionRunner()
                        .getOwner() == null
                    && chunks == world.theChunkProviderServer.loadedChunks.size(),
                "foreign home dimension fails closed without loading chunks");
        } finally {
            brain.setSuspended(true);
            maid.setCurrentItemOrArmor(0, null);
            maid.setDead();
            copy.setDead();
            near.setDead();
            far.setDead();
            world.removeEntity(maid);
            world.removeEntity(near);
            world.removeEntity(far);
            world.playerEntities.remove(owner);
            owner.setDead();
            stranger.setDead();
            for (int dx = -2; dx <= 17; dx++) for (int dz = -2; dz <= 6; dz++)
                for (int dy = 3; dy >= -1; dy--) world.setBlockToAir(x + dx, y + dy, z + dz);
            for (Object value : new ArrayList<>(world.loadedEntityList))
                if (value instanceof EntityItem item && !existing.contains(item)
                    && Math.abs(item.posX - x) <= 20
                    && Math.abs(item.posY - y) <= 5
                    && Math.abs(item.posZ - z) <= 9) {
                        item.setDead();
                        world.removeEntity(item);
                    }
            world.setWorldTime(day);
        }
        TLMNotHard.LOG.info(
            "TLMNH_HOME_TEST_PASS owner_commands=checked centers=3 native_nbt=preserved future_schema=preserved"
                + " offline_return=native_path sit=cancelled offline_sleep=bed_owned schedule_change=old_bed_released"
                + " offline_snack=native_bite offline_combat=area_filtered foreign_dimension=no_force_load"
                + " fixture=manual_root_dispatch natural_tick=unverified client_gui=unverified teleport=not_implemented");
    }

    private static EntityPlayerMP player(WorldServer world, String name) {
        var player = new EntityPlayerMP(
            MinecraftServer.getServer(),
            world,
            new GameProfile(UUID.randomUUID(), name),
            new ItemInWorldManager(world));
        player.theItemInWorldManager.setGameType(GameType.SURVIVAL);
        return player;
    }

    private static void action(ContainerMaid container, EntityPlayerMP owner, EntityMaid maid, int action) {
        maid.ticksExisted++;
        require(container.enchantItem(owner, action), "owner container action " + action);
    }

    private static void drive(EntityMaid maid, int count) {
        for (int i = 0; i < count; i++) maid.getBrainCoordinator()
            .updateTask();
    }

    private static void require(boolean value, String reason) {
        if (!value) throw new IllegalStateException("TLMNH home probe failed: " + reason);
    }
}
