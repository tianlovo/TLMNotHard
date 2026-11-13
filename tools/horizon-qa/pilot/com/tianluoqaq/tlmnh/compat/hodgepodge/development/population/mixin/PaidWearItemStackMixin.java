package com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin;

import java.util.Random;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tianluoqaq.tlmnh.qa.PaidWearLedger;

/** 单次原生调用保留原返回与异常，观察错误在下一资格边界拒签 */
@Mixin(ItemStack.class)
public abstract class PaidWearItemStackMixin {
    @WrapMethod(method = { "setItemDamage", "func_77964_b" }, remap = false, require = 1)
    private void paidWear$set(int damage, Operation<Void> original) {
        PaidWearLedger.invoke((ItemStack) (Object) this, "setItemDamage", damage, () -> {
            original.call(damage);
            return null;
        });
    }

    @WrapMethod(method = { "attemptDamageItem", "func_96631_a" }, remap = false, require = 1)
    private boolean paidWear$attempt(int damage, Random random, Operation<Boolean> original) {
        return PaidWearLedger.invoke((ItemStack) (Object) this, "attemptDamageItem", damage,
            () -> original.call(damage, random));
    }

    @WrapMethod(method = { "damageItem", "func_77972_a" }, remap = false, require = 1)
    private void paidWear$damage(int damage, EntityLivingBase actor, Operation<Void> original) {
        PaidWearLedger.invoke((ItemStack) (Object) this, "damageItem", damage, () -> {
            original.call(damage, actor);
            return null;
        });
    }
}
