package com.tianluoqaq.tlmnh.common.work.notify;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;

/**
 * 女仆身份缺件聊天提醒器：
 * 条件齐备时立即复位；切换工作模式或缺件集合变化时立即以女仆名字向在线主人播报；同一缺件集合每 1200 tick 重发一次
 */
public final class MaidMissingConditionNotifier {

    public interface OwnerChatSink {

        boolean sendToOnlineOwner(UUID ownerUuid, String maidDisplayName, String modeKey,
            List<MissingConditionEntry> missing);
    }

    public static final OwnerChatSink SERVER_OWNER_SINK = (ownerUuid, maidDisplayName, modeKey, missing) -> {
        if (ownerUuid == null || missing == null || missing.isEmpty()) return false;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return false;
        EntityPlayerMP owner = findOnlineOwner(server, ownerUuid);
        if (owner == null) return false;
        IChatComponent detail = new ChatComponentText("");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                detail.appendSibling(new ChatComponentTranslation(MissingConditionIds.ITEM_SEPARATOR));
            }
            detail.appendSibling(missing.get(i).toChatComponent());
        }
        IChatComponent message = new ChatComponentTranslation(
            MissingConditionIds.CHAT_TEMPLATE,
            new ChatComponentText(maidDisplayName),
            new ChatComponentTranslation(modeKey),
            detail);
        owner.addChatMessage(message);
        return true;
    };

    private MaidTask lastTask;
    private String lastSignature = "";
    private long lastNotifiedWorldTime = MissingConditionTuning.NEVER_NOTIFIED_TIME;

    public void reset() {
        lastTask = null;
        lastSignature = "";
        lastNotifiedWorldTime = MissingConditionTuning.NEVER_NOTIFIED_TIME;
    }

    public String lastSignature() {
        return lastSignature;
    }

    public boolean reportHealthy() {
        boolean changed = !lastSignature.isEmpty();
        lastSignature = "";
        lastNotifiedWorldTime = MissingConditionTuning.NEVER_NOTIFIED_TIME;
        return changed;
    }

    public boolean reportMissingForMaid(EntityMaid maid, MaidTask mode, String modeTranslationKey,
        List<MissingConditionEntry> missing) {
        if (maid == null || maid.worldObj == null || maid.worldObj.isRemote) return false;
        UUID ownerUuid = parseOwnerUuid(maid.func_152113_b());
        String maidName = resolveMaidDisplayName(maid);
        return evaluateAndNotify(
            maid.worldObj.getTotalWorldTime(),
            ownerUuid,
            maidName,
            mode,
            modeTranslationKey,
            missing,
            SERVER_OWNER_SINK);
    }

    public boolean evaluateAndNotify(long worldTime, UUID ownerUuid, String maidDisplayName, MaidTask mode,
        String modeTranslationKey, List<MissingConditionEntry> missing, OwnerChatSink sink) {
        if (missing == null || missing.isEmpty()) {
            reportHealthy();
            lastTask = mode;
            return false;
        }
        String sig = computeSignature(missing);
        boolean signatureChanged = mode != lastTask || !sig.equals(lastSignature);
        boolean cooldownElapsed = worldTime - lastNotifiedWorldTime >= MissingConditionTuning.REPEAT_COOLDOWN_TICKS;
        if (!signatureChanged && !cooldownElapsed) {
            return false;
        }
        OwnerChatSink activeSink = sink != null ? sink : SERVER_OWNER_SINK;
        boolean delivered = activeSink.sendToOnlineOwner(ownerUuid, maidDisplayName, modeTranslationKey, missing);
        if (delivered) {
            lastTask = mode;
            lastSignature = sig;
            lastNotifiedWorldTime = worldTime;
        }
        return delivered;
    }

    public static String computeSignature(List<MissingConditionEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(MissingConditionIds.SIG_ENTRY_SEP);
            entries.get(i).appendSignature(sb);
        }
        return sb.toString();
    }

    public static String resolveMaidDisplayName(EntityMaid maid) {
        if (maid == null) return "";
        if (maid.hasCustomNameTag()) {
            String custom = maid.getCustomNameTag();
            if (custom != null && !custom.trim().isEmpty()) {
                return custom;
            }
        }
        String senderName = maid.getCommandSenderName();
        return senderName != null ? senderName : "";
    }

    private static UUID parseOwnerUuid(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static EntityPlayerMP findOnlineOwner(MinecraftServer server, UUID ownerUuid) {
        List<?> players = server.getConfigurationManager().playerEntityList;
        if (players == null) return null;
        for (Object raw : players) {
            if (raw instanceof EntityPlayerMP player && ownerUuid.equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }
}
