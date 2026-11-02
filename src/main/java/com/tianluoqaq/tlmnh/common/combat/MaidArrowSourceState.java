package com.tianluoqaq.tlmnh.common.combat;

import net.minecraft.nbt.NBTBase;

/** 本模组箭的原始来源及停止标记；读取方不得改写返回的NBT */
public interface MaidArrowSourceState {

    NBTBase tlmnh$source();

    void tlmnh$recordSource(NBTBase source);

    boolean tlmnh$sourceStopped();

    void tlmnh$stopSource();
}
