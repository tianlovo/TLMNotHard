package com.tianluoqaq.tlmnh.common.clienthook;

import java.util.Objects;
import java.util.function.BiConsumer;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 供保留在 main 源集的 {@code MixinModelBipedCarry} 回调客户端抱持抬臂实现 */
@SideOnly(Side.CLIENT)
public final class CarryArmsHook {

    private static final BiConsumer<ModelBiped, Entity> NOOP = (model, entity) -> {};
    private static volatile BiConsumer<ModelBiped, Entity> handler = NOOP;

    private CarryArmsHook() {}

    public static void install(BiConsumer<ModelBiped, Entity> value) {
        handler = Objects.requireNonNull(value, ClientHookIds.ID_VALUE);
    }

    public static void apply(ModelBiped model, Entity entity) {
        handler.accept(model, entity);
    }
}
