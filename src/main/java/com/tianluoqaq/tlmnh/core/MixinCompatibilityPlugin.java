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
    private static volatile boolean naturalWorldGenHook;

    public static boolean hasShieldCaptureHook() {
        return shieldCaptureHook;
    }

    public static boolean hasNaturalWorldGenHook() {
        return naturalWorldGenHook;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".MixinMaidPassengerAlias")) return supportsYsmMaidPassenger(targetClassName);
        if (mixinClassName.endsWith(".MixinMaidWorldGenSpawning")) return supportsNaturalWorldGen(targetClassName);
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

    /** 可选YSM只在已读取的提供方class原字节及实际注入shape一致时加载 */
    private static boolean supportsYsmMaidPassenger(String targetClassName) {
        if (!"com.fox.ysmu.client.animation.condition.ConditionalEntityIdMatch".equals(targetClassName)) return false;
        try {
            var resource = net.minecraft.launchwrapper.Launch.classLoader
                .getResource("com/fox/ysmu/client/animation/condition/ConditionalEntityIdMatch.class");
            if (resource == null) return false;
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = resource.openStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = stream.read(bytes)) >= 0) if (count > 0) digest.update(bytes, 0, count);
            }
            var hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            // 同一已审计Multi-Release JAR：Java8原entry和Java21+实际选中的entry分别保留精确摘要
            if (!"6736b016732daa5cb32676fc3f88f1989bda8797663e4f806295d8d43109c994".equals(hex.toString())
                && !"a6cef19780740b05e7babb590e32dba7c9a088d5fcb59bfe3edac1e25bcb9a3d".equals(hex.toString())) {
                LogManager.getLogger("tlmnh")
                    .warn(
                        "YSM passenger matcher differs; resource={} observedSha256={}; original matching remains active",
                        resource,
                        hex);
                return false;
            }
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            int matching = 0;
            boolean field = false;
            for (Object raw : node.fields) {
                var entry = (org.spongepowered.asm.lib.tree.FieldNode) raw;
                if (entry.name.equals("ids") && entry.desc.equals("Ljava/util/Set;")
                    && (entry.access & Opcodes.ACC_FINAL) != 0) field = true;
            }
            for (Object raw : node.methods) {
                var entry = (MethodNode) raw;
                if (entry.name.equals("doTest")
                    && entry.desc.equals("(Lnet/minecraft/entity/EntityLivingBase;)Ljava/lang/String;")) matching++;
            }
            return field && matching == 1;
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger("tlmnh")
                .warn("Cannot inspect optional YSM passenger matcher; original matching remains active", failure);
            return false;
        }
    }

    private static boolean isWorldGenSpawning(MethodNode method) {
        return (method.name.equals("performWorldGenSpawning") || method.name.equals("func_77191_a"))
            && method.desc
                .equals("(Lnet/minecraft/world/World;Lnet/minecraft/world/biome/BiomeGenBase;IIIILjava/util/Random;)V")
            && (method.access & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean supportsNaturalWorldGen(String targetClassName) {
        if (Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaNaturalWorldgenHookDisabled"))
            return false;
        try {
            var node = MixinService.getService()
                .getBytecodeProvider()
                .getClassNode(targetClassName);
            int methods = 0, calls = 0;
            for (Object rawMethod : node.methods) {
                var method = (MethodNode) rawMethod;
                if (!isWorldGenSpawning(method)) continue;
                methods++;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("net/minecraft/world/World")
                        && (call.name.equals("spawnEntityInWorld") || call.name.equals("func_72838_d"))
                        && call.desc.equals("(Lnet/minecraft/entity/Entity;)Z")) calls++;
                }
            }
            if (methods == 1 && calls == 1) return true;
            LogManager.getLogger("tlmnh")
                .warn("Native worldgen spawning shape changed; natural maid spawning disabled");
        } catch (Exception | LinkageError failure) {
            LogManager.getLogger("tlmnh")
                .warn("Cannot inspect native worldgen spawning hook; natural maid spawning disabled", failure);
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
        if (mixinClassName.endsWith(".MixinMaidWorldGenSpawning")) {
            int calls = 0;
            for (Object rawMethod : targetClass.methods) {
                var method = (MethodNode) rawMethod;
                if (!isWorldGenSpawning(method)) continue;
                for (var instruction = method.instructions.getFirst(); instruction
                    != null; instruction = instruction.getNext()) {
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(targetClass.name)
                        && call.name.contains("tlmnh$naturalMaidWorldGen")
                        && call.desc.equals("(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;)Z")) calls++;
                }
            }
            naturalWorldGenHook = calls == 1;
            return;
        }
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
