package com.tianluoqaq.tlmnh.compat.ysmu.client.mixin;

import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.tianluoqaq.tlmnh.compat.ysmu.client.animation.MaidPassengerAlias;

/** 可选提供方的匹配尾部补别名，原有实体ID成功匹配时完全沿原结果 */
@Pseudo
@Mixin(targets = "com.fox.ysmu.client.animation.condition.ConditionalEntityIdMatch", remap = false)
public abstract class MixinMaidPassengerAlias {

    @Shadow
    @Final
    private Set<String> ids;

    @Shadow
    protected abstract String idPrefix();

    @Shadow
    protected abstract Entity matchedEntity(EntityLivingBase entity);

    @Inject(
        method = "doTest(Lnet/minecraft/entity/EntityLivingBase;)Ljava/lang/String;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 1)
    private void tlmnh$maidPassengerAlias(EntityLivingBase entity, CallbackInfoReturnable<String> callback) {
        String original = callback.getReturnValue();
        if (entity == null || original == null || !original.isEmpty()) return;
        String result = MaidPassengerAlias.resolve(matchedEntity(entity), idPrefix(), ids, original);
        if (!original.equals(result)) callback.setReturnValue(result);
    }
}
