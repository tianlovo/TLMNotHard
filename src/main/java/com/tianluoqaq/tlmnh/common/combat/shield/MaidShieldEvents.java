package com.tianluoqaq.tlmnh.common.combat.shield;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 与BG采用相同的LivingHurt阶段，不给女仆伪造玩家事件或玩家身份 */
public final class MaidShieldEvents {

    private static boolean registered;

    private MaidShieldEvents() {}

    public static void register() {
        if (registered) throw new IllegalStateException("Maid shield events already registered");
        MinecraftForge.EVENT_BUS.register(new MaidShieldEvents());
        registered = true;
    }

    @SubscribeEvent
    public void hurt(LivingHurtEvent event) {
        if (event.entityLiving instanceof EntityMaid maid && maid.worldObj != null && !maid.worldObj.isRemote) {
            var controller = maid.getShieldController();
            if (controller != null) controller.onHurt(event);
        }
    }
}
