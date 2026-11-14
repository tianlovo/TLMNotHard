package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.JumpInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tianluoqaq.tlmnh.qa.PopulationQuiescenceContract;

/** Early registration and late physical-source observation have distinct checkpoints. */
public final class BotaniaSheddingBootstrap implements IMixinConfigPlugin {

    public static final String REVISION = "botania-native-shedding-v1";
    public static final String BOOTSTRAP_REVISION = "botania-mr21-late-bootstrap-v2";
    public static final String PROPERTY = "tlmnh.qaBotaniaShedding";
    public static final String PROVIDER = BotaniaProviderResource.PROVIDER;
    public static final String JAR_SHA = BotaniaProviderResource.JAR_SHA;
    public static final String CLASS_SHA = BotaniaProviderResource.SELECTED_SHA;
    public static final String PREFIX = "com.tianluoqaq.tlmnh.compat.botania.development.shedding.mixin.";
    public static final String UPDATE = "(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V";
    private static final String DROP = "(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;";
    private static boolean earlyRequested, lateSelected, handlerApplied, entityApplied;
    private static Class<?> authenticated;
    private static URLClassLoader selectedLoader;
    private static BotaniaProviderResource.Selection selected;
    private static String failure;
    private static int observedProviderNodeMajor = -1;
    private static final String SHAPE = "25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;180:net/minecraft/entity/Entity:field_70170_p:Lnet/minecraft/world/World;;180:net/minecraft/world/World:field_72995_K:Z;153:6;177;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;184:vazkii/botania/common/core/handler/SheddingHandler:getShedPattern:(Lnet/minecraft/entity/Entity;)Lvazkii/botania/common/core/handler/SheddingHandler$ShedPattern;;58:2;25:2;198:27;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;180:net/minecraft/entity/Entity:field_70170_p:Lnet/minecraft/world/World;;180:net/minecraft/world/World:field_73012_v:Ljava/util/Random;;25:2;182:vazkii/botania/common/core/handler/SheddingHandler$ShedPattern:getRate:()I;182:java/util/Random:nextInt:(I)I;154:27;25:1;180:net/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent:entity:Lnet/minecraft/entity/Entity;;25:2;182:vazkii/botania/common/core/handler/SheddingHandler$ShedPattern:getItemStack:()Lnet/minecraft/item/ItemStack;;11;182:net/minecraft/entity/Entity:func_70099_a:(Lnet/minecraft/item/ItemStack;F)Lnet/minecraft/entity/item/EntityItem;;87;177;";

    public static boolean requested() {
        String value = System.getProperty(PROPERTY);
        if (value == null) return false;
        check(REVISION.equals(value), "Unknown Botania observer request");
        return true;
    }

    public void onLoad(String name) {
        if (!requested()) return;
        try {
            check(PopulationQuiescenceContract.requested(), "Botania observer requires the paid observation contract");
            BotaniaProviderResource.requireRuntimeInputs();
            check(!earlyRequested, "Duplicate early Botania config request");
            earlyRequested = true;
            System.out.println("TLMNH_QA_BOTANIA_BOOTSTRAP_EARLY " + BOOTSTRAP_REVISION);
        } catch (Exception problem) {
            fail("EARLY_REQUEST", problem);
            throw new IllegalStateException("Botania early request failed", problem);
        }
    }

    public String getRefMapperConfig() {
        return null;
    }

    public List<String> getMixins() {
        return requested() ? Collections.singletonList("NativeSheddingSpawnMixin") : Collections.emptyList();
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        return requested() && target.equals("net.minecraft.entity.Entity")
            && mixin.equals(PREFIX + "NativeSheddingSpawnMixin");
    }

    public void acceptTargets(Set<String> mine, Set<String> other) {}

    public static synchronized List<String> lateMixins(Set<String> loadedMods) {
        if (!requested()) return Collections.emptyList();
        try {
            check(earlyRequested && !lateSelected, "Late Botania selection outside the unique early request");
            check(loadedMods.contains("Botania"), "Requested Botania provider mod is absent");
            lateSelected = true;
            System.out.println("TLMNH_QA_BOTANIA_BOOTSTRAP_LATE_SELECTED " + BOOTSTRAP_REVISION);
            return Collections.singletonList("SheddingHandlerMixin");
        } catch (Exception problem) {
            fail("LATE_SELECTION", problem);
            throw new IllegalStateException("Botania late selection failed", problem);
        }
    }

    public static boolean appliesProvider(String target, String mixin) {
        return requested() && lateSelected && target.equals(PROVIDER) && mixin.equals(PREFIX + "SheddingHandlerMixin");
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        try {
            check(shouldApplyMixin(target, mixin), "Foreign early Botania mixin application");
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
        } catch (Exception problem) {
            fail("EARLY_ENTITY_SHAPE", problem);
            throw new IllegalStateException("Botania early Entity shape failed", problem);
        }
    }

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        check(shouldApplyMixin(target, mixin) && !entityApplied, "Foreign/duplicate early Entity application");
        entityApplied = true;
        applied(target, mixin);
    }

    public static synchronized void providerPreApply(String target, ClassNode node, String mixin) {
        try {
            check(appliesProvider(target, mixin) && selected == null, "Foreign/duplicate late provider application");
            ClassLoader actualLoader = BotaniaSheddingBootstrap.class.getClassLoader();
            check(actualLoader instanceof URLClassLoader, "LATE_LOADER_NOT_URL_CLASS_LOADER");
            URLClassLoader loader = (URLClassLoader) actualLoader;
            BotaniaProviderResource.verifyRuntimeLoader(loader);
            selected = BotaniaProviderResource
                .inspect(loader.findResource(BotaniaProviderResource.LOGICAL_ENTRY), expectedJar());
            selectedLoader = loader;
            validateProviderShape(node);
            observedProviderNodeMajor = node.version;
            System.out.println("TLMNH_QA_BOTANIA_BOOTSTRAP_RESOURCE " + selection());
        } catch (Exception | LinkageError problem) {
            fail("LATE_PROVIDER_RESOURCE_OR_SHAPE", problem);
            throw new IllegalStateException("Botania late source selection failed", problem);
        }
    }

    public static void validateProviderShape(ClassNode node) {
        check(node.name.equals(PROVIDER.replace('.', '/')), "Foreign provider ClassNode");
        MethodNode method = exact(node, "onLivingUpdate", UPDATE);
        check(
            method.access == 1 && method.signature == null
                && (method.exceptions == null || method.exceptions.isEmpty()),
            "LATE_PROVIDER_METHOD_FLAGS_MISMATCH");
        check(
            method.visibleAnnotations != null && method.visibleAnnotations.size() == 1,
            "LATE_PROVIDER_EVENT_ANNOTATION_MISMATCH");
        AnnotationNode annotation = (AnnotationNode) method.visibleAnnotations.get(0);
        check(
            annotation.desc.equals("Lcpw/mods/fml/common/eventhandler/SubscribeEvent;")
                && (annotation.values == null || annotation.values.isEmpty())
                && (method.invisibleAnnotations == null || method.invisibleAnnotations.isEmpty()),
            "LATE_PROVIDER_EVENT_ANNOTATION_VALUES_MISMATCH");
        check(SHAPE.equals(shape(method)), "LATE_PROVIDER_METHOD_SHAPE_MISMATCH");
    }

    public static synchronized void providerPostApply(String target, String mixin) {
        check(
            appliesProvider(target, mixin) && selected != null && !handlerApplied && failure == null,
            "Foreign/incomplete late provider postApply");
        handlerApplied = true;
        applied(target, mixin);
    }

    private static void applied(String target, String mixin) {
        System.out.println("TLMNH_QA_BOTANIA_SHEDDING_APPLIED " + target + " " + mixin);
    }

    private static Path expectedJar() throws Exception {
        String manifestPath = System.getProperty("tlmnh.qaEffectEmptyWorldManifest");
        check(manifestPath != null, "LATE_LAUNCH_MANIFEST_PROPERTY_MISSING");
        byte[] bytes = Files.readAllBytes(Paths.get(manifestPath));
        check(
            BotaniaProviderResource.hash(bytes)
                .equals(System.getProperty("tlmnh.qaEffectEmptyWorldManifestSha256")),
            "LATE_LAUNCH_MANIFEST_HASH_MISMATCH");
        JsonObject launch = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8))
            .getAsJsonObject();
        String relative = "mods/Botania-1.13.42-GTNH.jar";
        check(
            JAR_SHA.equals(
                launch.getAsJsonObject("files")
                    .get(relative)
                    .getAsString()),
            "LATE_DECLARED_PROVIDER_HASH_MISMATCH");
        return Paths.get(
            launch.get("instanceRoot")
                .getAsString())
            .resolve(relative)
            .toRealPath();
    }

    public static synchronized void requireApplied() {
        check(
            requested() && earlyRequested
                && lateSelected
                && handlerApplied
                && entityApplied
                && selected != null
                && failure == null
                && PopulationQuiescenceContract.selected(),
            "Botania observer not applied/selected: " + failure);
    }

    public static synchronized void authenticate(Class<?> provider) {
        if (authenticated == provider) return;
        try {
            check(
                selected != null && handlerApplied
                    && provider.getName()
                        .equals(PROVIDER)
                    && provider.getClassLoader() == selectedLoader
                    && authenticated == null
                    && failure == null,
                "RUNTIME_PROVIDER_CLASS_OR_LOADER_MISMATCH");
            Path source = BotaniaProviderResource.sourceFile(
                provider.getProtectionDomain()
                    .getCodeSource()
                    .getLocation());
            check(
                source.equals(selected.jar) && JAR_SHA.equals(BotaniaProviderResource.hash(Files.readAllBytes(source))),
                "RUNTIME_PROVIDER_CODE_SOURCE_MISMATCH");
            authenticated = provider;
            System.out.println("TLMNH_QA_BOTANIA_BOOTSTRAP_AUTHENTICATED " + selection());
        } catch (Exception | LinkageError problem) {
            fail("RUNTIME_PROVIDER_CLASS", problem);
            throw new IllegalStateException("Botania runtime source authentication failed", problem);
        }
    }

    public static synchronized boolean isProvider(Class<?> type) {
        return authenticated != null && authenticated == type;
    }

    public static synchronized JsonObject selection() {
        JsonObject proof = new JsonObject();
        proof.addProperty("bootstrapRevision", BOOTSTRAP_REVISION);
        proof.addProperty("javaSpecificationVersion", System.getProperty("java.specification.version"));
        proof.addProperty("jarVersionProperty", System.getProperty("jdk.util.jar.version"));
        proof.addProperty("physicalEntry", BotaniaProviderResource.SELECTED_ENTRY);
        proof.addProperty("physicalEntrySha256", CLASS_SHA);
        proof.addProperty("physicalEntryMajor", 65);
        proof.addProperty("loaderJarSha256", BotaniaProviderResource.LOADER_JAR_SHA);
        proof.addProperty("loaderClassSha256", BotaniaProviderResource.LOADER_SHA);
        proof.addProperty("runtimeProviderClass", authenticated == null ? "" : authenticated.getName());
        proof.addProperty("observedProviderClassNodeMajor", observedProviderNodeMajor);
        proof.addProperty("historicalBaseSha256", BotaniaProviderResource.BASE_SHA);
        proof.addProperty("runtimeClassCodeSourceVerified", authenticated != null);
        proof.addProperty("resourceSelected", selected != null);
        if (selected != null) {
            proof.addProperty("sourceJar", selected.jar.toString());
            proof.addProperty("resourceUrl", selected.resourceUrl);
            proof.addProperty("jarEntryRealName", selected.realEntry);
        }
        proof.addProperty("failure", failure == null ? "" : failure);
        return proof;
    }

    public static void observationFailed(Throwable problem) {
        fail("RUNTIME_OBSERVATION", problem);
    }

    private static synchronized void fail(String phase, Throwable problem) {
        if (failure == null) failure = phase + ": " + problem;
        System.out.println("TLMNH_QA_BOTANIA_SHEDDING_FAILURE " + phase + " " + problem);
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
