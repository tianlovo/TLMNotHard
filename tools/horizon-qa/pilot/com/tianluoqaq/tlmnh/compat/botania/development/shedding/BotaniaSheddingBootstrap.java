package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.JumpInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.tianluoqaq.tlmnh.qa.PopulationQuiescenceContract;

/** Optional bootstrap uses resources only; no provider/Minecraft class definition. */
public final class BotaniaSheddingBootstrap implements IMixinConfigPlugin {

    public static final String REVISION = "botania-native-shedding-v1";
    public static final String PROPERTY = "tlmnh.qaBotaniaShedding";
    public static final String JAR_SHA = "43d34830b1c32384f124c0e18fa66e848454dd3d6e585c1e7b5a9f143c364c5e";
    public static final String CLASS_SHA = "d08c39f90c184884586a73d90c19324e9aee18bddfb6fc81ca10df9dcb0acd9f";
    public static final String PROVIDER = "vazkii.botania.common.core.handler.SheddingHandler";
    private static final String PREFIX = "com.tianluoqaq.tlmnh.compat.botania.development.shedding.mixin.";
    private static final String UPDATE = "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V";
    private static final String DROP = "(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;";
    private static boolean handlerApplied, entityApplied;
    private static Class<?> authenticated;
    private static String failure;
    // Generated from actual pinned class instruction boundaries, with branch destinations in opcode ordinals.
    private static final String SHAPE = "25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;180:net/minecraft/entity/Entity:field_70170_p:Lnet/minecraft/world/World;;180:net/minecraft/world/World:field_72995_K:Z;153:6;177;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;184:vazkii/botania/common/core/handler/SheddingHandler:getShedPattern:(Lnet/minecraft/entity/Entity;)Lvazkii/botania/common/core/handler/SheddingHandler$ShedPattern;;58:2;25:2;198:27;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;180:net/minecraft/entity/Entity:field_70170_p:Lnet/minecraft/world/World;;180:net/minecraft/world/World:field_73012_v:Ljava/util/Random;;25:2;182:vazkii/botania/common/core/handler/SheddingHandler$ShedPattern:getRate:()I;182:java/util/Random:nextInt:(I)I;154:27;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;25:2;182:vazkii/botania/common/core/handler/SheddingHandler$ShedPattern:getItemStack:()Lnet/minecraft/item/ItemStack;;11;182:net/minecraft/entity/Entity:func_70099_a:(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;;87;177;";

    public static boolean requested() {
        String value = System.getProperty(PROPERTY);
        if (value == null) return false;
        check(REVISION.equals(value), "Unknown Botania observer request");
        return true;
    }

    public void onLoad(String name) {
        if (!requested()) return;
        try (InputStream stream = getClass().getClassLoader()
            .getResourceAsStream(PROVIDER.replace('.', '/') + ".class")) {
            check(PopulationQuiescenceContract.requested(), "Botania observer requires the paid observation contract");
            check(stream != null && CLASS_SHA.equals(hash(read(stream))), "Actual Botania class resource differs");
        } catch (Exception problem) {
            throw new IllegalStateException("Botania observer bootstrap rejected", problem);
        }
    }

    public String getRefMapperConfig() {
        return null;
    }

    public List<String> getMixins() {
        return requested() ? Arrays.asList("SheddingHandlerMixin", "NativeSheddingSpawnMixin")
            : Collections.emptyList();
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        return requested() && ((target.equals(PROVIDER) && mixin.equals(PREFIX + "SheddingHandlerMixin"))
            || (target.equals("net.minecraft.entity.Entity") && mixin.equals(PREFIX + "NativeSheddingSpawnMixin")));
    }

    public void acceptTargets(Set<String> mine, Set<String> other) {}

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        check(shouldApplyMixin(target, mixin), "Foreign Botania mixin application");
        if (target.equals(PROVIDER)) {
            MethodNode method = exact(node, "onLivingUpdate", UPDATE);
            check(
                SHAPE.equals(shape(method)),
                "Actual transformed Botania handler differs from pinned native instructions");
        } else {
            MethodNode method = null;
            for (Object raw : node.methods) {
                MethodNode candidate = (MethodNode) raw;
                if (candidate.desc.equals(DROP)
                    && (candidate.name.equals("entityDropItem") || candidate.name.equals("func_70099_a"))) {
                    check(method == null, "Ambiguous Entity native drop");
                    method = candidate;
                }
            }
            check(method != null, "Native Entity drop absent");
            int calls = 0;
            for (AbstractInsnNode insn : method.instructions.toArray())
                if (insn instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/World")
                    && call.desc.equals("(Lnet/minecraft/entity/Entity;)Z")
                    && (call.name.equals("spawnEntityInWorld") || call.name.equals("func_72838_d"))) calls++;
            check(calls == 1, "Actual native drop spawn site differs");
        }
    }

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        check(shouldApplyMixin(target, mixin), "Foreign Botania postApply");
        if (target.equals(PROVIDER)) {
            check(!handlerApplied, "Duplicate Botania handler application");
            handlerApplied = true;
        } else {
            check(!entityApplied, "Duplicate native Entity application");
            entityApplied = true;
        }
        System.out.println("TLMNH_QA_BOTANIA_SHEDDING_APPLIED " + target + " " + mixin);
    }

    public static synchronized void requireApplied() {
        check(
            requested() && handlerApplied
                && entityApplied
                && failure == null
                && PopulationQuiescenceContract.selected(),
            "Botania observer not applied/selected: " + failure);
    }

    public static synchronized void authenticate(Class<?> provider) {
        if (authenticated == provider) return;
        check(
            authenticated == null && provider.getName()
                .equals(PROVIDER),
            "Unknown Botania runtime provider");
        try {
            check(
                JAR_SHA.equals(
                    hash(
                        Files.readAllBytes(
                            Paths.get(
                                provider.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())))),
                "Botania runtime CodeSource differs");
            authenticated = provider;
        } catch (Exception problem) {
            failure = problem.toString();
            throw new IllegalStateException("Botania runtime authentication failed", problem);
        }
    }

    public static synchronized boolean isProvider(Class<?> type) {
        return authenticated != null && authenticated == type;
    }

    private static byte[] read(InputStream stream) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int n;
        while ((n = stream.read(bytes)) >= 0) buffer.write(bytes, 0, n);
        return buffer.toByteArray();
    }

    private static String hash(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256")
            .digest(data)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    private static MethodNode exact(ClassNode node, String name, String desc) {
        MethodNode found = null;
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (method.name.equals(name) && method.desc.equals(desc)) {
                check(found == null, "Ambiguous handler method");
                found = method;
            }
        }
        check(found != null, "Handler method absent");
        return found;
    }

    private static String shape(MethodNode method) {
        check(method.tryCatchBlocks.isEmpty(), "Unexpected handler exception table");
        StringBuilder result = new StringBuilder();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() < 0) continue;
            result.append(insn.getOpcode());
            if (insn instanceof VarInsnNode var) result.append(':')
                .append(var.var);
            if (insn instanceof FieldInsnNode field) result.append(':')
                .append(field.owner)
                .append(':')
                .append(field.name)
                .append(':')
                .append(field.desc);
            if (insn instanceof MethodInsnNode call) result.append(':')
                .append(call.owner)
                .append(':')
                .append(call.name)
                .append(':')
                .append(call.desc);
            if (insn instanceof JumpInsnNode branch) {
                int ordinal = 0;
                AbstractInsnNode cursor = method.instructions.getFirst();
                while (cursor != branch.label) {
                    check(cursor != null, "Foreign branch label");
                    if (cursor.getOpcode() >= 0) ordinal++;
                    cursor = cursor.getNext();
                }
                result.append(':')
                    .append(ordinal);
            }
            result.append(';');
        }
        return result.toString();
    }

    private static void check(boolean good, String message) {
        if (!good) throw new IllegalStateException(message);
    }
}
