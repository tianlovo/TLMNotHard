package com.tianluoqaq.tlmnh.common.combat;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 仅为本模组记录的箭恢复确切来源；无法核实时停止原实体，不猜射手或重写原始记录 */
public final class MaidProjectileSource {

    public static final String TAG = "TLMNHProjectileSource";
    public static final String STOPPED_TAG = "TLMNHProjectileStopped";

    private MaidProjectileSource() {}

    private static final class Source {

        final UUID shooter, owner;
        final int dimension;

        Source(UUID shooter, UUID owner, int dimension) {
            this.shooter = shooter;
            this.owner = owner;
            this.dimension = dimension;
        }
    }

    private static UUID uuid(String raw) {
        var value = UUID.fromString(raw);
        if (!value.toString()
            .equals(raw)) throw new IllegalArgumentException("Noncanonical projectile source UUID");
        return value;
    }

    private static UUID owner(EntityMaid maid) {
        String raw = maid.func_152113_b();
        return raw == null || raw.isEmpty() ? null : uuid(raw);
    }

    private static String key(Source source) {
        return "projectile_impact/" + source.shooter;
    }

    private static Source read(NBTBase raw) {
        if (!(raw instanceof NBTTagCompound tag) || !tag.hasKey("Schema", 3)
            || tag.getInteger("Schema") != 1
            || !tag.hasKey("Shooter", 8)
            || !tag.hasKey("Owner", 8)
            || !tag.hasKey("Dimension", 3))
            throw new IllegalArgumentException("Unsupported projectile source record retained unchanged");
        String player = tag.getString("Owner");
        return new Source(
            uuid(tag.getString("Shooter")),
            player.isEmpty() ? null : uuid(player),
            tag.getInteger("Dimension"));
    }

    public static boolean record(EntityArrow arrow, EntityMaid maid) {
        try {
            if (!(arrow instanceof MaidArrowSourceState state))
                throw new IllegalStateException("Native projectile source lifecycle unavailable");
            if (arrow.worldObj != maid.worldObj || arrow.worldObj == null)
                throw new IllegalStateException("Projectile source world changed before release");
            var source = new NBTTagCompound();
            source.setInteger("Schema", 1);
            source.setString(
                "Shooter",
                maid.getUniqueID()
                    .toString());
            var owner = owner(maid);
            source.setString("Owner", owner == null ? "" : owner.toString());
            source.setInteger("Dimension", maid.worldObj.provider.dimensionId);
            state.tlmnh$recordSource(source);
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            MaidProjectileImpactProtection.failure(maid, failure);
            return false;
        }
    }

    public static boolean beforeUpdate(EntityArrow arrow) {
        if (arrow.worldObj.isRemote || !(arrow instanceof MaidArrowSourceState state)) return true;
        if (state.tlmnh$source() == null && !state.tlmnh$sourceStopped()) return true;
        Source source = null;
        try {
            source = read(state.tlmnh$source());
            if (state.tlmnh$sourceStopped()) throw new IllegalStateException("Projectile remains stopped after reload");
            if (!(arrow instanceof MaidArrowImpactState) || !IntegrationGuard.available(Side.SERVER, key(source)))
                throw new IllegalStateException("Projectile impact capability unavailable; original entity retained");
            if (source.dimension != arrow.worldObj.provider.dimensionId)
                throw new IllegalStateException("Projectile source dimension changed");
            EntityMaid shooter;
            if (arrow.shootingEntity == null) shooter = loadedShooter(arrow, source.shooter);
            else if (arrow.shootingEntity instanceof EntityMaid maid) shooter = maid;
            else throw new IllegalStateException("Projectile source replaced by another shooter");
            if (shooter == null || shooter.isDead
                || shooter.worldObj != arrow.worldObj
                || !source.shooter.equals(shooter.getUniqueID())
                || arrow.worldObj.getEntityByID(shooter.getEntityId()) != shooter
                || !Objects.equals(source.owner, owner(shooter)))
                throw new IllegalStateException("Exact live projectile source cannot be verified");
            // 只绑定本模组原记录、原世界表中的同UUID/原主人实体，不构造或搜索未加载的射手
            arrow.shootingEntity = shooter;
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            state.tlmnh$stopSource();
            IntegrationGuard.report(
                Side.SERVER,
                source == null ? "projectile_source/" + arrow.getUniqueID() : key(source),
                failure,
                source == null ? null : source.owner);
            return false;
        }
    }

    /** 原生1.7无任意实体UUID索引；只在丢失引用时检查当前世界已加载表一次，失败即停止 */
    private static EntityMaid loadedShooter(EntityArrow arrow, UUID id) {
        EntityMaid found = null;
        for (Object raw : arrow.worldObj.loadedEntityList) {
            if (!(raw instanceof EntityMaid maid) || maid.isDead || !id.equals(maid.getUniqueID())) continue;
            if (found != null && found != maid)
                throw new IllegalStateException("Duplicate loaded projectile source UUID");
            found = maid;
        }
        return found;
    }
}
