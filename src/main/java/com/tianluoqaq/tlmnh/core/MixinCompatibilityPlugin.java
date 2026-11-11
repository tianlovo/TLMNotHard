package com.tianluoqaq.tlmnh.core;

import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import com.tianluoqaq.tlmnh.common.diagnostic.DevelopmentFlags;
import com.tianluoqaq.tlmnh.common.protection.Sha256Hex;

/** 启动阶段只读目标字节码做形态核对，避免提前加载模组实体；可选碰撞钩子对不上时退回伤害前保护 */
public final class MixinCompatibilityPlugin implements IMixinConfigPlugin {

    private static volatile boolean shieldCaptureHook;
    private static volatile boolean naturalWorldGenHook;

    public static boolean hasShieldCaptureHook() {
        return shieldCaptureHook;
    }

    public static boolean hasNaturalWorldGenHook() {
        return naturalWorldGenHook;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(CorePluginIds.ID_MIXINMAIDPASSENGERALIAS))
            return supportsYsmMaidPassenger(targetClassName);
        if (mixinClassName.endsWith(CorePluginIds.ID_MIXINMAIDWORLDGENSPAWNING))
            return supportsNaturalWorldGen(targetClassName);
        if (mixinClassName.endsWith(CorePluginIds.ID_MIXINENTITYARROWSHIELDCAPTURE))
            return supportsShieldCapture(targetClassName);
        if (!mixinClassName.endsWith(CorePluginIds.ID_MIXINARROWIMPACT)) return true;
        if (DevelopmentFlags.isQaProjectileImpactFallback()) return false;
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!(method.name.equals(CorePluginIds.ID_ONUPDATE)
                    || method.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_70071_H))
                    || !method.desc.equals(CorePluginIds.ID_V)) continue;
                int raySlot = CorePluginTuning.UNSET_OR_NOT_FOUND, entitySlot = CorePluginTuning.UNSET_OR_NOT_FOUND,
                    rays = CorePluginTuning.MIXIN_COMPATIBILITY_RAYS,
                    entities = CorePluginTuning.MIXIN_COMPATIBILITY_RAYS;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    var next = nextCode(instruction);
                    if (!(next instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
                    if (call.desc.endsWith(CorePluginIds.ID_LNET_MINECRAFT_UTIL_MOVINGOBJECTPOSITION)
                        && (call.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_147447_A)
                            || call.name.equals(CorePluginIds.ID_RAYTRACEBLOCKS))) {
                        raySlot = store.var;
                        rays++;
                    }
                    if (call.owner.equals(CorePluginIds.ID_NET_MINECRAFT_UTIL_MOVINGOBJECTPOSITION)
                        && call.name.equals(CorePluginIds.ID_INIT)
                        && call.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS)) {
                        entitySlot = store.var;
                        entities++;
                    }
                }
                if (rays == CorePluginTuning.TARGET_RAYS_THRESHOLD && entities == CorePluginTuning.TARGET_RAYS_THRESHOLD
                    && raySlot == entitySlot
                    && raySlot >= 0) return true;
            }
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_NATIVE_ARROW_IMPACT_SHAPE_CHANGED);
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_CANNOT_INSPECT_NATIVE_ARROW_IMPACT, failure);
        }
        return false;
    }

    /** 核对 YSM 乘客匹配器的 class 字节摘要与注入形态，一致时才启用可选别名钩子 */
    private static boolean supportsYsmMaidPassenger(String targetClassName) {
        if (!CorePluginIds.TARGET_CLASS_CONDITIONAL_ENTITY_ID_MATCH.equals(targetClassName)) return false;
        try {
            var resource = Launch.classLoader
                .getResource(CorePluginIds.ID_COM_FOX_YSMU_CLIENT_ANIMATION_CONDITION_CONDITION);
            if (resource == null) return false;
            String hex;
            try (var stream = resource.openStream()) {
                hex = Sha256Hex.ofStream(stream);
            }
            // 同一个 Multi-Release JAR 内 Java 8 原始条目与 Java 21+ 命中条目的摘要分别放行
            if (!CorePluginIds.EXPECTED_SHA256_6736B016732D.equals(hex)
                && !CorePluginIds.EXPECTED_SHA256_A6CEF1978074.equals(hex)) {
                LogManager.getLogger(CorePluginIds.ID_TLMNH)
                    .warn(CorePluginDiagnostics.MSG_YSM_PASSENGER_MATCHER_DIFFERS_RESOURCE, resource, hex);
                return false;
            }
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            int matching = 0;
            boolean field = false;
            for (Object raw : node.fields) {
                var entry = (FieldNode) raw;
                if (entry.name.equals(CorePluginIds.ID_IDS) && entry.desc.equals(CorePluginIds.KEY_LJAVA_UTIL_SET)
                    && (entry.access & Opcodes.ACC_FINAL) != 0) field = true;
            }
            for (Object raw : node.methods) {
                var entry = (MethodNode) raw;
                if (entry.name.equals(CorePluginIds.ID_DOTEST)
                    && entry.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS_2)) matching++;
            }
            return field && matching == CorePluginTuning.TARGET_RAYS_THRESHOLD;
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_CANNOT_INSPECT_OPTIONAL_YSM_PASSENGER, failure);
            return false;
        }
    }

    private static boolean isWorldGenSpawning(MethodNode method) {
        return (method.name.equals(CorePluginIds.ID_PERFORMWORLDGENSPAWNING)
            || method.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_77191_A))
            && method.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS_3)
            && (method.access & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean supportsNaturalWorldGen(String targetClassName) {
        if (DevelopmentFlags.isQaNaturalWorldgenHookDisabled()) return false;
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            int methods = 0, calls = CorePluginTuning.MIXIN_COMPATIBILITY_RAYS;
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!isWorldGenSpawning(method)) continue;
                methods++;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(CorePluginIds.ID_NET_MINECRAFT_WORLD_WORLD)
                        && (call.name.equals(CorePluginIds.ID_SPAWNENTITYINWORLD)
                            || call.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_72838_D))
                        && call.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS_4)) calls++;
                }
            }
            if (methods == CorePluginTuning.TARGET_RAYS_THRESHOLD && calls == CorePluginTuning.TARGET_RAYS_THRESHOLD)
                return true;
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_NATIVE_WORLDGEN_SPAWNING_SHAPE_CHANGED);
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_CANNOT_INSPECT_NATIVE_WORLDGEN_SPAWNING, failure);
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

    /** 核对箭矢命中伤害与原生 getArrowCountInEntity() + 1 指令序列是否完好 */
    private static boolean supportsShieldCapture(String targetClassName) {
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!(method.name.equals(CorePluginIds.ID_ONUPDATE)
                    || method.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_70071_H))
                    || !method.desc.equals(CorePluginIds.ID_V)) continue;
                int attacks = 0, additions = CorePluginTuning.MIXIN_COMPATIBILITY_RAYS;
                boolean afterAttack = false;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    if (call.owner.equals(CorePluginIds.ID_NET_MINECRAFT_ENTITY_ENTITY)
                        && (call.name.equals(CorePluginIds.ID_ATTACKENTITYFROM)
                            || call.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_70097_A))
                        && call.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS_5)) {
                        attacks++;
                        afterAttack = true;
                    }
                    if (call.owner.equals(CorePluginIds.ID_NET_MINECRAFT_ENTITY_ENTITYLIVINGBASE)
                        && (call.name.equals(CorePluginIds.ID_SETARROWCOUNTINENTITY)
                            || call.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_85034_R))
                        && call.desc.equals(CorePluginIds.ID_I_V)) {
                        var add = previousCode(call);
                        var one = add == null ? null : previousCode(add);
                        var get = one == null ? null : previousCode(one);
                        if (afterAttack && add != null
                            && add.getOpcode() == Opcodes.IADD
                            && one != null
                            && one.getOpcode() == Opcodes.ICONST_1
                            && get instanceof MethodInsnNode getter
                            && getter.owner.equals(CorePluginIds.ID_NET_MINECRAFT_ENTITY_ENTITYLIVINGBASE)
                            && (getter.name.equals(CorePluginIds.ID_GETARROWCOUNTINENTITY)
                                || getter.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_85035_BI))
                            && getter.desc.equals(CorePluginIds.ID_I)) additions++;
                        else return false;
                    }
                }
                if (attacks == CorePluginTuning.TARGET_RAYS_THRESHOLD
                    && additions == CorePluginTuning.TARGET_RAYS_THRESHOLD) return true;
            }
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_NATIVE_ARROW_SHIELD_CAPTURE_SHAPE);
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger(CorePluginIds.ID_TLMNH)
                .warn(CorePluginDiagnostics.MSG_CANNOT_INSPECT_NATIVE_SHIELD_CAPTURE, failure);
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
        if (mixinClassName.endsWith(CorePluginIds.ID_MIXINMAIDWORLDGENSPAWNING)) {
            int calls = 0;
            for (Object rawMethod : targetClass.methods) {
                var method = (MethodNode) rawMethod;
                if (!isWorldGenSpawning(method)) continue;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(targetClass.name)
                        && call.name.contains(CorePluginIds.ID_TLMNH_NATURALMAIDWORLDGEN)
                        && call.desc.equals(CorePluginReflectionSpec.JVM_DESC_MIXIN_COMPATIBILITY_EQUALS_6)) calls++;
                }
            }
            naturalWorldGenHook = calls == CorePluginTuning.TARGET_RAYS_THRESHOLD;
            return;
        }
        if (!mixinClassName.endsWith(CorePluginIds.ID_MIXINENTITYARROWSHIELDCAPTURE)) return;
        int calls = 0;
        for (Object rawMethod : targetClass.methods) {
            var method = (MethodNode) rawMethod;
            if (!(method.name.equals(CorePluginIds.ID_ONUPDATE)
                || method.name.equals(CorePluginReflectionSpec.SRG_MEMBER_FUNC_70071_H))
                || !method.desc.equals(CorePluginIds.ID_V)) continue;
            for (var instruction = method.instructions.getFirst(); instruction
                != null; instruction = instruction.getNext())
                if (instruction instanceof MethodInsnNode call
                    && call.name.contains(CorePluginIds.ID_TLMNH_SHIELDARROWDAMAGE)) calls++;
        }
        shieldCaptureHook = calls == CorePluginTuning.TARGET_RAYS_THRESHOLD;
    }
}
