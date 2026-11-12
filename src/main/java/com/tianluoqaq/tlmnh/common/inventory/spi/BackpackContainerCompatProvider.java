package com.tianluoqaq.tlmnh.common.inventory.spi;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.item.ItemStack;

/** 背包类内部容器适配扩展接口：供拾取优先存入、工作模式物资取用与热锭容器免烫扫描共享 */
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
     * 只读遍历背包内部当前存放的全部非空物品（包含锁定模式林业背包，供热锭免烫核验与测试断言调用）。
     *
     * @return 若该容器归属已启用提供方并完成遍历则返回 true
     */
    boolean forEachStoredStack(ItemStack containerStack, Consumer<ItemStack> visitor);

    /**
     * 只读遍历当前允许自动取用的背包内部物品（跳过锁定模式林业背包或正被其他玩家打开的会话，保证齐件判断与执行取用严格一致）。
     *
     * @return 若该容器当前允许取用并完成遍历则返回 true
     */
    default boolean forEachExtractableStack(ItemStack containerStack, Consumer<ItemStack> visitor) {
        return false;
    }

    /**
     * 按过滤条件与可选评分器从背包内部容器中取出（或模拟预览）最多 maxCount 个最佳匹配物品。
     *
     * @param containerStack 女仆持有的背包物品（simulate 为 false 且成功取出时原地写回 NBT 或外部存档）
     * @param filter         过滤条件（入参为只读副本，返回 true 表示匹配）
     * @param scorer         可选评分函数（为 null 时取首个匹配栈并合并同类栈；非 null 时取评分最高的单栈）
     * @param maxCount       最多取出的数量（>= 1）
     * @param simulate       为 true 时仅返回将取出的物品副本，不修改背包内容与外部存档
     * @return 取出的物品栈副本（stackSize <= maxCount）；无匹配或当前不可取用时返回 null
     */
    default ItemStack extractFromBackpack(ItemStack containerStack, Predicate<ItemStack> filter,
        ToIntFunction<ItemStack> scorer, int maxCount, boolean simulate) {
        return null;
    }
}
