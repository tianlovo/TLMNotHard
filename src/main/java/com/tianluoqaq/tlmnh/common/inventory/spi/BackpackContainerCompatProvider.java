package com.tianluoqaq.tlmnh.common.inventory.spi;

import java.util.function.Consumer;

import net.minecraft.item.ItemStack;

/** 背包类内部容器适配扩展接口：供拾取优先存入与热锭容器免烫扫描共享 */
public interface BackpackContainerCompatProvider {

    BackpackContainerCompatProvider NOOP = new BackpackContainerCompatProvider() {

        @Override
        public boolean isSupportedBackpack(ItemStack containerStack) {
            return false;
        }

        @Override
        public ItemStack insertIntoBackpack(ItemStack containerStack, ItemStack input, boolean simulate) {
            return input == null ? null : input.copy();
        }

        @Override
        public boolean forEachStoredStack(ItemStack containerStack, Consumer<ItemStack> visitor) {
            return false;
        }
    };

    /** 判断给定物品是否为受支持的背包类内部容器（排除末影背包等全局共享容器） */
    boolean isSupportedBackpack(ItemStack containerStack);

    /**
     * 尝试把待入库物品塞进背包内部容器（先合并同类非满栈，再填入空槽）。
     *
     * @param containerStack 女仆持有的背包物品（simulate 为 false 且发生存入时原地写回 NBT 或外部存档）
     * @param input          待存入物品（不会改动入参实例）
     * @param simulate       为 true 时仅试算剩余量，不改 NBT、不写盘
     * @return 未能塞入的剩余副本；全部塞入时返回 null
     */
    ItemStack insertIntoBackpack(ItemStack containerStack, ItemStack input, boolean simulate);

    /**
     * 只读遍历背包内部当前存放的全部非空物品（供热锭免烫核验与测试断言调用）。
     *
     * @return 若该容器归属已启用提供方并完成遍历则返回 true
     */
    boolean forEachStoredStack(ItemStack containerStack, Consumer<ItemStack> visitor);
}
