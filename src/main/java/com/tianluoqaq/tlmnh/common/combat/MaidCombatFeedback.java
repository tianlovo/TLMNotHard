package com.tianluoqaq.tlmnh.common.combat;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.util.ChatComponentTranslation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 无准入武器时给在线主人一次提示，预算排队不误报为武器不支持 */
public final class MaidCombatFeedback {

    private static final Map<EntityMaid, Notice> NOTICES = new WeakHashMap<>();

    private MaidCombatFeedback() {}

    public static void unsupported(EntityMaid maid) {
        if (maid.worldObj.isRemote || !(maid.getOwner() instanceof EntityPlayerMP owner)
            || owner.playerNetServerHandler == null
            || owner.worldObj != maid.worldObj) return;
        var held = maid.getHeldItem();
        if (held == null) return;
        var profile = maid.getCombatWeaponProfile();
        if (profile.supported() || profile.reason.equals(MaidCombatIds.ID_ITEM_BUDGET_PENDING)) return;
        var previous = NOTICES.get(maid);
        if (previous != null && previous.item == held.getItem()
            && previous.metadata == held.getItemDamage()
            && previous.owner.equals(owner.getUniqueID())) return;
        NOTICES.put(maid, new Notice(held.getItem(), held.getItemDamage(), owner.getUniqueID()));
        owner.addChatMessage(
            new ChatComponentTranslation(MaidCombatIds.UNSUPPORTED_WEAPON, maid.getCommandSenderName()));
    }

    public static void supported(EntityMaid maid) {
        NOTICES.remove(maid);
    }

    private static final class Notice {

        final Item item;
        final int metadata;
        final UUID owner;

        Notice(Item item, int metadata, UUID owner) {
            this.item = item;
            this.metadata = metadata;
            this.owner = owner;
        }
    }
}
