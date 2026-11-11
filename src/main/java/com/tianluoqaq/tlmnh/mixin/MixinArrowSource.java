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

import com.tianluoqaq.tlmnh.common.combat.projectile.MaidArrowSourceState;
import com.tianluoqaq.tlmnh.common.combat.projectile.MaidProjectileSource;
import com.tianluoqaq.tlmnh.common.combat.projectile.ProjectileCombatIds;
import com.tianluoqaq.tlmnh.common.combat.projectile.ProjectileCombatNbtKeys;

/** 独立于可选碰撞体形状的原生生命周期边界；停止时保留实体及原始NBT */
@Mixin(EntityArrow.class)
public abstract class MixinArrowSource implements MaidArrowSourceState {

    @Unique
    private NBTBase tlmnh$source;
    @Unique
    private NBTBase tlmnh$sourceStopped;
    @Unique
    private NBTBase tlmnh$sourceHandoff;

    @Inject(method = "readEntityFromNBT", at = @At("TAIL"))
    private void tlmnh$readSource(NBTTagCompound tag, CallbackInfo ci) {
        tlmnh$sourceHandoff = tag.hasKey(ProjectileCombatIds.HANDOFF_TAG) ? tag.getTag(ProjectileCombatIds.HANDOFF_TAG)
            .copy() : null;
        tlmnh$source = tag.hasKey(ProjectileCombatNbtKeys.TAG) ? tag.getTag(ProjectileCombatNbtKeys.TAG)
            .copy() : null;
        tlmnh$sourceStopped = tag.hasKey(ProjectileCombatIds.STOPPED_TAG) ? tag.getTag(ProjectileCombatIds.STOPPED_TAG)
            .copy() : null;
    }

    @Inject(method = "writeEntityToNBT", at = @At("TAIL"))
    private void tlmnh$writeSource(NBTTagCompound tag, CallbackInfo ci) {
        MaidProjectileSource.beforeSave((EntityArrow) (Object) this);
        if (tlmnh$source != null) tag.setTag(ProjectileCombatNbtKeys.TAG, tlmnh$source.copy());
        if (tlmnh$sourceStopped != null) tag.setTag(ProjectileCombatIds.STOPPED_TAG, tlmnh$sourceStopped.copy());
        if (tlmnh$sourceHandoff != null) tag.setTag(ProjectileCombatIds.HANDOFF_TAG, tlmnh$sourceHandoff.copy());
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
        if (tlmnh$source != null || tlmnh$sourceStopped != null || tlmnh$sourceHandoff != null)
            throw new IllegalStateException("Existing projectile source must not be overwritten");
        tlmnh$source = source.copy();
    }

    @Override
    public NBTBase tlmnh$sourceHandoff() {
        return tlmnh$sourceHandoff;
    }

    @Override
    public void tlmnh$handoffSource(NBTBase handoff) {
        if (tlmnh$source == null || tlmnh$sourceStopped != null || tlmnh$sourceHandoff != null || handoff == null)
            throw new IllegalStateException("Existing projectile source state must not be overwritten during handoff");
        tlmnh$sourceHandoff = handoff.copy();
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
