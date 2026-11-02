package com.tianluoqaq.tlmnh.core;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** 启动阶段只读字节码，不提前加载模组实体；不兼容的可选碰撞钩子留给伤害前后备 */
public final class MixinCompatibilityPlugin implements IMixinConfigPlugin {

    private static volatile boolean shieldCaptureHook;

    public static boolean hasShieldCaptureHook() {
        return shieldCaptureHook;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".MixinEntityArrowShieldCapture")) return supportsShieldCapture(targetClassName);
        if (!mixinClassName.endsWith(".MixinArrowImpact")) return true;
        if (Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaProjectileImpactFallback"))
            return false;
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!(method.name.equals("onUpdate") || method.name.equals("func_70071_h_"))
                    || !method.desc.equals("()V")) continue;
                int raySlot = -1, entitySlot = -1, rays = 0, entities = 0;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    var next = nextCode(instruction);
                    if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
                    if (call.desc.endsWith(")Lnet/minecraft/util/MovingObjectPosition;")
                        && (call.name.equals("func_147447_a") || call.name.equals("rayTraceBlocks"))) {
                        raySlot = store.var;
                        rays++;
                    }
                    if (call.owner.equals("net/minecraft/util/MovingObjectPosition") && call.name.equals("<init>")
                        && call.desc.equals("(Lnet/minecraft/entity/Entity;)V")) {
                        entitySlot = store.var;
                        entities++;
                    }
                }
                if (rays == 1 && entities == 1 && raySlot == entitySlot && raySlot >= 0) return true;
            }
            LogManager.getLogger("tlmnh")
                .warn("Native arrow impact shape changed; pre-damage protection remains active");
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger("tlmnh")
                .warn("Cannot inspect native arrow impact hook; using pre-damage protection", failure);
        }
        return false;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        var next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** 仅接受唯一伤害调用和原生getArrowCount()+1布局，不改写未知引擎实现 */
    private static boolean supportsShieldCapture(String targetClassName) {
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!(method.name.equals("onUpdate") || method.name.equals("func_70071_h_"))
                    || !method.desc.equals("()V")) continue;
                int attacks = 0, additions = 0;
                boolean afterAttack = false;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (call.owner.equals("net/minecraft/entity/Entity")
                        && (call.name.equals("attackEntityFrom") || call.name.equals("func_70097_a"))
                        && call.desc.equals("(Lnet/minecraft/util/DamageSource;F)Z")) {
                        attacks++;
                        afterAttack = true;
                    }
                    if (call.owner.equals("net/minecraft/entity/EntityLivingBase")
                        && (call.name.equals("setArrowCountInEntity") || call.name.equals("func_85034_r"))
                        && call.desc.equals("(I)V")) {
                        var add = previousCode(call);
                        var one = add == null ? null : previousCode(add);
                        var get = one == null ? null : previousCode(one);
                        if (afterAttack && add != null
                            && add.getOpcode() == Opcodes.IADD
                            && one != null
                            && one.getOpcode() == Opcodes.ICONST_1
                            && get instanceof MethodInsnNode getter
                            && getter.owner.equals("net/minecraft/entity/EntityLivingBase")
                            && (getter.name.equals("getArrowCountInEntity") || getter.name.equals("func_85035_bI"))
                            && getter.desc.equals("()I")) additions++;
                        else return false;
                    }
                }
                if (attacks == 1 && additions == 1) return true;
            }
            LogManager.getLogger("tlmnh")
                .warn("Native arrow shield capture shape changed; capture hook disabled");
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger("tlmnh")
                .warn("Cannot inspect native shield capture hook; capture disabled", failure);
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!mixinClassName.endsWith(".MixinEntityArrowShieldCapture")) return;
        int calls = 0;
        for (Object rawMethod : targetClass.methods) {
            var method = (MethodNode) rawMethod;
            if (!(method.name.equals("onUpdate") || method.name.equals("func_70071_h_")) || !method.desc.equals("()V"))
                continue;
            for (var instruction = method.instructions.getFirst(); instruction
                != null; instruction = instruction.getNext())
                if (instruction instanceof MethodInsnNode call && call.name.contains("tlmnh$shieldArrowDamage"))
                    calls++;
        }
        shieldCaptureHook = calls == 1;
    }
}
