package com.tianluoqaq.tlmnh.common.combat.projectile;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 仅为本模组记录的箭恢复确切来源；无法核实时停止原实体，不猜射手或重写原始记录 */
public final class MaidProjectileSource {

    public static final String TAG = "TLMNHProjectileSource";
    public static final String STOPPED_TAG = "TLMNHProjectileStopped";
    public static final String HANDOFF_TAG = "TLMNHProjectileHandoff";

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
        if (!(raw instanceof NBTTagCompound tag) || !tag.hasKey("Schema", Constants.NBT.TAG_INT)
            || tag.getInteger("Schema") != 1
            || !tag.hasKey("Shooter", Constants.NBT.TAG_STRING)
            || !tag.hasKey("Owner", Constants.NBT.TAG_STRING)
            || !tag.hasKey("Dimension", Constants.NBT.TAG_INT))
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
        if (state.tlmnh$source() == null && !state.tlmnh$sourceStopped() && state.tlmnh$sourceHandoff() == null)
            return true;
        Source source = null;
        boolean sourceRevoked = false;
        try {
            source = read(state.tlmnh$source());
            if (!state.tlmnh$sourceStopped() && state.tlmnh$sourceHandoff() != null) {
                sourceRevoked = true;
                validateHandoff(state.tlmnh$sourceHandoff(), source);
                // This is historical provenance only. Native reload may lose its new transient shooter;
                // never bind it back to the old maid or invent restoration authority for the new player.
                return true;
            }
            if (recordPlayerHandoff(arrow, state, source)) return true;
            if (!(arrow instanceof MaidArrowImpactState) || !IntegrationGuard.available(Side.SERVER, key(source)))
                throw new IllegalStateException("Projectile impact capability unavailable; original entity retained");
            if (state.tlmnh$sourceStopped()) {
                sourceRevoked = true;
                throw new IllegalStateException("Projectile remains stopped after reload");
            }
            if (source.dimension != arrow.worldObj.provider.dimensionId) {
                sourceRevoked = true;
                throw new IllegalStateException("Projectile source dimension changed");
            }
            EntityMaid shooter;
            if (arrow.shootingEntity == null) shooter = loadedShooter(arrow, source.shooter);
            else if (arrow.shootingEntity instanceof EntityMaid maid) shooter = maid;
            else {
                sourceRevoked = true;
                throw new IllegalStateException(
                    "Projectile source replaced by an unverified shooter: " + arrow.shootingEntity.getClass()
                        .getName());
            }
            if (shooter == null || shooter.isDead
                || shooter.worldObj != arrow.worldObj
                || !source.shooter.equals(shooter.getUniqueID())
                || arrow.worldObj.getEntityByID(shooter.getEntityId()) != shooter
                || !Objects.equals(source.owner, owner(shooter))) {
                sourceRevoked = true;
                throw new IllegalStateException("Exact live projectile source cannot be verified");
            }
            // 只绑定本模组原记录、原世界表中的同UUID/原主人实体，不构造或搜索未加载的射手
            arrow.shootingEntity = shooter;
            return true;
        } catch (Exception | LinkageError | AssertionError failure) {
            state.tlmnh$stopSource();
            IntegrationGuard.report(
                Side.SERVER,
                source == null || sourceRevoked ? "projectile_source/" + arrow.getUniqueID() : key(source),
                failure,
                source == null ? null : source.owner);
            return false;
        }
    }

    /** Capture a transfer in the current native update before a same-tick save loses its transient shooter. */
    public static void beforeSave(EntityArrow arrow) {
        if (arrow.worldObj == null || arrow.worldObj.isRemote
            || !(arrow instanceof MaidArrowSourceState state)
            || state.tlmnh$source() == null
            || state.tlmnh$sourceStopped()
            || state.tlmnh$sourceHandoff() != null
            || !(arrow.shootingEntity instanceof EntityPlayer)) return;
        Source source = null;
        try {
            source = read(state.tlmnh$source());
            if (!recordPlayerHandoff(arrow, state, source)) throw new IllegalStateException(
                "Unverified player projectile takeover cannot lose its transient identity on save");
        } catch (Exception | LinkageError | AssertionError failure) {
            state.tlmnh$stopSource();
            IntegrationGuard.report(
                Side.SERVER,
                "projectile_source/" + arrow.getUniqueID(),
                failure,
                source == null ? null : source.owner);
        }
    }

    private static boolean recordPlayerHandoff(EntityArrow arrow, MaidArrowSourceState state, Source source) {
        if (state.tlmnh$sourceStopped() || state.tlmnh$sourceHandoff() != null
            || source.dimension != arrow.worldObj.provider.dimensionId
            || !(arrow.shootingEntity instanceof EntityPlayer player)
            || player.isDead
            || player.worldObj != arrow.worldObj
            || arrow.worldObj.getEntityByID(player.getEntityId()) != player
            || !arrow.worldObj.playerEntities.contains(player)
            || source.shooter.equals(player.getUniqueID())) return false;
        var handoff = new NBTTagCompound();
        handoff.setInteger("Schema", 1);
        handoff.setString(
            "Player",
            player.getUniqueID()
                .toString());
        handoff.setInteger("Dimension", source.dimension);
        state.tlmnh$handoffSource(handoff);
        // The registered player owns this native arrow; preserve its shooter, physics and complete old source.
        return true;
    }

    private static void validateHandoff(NBTBase raw, Source source) {
        if (!(raw instanceof NBTTagCompound tag) || !tag.hasKey("Schema", Constants.NBT.TAG_INT)
            || tag.getInteger("Schema") != 1
            || !tag.hasKey("Player", Constants.NBT.TAG_STRING)
            || !tag.hasKey("Dimension", Constants.NBT.TAG_INT)
            || tag.getInteger("Dimension") != source.dimension
            || source.shooter.equals(uuid(tag.getString("Player"))))
            throw new IllegalArgumentException("Unsupported projectile handoff retained unchanged");
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
