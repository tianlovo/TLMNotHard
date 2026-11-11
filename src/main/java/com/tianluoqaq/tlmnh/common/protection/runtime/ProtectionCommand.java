package com.tianluoqaq.tlmnh.common.protection.runtime;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** 普通玩家只能取回自己附近女仆的保管数据，不提供忽略版本校验的强制恢复 */
public final class ProtectionCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return ProtectionRuntimeIds.ID_TLMNHPROTECT;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return ProtectionRuntimeIds.ID_COMMANDS_TLMNHPROTECT_USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return ProtectionRuntimeTuning.PROTECTION_COMMAND_GET_REQUIRED_PERMISSION_LEVEL_ZER;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player) || args.length != ProtectionRuntimeTuning.TARGET_LENGTH_THRESHOLD
            || !args[ProtectionRuntimeTuning.PROTECTION_COMMAND_GET_REQUIRED_PERMISSION_LEVEL_ZER]
                .equals(ProtectionRuntimeIds.ID_RECOVER))
            throw new WrongUsageException(getCommandUsage(sender));
        int id = parseInt(sender, args[ProtectionRuntimeTuning.PARSE_INT_SINGLE_UNIT]);
        var entity = player.worldObj.getEntityByID(id);
        if (!(entity instanceof EntityMaid maid) || !maid.getMaidInventory()
            .isUseableByPlayer(player))
            throw new WrongUsageException(ProtectionRuntimeIds.ID_COMMANDS_TLMNHPROTECT_OWNER);
        boolean moved = maid.getMaidInventory()
            .recovery()
            .recoverOne(player);
        sender.addChatMessage(
            new ChatComponentTranslation(
                moved ? ProtectionRuntimeIds.I18N_CHAT_TLMNH_PROTECTION_RECOVERED
                    : ProtectionRuntimeIds.I18N_CHAT_TLMNH_PROTECTION_NO_SPACE));
    }
}
