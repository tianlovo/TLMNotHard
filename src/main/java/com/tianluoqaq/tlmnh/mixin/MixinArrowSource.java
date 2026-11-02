package com.tianluoqaq.tlmnh.mixin;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByte;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.tianluoqaq.tlmnh.common.combat.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.MaidProjectileSource;

/** 独立于可选碰撞体形状的原生生命周期边界；停止时保留实体及原始NBT */
@Mixin(EntityArrow.class)
public abstract class MixinArrowSource implements MaidArrowSourceState {

    @Unique
    private NBTBase tlmnh$source;
    @Unique
    private NBTBase tlmnh$sourceStopped;

    @Inject(method = "readEntityFromNBT", at = @At("TAIL"))
    private void tlmnh$readSource(NBTTagCompound tag, CallbackInfo ci) {
        tlmnh$source = tag.hasKey(MaidProjectileSource.TAG) ? tag.getTag(MaidProjectileSource.TAG)
            .copy() : null;
        tlmnh$sourceStopped = tag.hasKey(MaidProjectileSource.STOPPED_TAG)
            ? tag.getTag(MaidProjectileSource.STOPPED_TAG)
                .copy()
            : null;
    }

    @Inject(method = "writeEntityToNBT", at = @At("TAIL"))
    private void tlmnh$writeSource(NBTTagCompound tag, CallbackInfo ci) {
        if (tlmnh$source != null) tag.setTag(MaidProjectileSource.TAG, tlmnh$source.copy());
        if (tlmnh$sourceStopped != null) tag.setTag(MaidProjectileSource.STOPPED_TAG, tlmnh$sourceStopped.copy());
    }

    @Inject(method = "onUpdate", at = @At("HEAD"), cancellable = true)
    private void tlmnh$checkSource(CallbackInfo ci) {
        if (!MaidProjectileSource.beforeUpdate((EntityArrow) (Object) this)) ci.cancel();
    }

    @Override
    public NBTBase tlmnh$source() {
        return tlmnh$source;
    }

    @Override
    public void tlmnh$recordSource(NBTBase source) {
        if (tlmnh$source != null || tlmnh$sourceStopped != null)
            throw new IllegalStateException("Existing projectile source must not be overwritten");
        tlmnh$source = source.copy();
    }

    @Override
    public boolean tlmnh$sourceStopped() {
        return tlmnh$sourceStopped != null;
    }

    @Override
    public void tlmnh$stopSource() {
        if (tlmnh$sourceStopped == null) tlmnh$sourceStopped = new NBTTagByte((byte) 1);
    }
}
