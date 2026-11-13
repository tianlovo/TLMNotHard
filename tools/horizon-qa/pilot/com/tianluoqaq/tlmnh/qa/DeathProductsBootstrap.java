package com.tianluoqaq.tlmnh.qa;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Bootstrap metadata only. Does not define the Minecraft or provider target classes. */
public final class DeathProductsBootstrap {

    public static final String REVISION = "native-controlled-death-products-v1";
    public static final String PROPERTY = "tlmnh.qaDeathProducts";
    private static final String PACKAGE =
        "com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin.";
    private static final String UPDATE = PACKAGE + "NativeDeathUpdateMixin";
    private static final String CONSTRUCTOR = PACKAGE + "NativeExperienceConstructorMixin";
    private static final String ARCHAIC =
        "org.embeddedt.archaicfix.mixins.common.core.MixinEntityLivingBase_EarlyXpDrop";
    private static final Map<String, JsonObject> APPLIED = new HashMap<>();
    private static String failure;

    private DeathProductsBootstrap() {}

    public static boolean requested() {
        String request = System.getProperty(PROPERTY);
        if (request == null) return false;
        require(REVISION.equals(request), "Unknown death-products observer request");
        return true;
    }

    public static boolean owns(String mixin) {
        return UPDATE.equals(mixin) || CONSTRUCTOR.equals(mixin);
    }

    public static boolean applies(String target, String mixin) {
        return requested() && ((UPDATE.equals(mixin) && target.equals("net.minecraft.entity.EntityLivingBase"))
            || (CONSTRUCTOR.equals(mixin) && target.equals("net.minecraft.entity.item.EntityXPOrb")));
    }

    public static List<String> mixins() {
        return requested() ? Arrays.asList("NativeDeathUpdateMixin", "NativeExperienceConstructorMixin")
            : java.util.Collections.emptyList();
    }

    public static synchronized void applied(String target, ClassNode node, String mixin) {
        try {
            require(applies(target, mixin) && !APPLIED.containsKey(target), "Duplicate/foreign death-products apply");
            JsonObject proof = new JsonObject();
            proof.addProperty("target", target);
            proof.addProperty("mixin", mixin);
            proof.addProperty("revision", REVISION);
            if (UPDATE.equals(mixin)) update(node, proof);
            else constructor(node, proof);
            APPLIED.put(target, proof);
            System.out.println("TLMNH_QA_DEATH_PRODUCTS_APPLIED " + target + " " + mixin);
        } catch (Throwable problem) {
            if (failure == null) failure = problem.toString();
        }
    }

    private static void update(ClassNode node, JsonObject proof) {
        MethodNode nativeUpdate = null, early = null;
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (method.desc.equals("()V") && (method.name.equals("onDeathUpdate")
                || method.name.equals("func_70609_aI"))) {
                require(nativeUpdate == null, "Ambiguous native death-update method");
                nativeUpdate = method;
            }
            if (method.desc.equals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && method.name.endsWith("$dropXpImmediately") && mergedFrom(method, ARCHAIC)) {
                require(early == null, "Ambiguous merged Archaic early-XP method");
                early = method;
            }
        }
        require(nativeUpdate != null && early != null, "Reviewed native/Archaic death-update methods absent");
        boolean cached = false;
        for (Object raw : node.fields) {
            org.spongepowered.asm.lib.tree.FieldNode field = (org.spongepowered.asm.lib.tree.FieldNode) raw;
            if (field.name.equals("arch$droppedXp") && field.desc.equals("I")) cached = true;
        }
        require(cached, "Actual Archaic XP cache absent");
        AbstractInsnNode[] code = nativeUpdate.instructions.toArray();
        int getter = -1, store = -1, capture = -1, getterCount = 0, captureCount = 0;
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof MethodInsnNode call) {
                if ((call.name.equals("getExperiencePoints") || call.name.equals("func_70693_a"))
                    && call.desc.equals("(Lnet/minecraft/entity/player/EntityPlayer;)I")) {
                    getter = i;
                    getterCount++;
                }
                if (call.name.endsWith("$deathProducts$lateAmount")) {
                    capture = i;
                    captureCount++;
                }
            }
            if (getter >= 0 && store < 0 && code[i] instanceof VarInsnNode variable
                && variable.getOpcode() == Opcodes.ISTORE && variable.var == 1) store = i;
        }
        require(getterCount == 1 && captureCount == 1 && getter < store && store < capture,
            "Native residual capture is not after its actual assignment");
        proof.addProperty("nativeUpdateMethod", nativeUpdate.name);
        proof.addProperty("earlyProducerMethod", early.name);
        proof.addProperty("earlyProducerDescriptor", early.desc);
        proof.addProperty("earlyMixinSource", ARCHAIC);
        proof.addProperty("cacheField", "arch$droppedXp");
        proof.addProperty("nativeGetterSites", getterCount);
        proof.addProperty("lateCaptureSites", captureCount);
        proof.addProperty("lateCaptureAfterStore", true);
        proof.addProperty("wrapMethodRuntimeClosureRequired", true);
    }

    private static void constructor(ClassNode node, JsonObject proof) {
        int constructors = 0, captures = 0;
        for (Object raw : node.methods) {
            MethodNode method = (MethodNode) raw;
            if (!method.name.equals("<init>") || !method.desc.equals("(Lnet/minecraft/world/World;DDDI)V")) continue;
            constructors++;
            for (AbstractInsnNode instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call
                    && call.name.endsWith("$deathProducts$constructed")) captures++;
        }
        require(constructors == 1 && captures == 1, "Exact XP constructor RETURN observer missing/ambiguous");
        proof.addProperty("constructorSites", constructors);
        proof.addProperty("constructorReturnCaptureSites", captures);
    }

    private static boolean mergedFrom(MethodNode method, String expected) {
        for (List<AnnotationNode> annotations : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")) continue;
                for (int i = 0; annotation.values != null && i + 1 < annotation.values.size(); i += 2)
                    if (annotation.values.get(i).equals("mixin") && annotation.values.get(i + 1).equals(expected))
                        return true;
            }
        }
        return false;
    }

    public static synchronized JsonObject snapshot() {
        require(failure == null, "Death-products Mixin application failed: " + failure);
        require(APPLIED.size() == 2, "Both actual death-products Mixin targets are required");
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonObject> entry : APPLIED.entrySet())
            result.add(entry.getKey(), new JsonParser().parse(entry.getValue().toString()));
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
