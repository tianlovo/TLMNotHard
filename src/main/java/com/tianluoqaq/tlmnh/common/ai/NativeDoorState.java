package com.tianluoqaq.tlmnh.common.ai;

import java.lang.reflect.Method;

import net.minecraft.block.BlockDoor;
import net.minecraft.world.World;

/** native 原版木门的 Malisis 动画状态；不替换其开门入口、动画或权限 */
public final class NativeDoorState {

    private static final Method ANIMATED_STATE = resolve();

    private NativeDoorState() {}

    public static boolean isOpenOrOpening(BlockDoor door, World world, int x, int y, int z) {
        var tile = world.getTileEntity(x, y, z);
        if (ANIMATED_STATE != null && ANIMATED_STATE.getDeclaringClass()
            .isInstance(tile)) {
            try {
                Object state = ANIMATED_STATE.invoke(tile);
                if (state instanceof Enum<?>value) return value.name()
                    .equals("OPENED")
                    || value.name()
                        .equals("OPENING");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("native animated door state is unavailable", failure);
            }
        }
        return door.func_150015_f(world, x, y, z);
    }

    private static Method resolve() {
        try {
            return Class
                .forName(
                    "net.malisis.doors.door.tileentity.DoorTileEntity",
                    false,
                    NativeDoorState.class.getClassLoader())
                .getMethod("getState");
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            return null;
        }
    }
}
