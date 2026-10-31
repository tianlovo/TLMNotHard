package com.tianluoqaq.tlmnh.common.ai.perception;

import java.util.Objects;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.common.util.FakePlayer;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.ai.MaidTelemetryChannel;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyContext;

/** 同一策略决策帧只查询一次主人；缓存的在线结果不授予动作权限 */
public final class MaidOwnerSensor {

    private final EntityMaid maid;
    private final MaidTelemetryChannel telemetry;
    private World world;
    private String ownerId;
    private MaidPolicyContext context;
    private MaidPolicyContext.Token token;
    private long frame, observedAt, scans;
    private boolean queried;
    private EntityPlayer owner;

    public MaidOwnerSensor(EntityMaid maid, MaidTelemetryChannel telemetry) {
        this.maid = Objects.requireNonNull(maid, "maid");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public void beginFrame(MaidPolicyContext context, long frame) {
        clear();
        if (context == null || !context.getMaidId()
            .equals(maid.getUniqueID()) || !context.isCurrent(context.getToken())) return;
        this.context = context;
        token = context.getToken();
        world = maid.worldObj;
        ownerId = maid.func_152113_b();
        this.frame = frame;
        observedAt = world.getTotalWorldTime();
    }

    /** 阴性结果也只限本帧；已找到的主人离线、换世界或进入旁观时立即拒绝 */
    public EntityPlayer findOwner() {
        if (world == null || world != maid.worldObj
            || world.isRemote
            || observedAt != world.getTotalWorldTime()
            || !Objects.equals(ownerId, maid.func_152113_b())
            || !context.isCurrent(token)) return null;
        if (!queried) {
            queried = true;
            scans++;
            int inspected = 0;
            for (Object value : world.playerEntities) {
                inspected++;
                if (value instanceof EntityPlayer player && isValid(maid, player)) {
                    owner = player;
                    break;
                }
            }
            telemetry.scan("shared_owner", world.playerEntities.size(), inspected, "world_player_list;frame=" + frame);
        }
        if (!isValid(maid, owner)) owner = null;
        return owner;
    }

    public static boolean isValid(EntityMaid maid, EntityPlayer player) {
        return player != null && !(player instanceof FakePlayer)
            && maid.isOwner(player)
            && player.isEntityAlive()
            && !player.isDead
            && player.worldObj == maid.worldObj
            && player.dimension == maid.dimension
            && maid.worldObj.playerEntities.contains(player)
            && (!(player instanceof EntityPlayerMP server) || server.theItemInWorldManager.getGameType()
                .getID() != 3);
    }

    public void clear() {
        world = null;
        ownerId = null;
        context = null;
        token = null;
        owner = null;
        queried = false;
    }

    public long getScans() {
        return scans;
    }
}
