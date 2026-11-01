package com.tianluoqaq.tlmnh.common.protection;

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
        return "tlmnhprotect";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "commands.tlmnhprotect.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player) || args.length != 2 || !args[0].equals("recover"))
            throw new WrongUsageException(getCommandUsage(sender));
        int id = parseInt(sender, args[1]);
        var entity = player.worldObj.getEntityByID(id);
        if (!(entity instanceof EntityMaid maid) || !maid.getMaidInventory()
            .isUseableByPlayer(player)) throw new WrongUsageException("commands.tlmnhprotect.owner");
        boolean moved = maid.getMaidInventory()
            .recovery()
            .recoverOne(player);
        sender.addChatMessage(
            new ChatComponentTranslation(moved ? "chat.tlmnh.protection.recovered" : "chat.tlmnh.protection.no_space"));
    }
}
