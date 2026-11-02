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

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
