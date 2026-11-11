package com.tianluoqaq.tlmnh.client.protection;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import com.tianluoqaq.tlmnh.common.protection.CompatibilityLedger;
import com.tianluoqaq.tlmnh.common.protection.ExternalFeature;
import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 本机依赖基线和本地聊天，不读写服务端世界记录或其他玩家的通知状态 */
@SideOnly(Side.CLIENT)
public final class ClientProtectionEvents {

    private final Set<String> notified = new HashSet<>();
    private EntityPlayer player;

    public static void register() {
        verifyBaseline();
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientProtectionEvents());
    }

    private static void verifyBaseline() {
        IntegrationGuard.call(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE, () -> {
            var path = Minecraft.getMinecraft().mcDataDir.toPath()
                .resolve("config/tlmnh-client-compatibility.dat");
            var ledger = new CompatibilityLedger("client");
            if (Files.exists(path)) {
                try (var input = Files.newInputStream(path)) {
                    ledger.readFromNBT(CompressedStreamTools.readCompressed(input));
                }
            }
            ledger.check(Side.CLIENT);
            if (ledger.isDirty() && IntegrationGuard.available(Side.CLIENT, ExternalFeature.CLIENT_RENDER_STATE)) {
                Files.createDirectories(path.getParent());
                var temporary = Files.createTempFile(path.getParent(), "tlmnh-client-compatibility-", ".tmp");
                try {
                    var data = new NBTTagCompound();
                    ledger.writeToNBT(data);
                    try (var output = Files.newOutputStream(temporary)) {
                        CompressedStreamTools.writeCompressed(data, output);
                    }
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return true;
        }, () -> false);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var current = Minecraft.getMinecraft().thePlayer;
        if (current != player) {
            player = current;
            notified.clear();
        }
        if (player == null) return;
        for (var fault : IntegrationGuard.faults(Side.CLIENT)
            .values()) if (notified.add(fault.code)) {
                var message = new ChatComponentTranslation(
                    "chat.tlmnh.protection.client_fallback",
                    fault.code,
                    fault.key);
                message.getChatStyle()
                    .setColor(EnumChatFormatting.GOLD);
                player.addChatMessage(message);
            }
    }
}
