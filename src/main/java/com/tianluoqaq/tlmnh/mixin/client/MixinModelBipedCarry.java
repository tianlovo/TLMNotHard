/*
 * Adapted from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: mixin/client/HumanoidModelMixin.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 */
package com.tianluoqaq.tlmnh.mixin.client;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.client.render.MaidPlayerCarryArms;

@Mixin(ModelBiped.class)
public abstract class MixinModelBipedCarry {

    @Inject(method = "setRotationAngles(FFFFFFLnet/minecraft/entity/Entity;)V", at = @At("TAIL"), require = 1)
    private void tlmnh$carryArms(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw,
        float headPitch, float scale, Entity entity, CallbackInfo callback) {
        MaidPlayerCarryArms.apply((ModelBiped) (Object) this, entity);
    }
}
