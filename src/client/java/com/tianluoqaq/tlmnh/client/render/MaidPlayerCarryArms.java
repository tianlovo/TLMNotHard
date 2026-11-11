/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: client/event/CarryMaidHideArmEvent.java, mixin/client/HumanoidModelMixin.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.client.render;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.common.MinecraftForge;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 原生玩家抱持时抬起双臂，并按原版语义隐藏第一人称手与持物 */
@SideOnly(Side.CLIENT)
public final class MaidPlayerCarryArms {

    private static final MaidPlayerCarryArms INSTANCE = new MaidPlayerCarryArms();
    private static final Map<EntityPlayer, JsonObject> OBSERVED = new WeakHashMap<>();
    private static boolean registered;
    private static long hiddenHandFrames;

    private MaidPlayerCarryArms() {}

    public static void register() {
        if (registered) return;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        registered = true;
    }

    private static boolean carriesMaid(EntityPlayer player) {
        return player != null && player.riddenByEntity instanceof EntityMaid maid && maid.ridingEntity == player;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void hideFirstPersonHand(RenderHandEvent event) {
        if (!carriesMaid(Minecraft.getMinecraft().thePlayer)) return;
        event.setCanceled(true);
        if (DevelopmentFlags.isDevCommandsEnabled()) hiddenHandFrames++;
    }

    public static void apply(ModelBiped model, Entity entity) {
        if (!(entity instanceof EntityPlayer player) || !carriesMaid(player)) return;
        setArmRotations(model.bipedLeftArm, model.bipedRightArm);
        if (!DevelopmentFlags.isDevCommandsEnabled()) return;
        var observed = new JsonObject();
        observed.addProperty(
            "nativeModelClass",
            model.getClass()
                .getName());
        observed.addProperty("entityTick", player.ticksExisted);
        observed.addProperty("leftX", model.bipedLeftArm.rotateAngleX);
        observed.addProperty("leftY", model.bipedLeftArm.rotateAngleY);
        observed.addProperty("rightX", model.bipedRightArm.rotateAngleX);
        observed.addProperty("rightY", model.bipedRightArm.rotateAngleY);
        OBSERVED.put(player, observed);
    }

    public static void setArmRotations(ModelRenderer left, ModelRenderer right) {
        left.rotateAngleX = (float) Math.toRadians(-65);
        left.rotateAngleY = (float) Math.toRadians(10);
        right.rotateAngleX = (float) Math.toRadians(-65);
        right.rotateAngleY = (float) Math.toRadians(-10);
    }

    /** 没有实际ModelBiped调用时不伪造已应用角度，独立Gecko玩家模型可能不经此路径 */
    public static JsonObject observation() {
        var result = new JsonObject();
        var player = Minecraft.getMinecraft().thePlayer;
        result.addProperty("carryingMaid", carriesMaid(player));
        result.addProperty("hiddenFirstPersonHandFrames", hiddenHandFrames);
        var pose = OBSERVED.get(player);
        result.addProperty("actualNativeBipedObserved", pose != null);
        if (pose != null) result.add("lastNativeBipedPose", pose);
        return result;
    }
}
