package com.tianluoqaq.tlmnh.common.proxy;

import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.world.World;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.backpack.EmptyBackpackContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.config.MaidConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.inventory.container.task.TaskConfigContainer;
import com.github.tartaricacid.touhoulittlemaid.network.message.PlayMaidSoundMessage;
import com.github.tartaricacid.touhoulittlemaid.network.message.SendEffectMessage;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.interaction.MaidInteractions;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaid;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidModels;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidNaming;
import com.tianluoqaq.tlmnh.common.inventory.ContainerMaidSounds;
import com.tianluoqaq.tlmnh.common.inventory.MaidContainerAccess;
import com.tianluoqaq.tlmnh.common.network.MaidAttackSnapshot;
import com.tianluoqaq.tlmnh.common.network.MaidEffectNetwork;
import com.tianluoqaq.tlmnh.common.network.MaidNamingNetwork;
import com.tianluoqaq.tlmnh.common.network.MaidSoundNetwork;
import com.tianluoqaq.tlmnh.common.network.MaidTaskConfigNetwork;

import cpw.mods.fml.common.network.IGuiHandler;

/** 公共侧只创建服务端容器，不加载客户端 GUI 类型 */
public class CommonProxy implements IGuiHandler {

    public static final int GUI_MAID = 0;
    public static final int GUI_MAID_MODELS = 1;
    public static final int GUI_MAID_NAMING = 2;
    public static final int GUI_MAID_ORIGINAL = 3;
    public static final int GUI_MAID_ORIGINAL_MODELS = 4;
    public static final int GUI_MAID_SOUNDS = 5;
    public static final int GUI_MAID_CONFIG = 6;
    public static final int GUI_MAID_ATTACK_CONFIG = 7;
    public static final int GUI_MAID_DEFAULT_TASK_CONFIG = 8;

    public void init() {
        MaidNamingNetwork.init();
        MaidSoundNetwork.init();
        MaidEffectNetwork.init();
        MaidTaskConfigNetwork.init();
    }

    public void enqueueMaidSound(PlayMaidSoundMessage message) {}

    public void enqueueMaidEffects(SendEffectMessage message) {}

    public void enqueueMaidAttackSnapshot(MaidAttackSnapshot message) {}

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        if ((id != GUI_MAID && id != GUI_MAID_MODELS
            && id != GUI_MAID_NAMING
            && id != GUI_MAID_ORIGINAL
            && id != GUI_MAID_ORIGINAL_MODELS
            && id != GUI_MAID_SOUNDS
            && id != GUI_MAID_CONFIG
            && id != GUI_MAID_ATTACK_CONFIG
            && id != GUI_MAID_DEFAULT_TASK_CONFIG) || world == null || player == null || world != player.worldObj)
            return null;
        var entity = world.getEntityByID(entityId);
        if (!(entity instanceof EntityMaid maid)) return null;
        if (!MaidContainerAccess.canInteract(player, player, maid)) return null;
        if ((id == GUI_MAID || id == GUI_MAID_MODELS) && !DevelopmentFlags.isDevCommandsEnabled()) return null;
        // 原openMaidGui拒绝睡眠时新开普通菜单；命名牌有独立交互，继续沿其原条件
        if (id != GUI_MAID_NAMING && maid.hasPose(MaidPose.SLEEPING)) return null;
        var factory = maidMenuFactory(id, player, maid, unusedY, unusedZ);
        if (factory == null) return null;
        if (id != GUI_MAID_NAMING) maid.getNavigator()
            .clearPathEntity();
        return factory.get();
    }

    private static Supplier<Container> maidMenuFactory(int id, EntityPlayer player, EntityMaid maid, int unusedY,
        int unusedZ) {
        if (id == GUI_MAID_ATTACK_CONFIG || id == GUI_MAID_DEFAULT_TASK_CONFIG) {
            long session = ((long) unusedY << 32) | (unusedZ & 0xFFFFFFFFL);
            boolean attack = id == GUI_MAID_ATTACK_CONFIG;
            if (session == 0 || player.inventory.getItemStack() != null
                || !maid.worldObj.isRemote && (maid.getTask() != MaidTask.IDLE) != attack) return null;
            return () -> new TaskConfigContainer(player.inventory, maid, session, attack);
        }
        if (id == GUI_MAID_CONFIG)
            return player.inventory.getItemStack() == null ? () -> new MaidConfigContainer(player.inventory, maid)
                : null;
        if (id == GUI_MAID_SOUNDS)
            return player.inventory.getItemStack() == null ? () -> new ContainerMaidSounds(player.inventory, maid)
                : null;
        if (id == GUI_MAID_ORIGINAL) return () -> new EmptyBackpackContainer(player.inventory, maid);
        if (id == GUI_MAID_ORIGINAL_MODELS)
            return player.inventory.getItemStack() == null ? () -> new ContainerMaidModels(player.inventory, maid, true)
                : null;
        if (id == GUI_MAID_NAMING) {
            long session = ((long) unusedY << 32) | (unusedZ & 0xFFFFFFFFL);
            if (session == 0 || !MaidInteractions.canInteract(maid, player)
                || !MaidInteractions.isMainHand(player)
                || player.inventory.getItemStack() != null
                || !ContainerMaidNaming.isUnwrittenTag(player.getCurrentEquippedItem())) return null;
            return () -> new ContainerMaidNaming(player.inventory, maid, session);
        }
        if (id == GUI_MAID_MODELS)
            return player.inventory.getItemStack() == null ? () -> new ContainerMaidModels(player.inventory, maid)
                : null;
        return () -> new ContainerMaid(player.inventory, maid);
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int entityId, int unusedY,
        int unusedZ) {
        return null;
    }
}
