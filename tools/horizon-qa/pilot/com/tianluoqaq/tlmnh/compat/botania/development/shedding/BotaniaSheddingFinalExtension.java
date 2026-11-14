package com.tianluoqaq.tlmnh.compat.botania.development.shedding;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.llamalad7.mixinextras.injector.LateInjectionApplicatorExtension;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethodApplicatorExtension;
import com.llamalad7.mixinextras.utils.MixinInternals;

/** Observes the exact Entity node after both actual MixinExtras injection extensions. */
public final class BotaniaSheddingFinalExtension implements IExtension {

    public static final String REVISION = "post-mixinextras-native-entity-node-v1";
    public static final String API_JAR_SHA = "ad0ea4f92daf7bf7ec5c10e16258425e47929126954aea5d80df69ce26cd7318";
    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final BotaniaSheddingFinalExtension OBSERVER = new BotaniaSheddingFinalExtension();
    private static boolean installed;
    private static ClassNode merged;
    private static Thread mergedThread;
    private static boolean complete;

    private BotaniaSheddingFinalExtension() {}

    static synchronized void install() throws Exception {
        require(!installed, "Duplicate final Entity observer installation");
        Path jar = BotaniaSheddingBootstrap.expectedDeclaredJar("mods/+unimixins-all-1.7.10-0.3.1.jar", API_JAR_SHA);
        verifyApiSource(
            MixinExtrasBootstrap.class,
            jar,
            "686e57c6ce82d8bb2a24bf46a03b7cd6be83dce215df39aeb6841259a39ab609");
        verifyApiSource(MixinInternals.class, jar, "1bd1d554350a37cf49a387f702e913311d567f1afafb99546d2f68ec786e229a");
        verifyApiSource(
            LateInjectionApplicatorExtension.class,
            jar,
            "b222b26126e0ffff36a7ba4e059c473a26c5c9a34cd0c91db3911a12e9f4e2a3");
        verifyApiSource(
            WrapMethodApplicatorExtension.class,
            jar,
            "ac6819f03f640a17a9102bdefd5cc0c6684d9a27b553af824113070c01c155b8");
        verifyApiSource(Extensions.class, jar, "17fb7e56ef481708d0d3841926c13dad37f5cfd384e9dce2f026ce337eb029bd");
        MixinExtrasBootstrap.init();
        MixinInternals.registerExtension(OBSERVER);
        verifyOrder(
            MixinInternals.getExtensions()
                .getExtensions(),
            OBSERVER);
        verifyOrder(
            MixinInternals.getExtensions()
                .getActiveExtensions(),
            OBSERVER);
        installed = true;
        System.out.println("TLMNH_QA_BOTANIA_NATIVE_EXTENSION_INSTALLED " + REVISION);
    }

    public static void verifyApiSource(Class<?> type, Path expectedJar, String expectedClassSha) throws Exception {
        Path actual = BotaniaProviderResource.sourceFile(
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation());
        require(
            actual.equals(expectedJar.toRealPath())
                && API_JAR_SHA.equals(BotaniaProviderResource.hash(Files.readAllBytes(actual))),
            "FINAL_EXTENSION_API_JAR_MISMATCH " + type.getName() + " " + actual);
        String resource = "/" + type.getName()
            .replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            require(
                input != null
                    && expectedClassSha.equals(BotaniaProviderResource.hash(BotaniaProviderResource.read(input))),
                "FINAL_EXTENSION_API_RESOURCE_MISMATCH " + type.getName());
        }
    }

    /** The order is checked at installation and again in the actual target callbacks. */
    public static void verifyOrder(List<IExtension> extensions, IExtension observer) {
        int late = -1, wrap = -1, own = -1;
        for (int i = 0; i < extensions.size(); i++) {
            IExtension extension = extensions.get(i);
            if (extension.getClass() == LateInjectionApplicatorExtension.class) {
                require(late == -1, "Duplicate actual late-injection extension");
                late = i;
            }
            if (extension.getClass() == WrapMethodApplicatorExtension.class) {
                require(wrap == -1, "Duplicate actual wrap-method extension");
                wrap = i;
            }
            if (extension == observer) {
                require(own == -1, "Duplicate actual Entity observer extension");
                own = i;
            }
        }
        require(
            late >= 0 && wrap > late && own > wrap,
            "FINAL_ENTITY_EXTENSION_ORDER_MISMATCH late=" + late + " wrap=" + wrap + " observer=" + own);
    }

    static synchronized void merged(ClassNode node) {
        require(
            installed && merged == null && !complete && ENTITY.equals(node.name),
            "Unexpected Entity merge checkpoint");
        merged = node;
        mergedThread = Thread.currentThread();
        System.out.println("TLMNH_QA_BOTANIA_NATIVE_ENTITY_MERGED " + REVISION);
    }

    public static BotaniaSheddingBridge inspectFinalNode(ClassNode expected, ClassNode actual, Thread thread) {
        require(
            expected != null && actual == expected && thread == Thread.currentThread() && ENTITY.equals(actual.name),
            "FINAL_ENTITY_NODE_OR_THREAD_MISMATCH");
        return BotaniaSheddingBridge.inspect(actual);
    }

    public boolean checkActive(MixinEnvironment environment) {
        return BotaniaSheddingBootstrap.requested();
    }

    public void preApply(ITargetClassContext context) {
        if (!ENTITY.equals(context.getClassNode().name)) return;
        require(installed && !complete && merged == null, "Final Entity observer entered at wrong phase");
        verifyOrder(
            MixinInternals.getExtensions()
                .getActiveExtensions(),
            this);
    }

    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        if (!ENTITY.equals(node.name)) return;
        try {
            require(installed && !complete, "Duplicate/uninstalled final Entity observation");
            verifyOrder(
                MixinInternals.getExtensions()
                    .getActiveExtensions(),
                this);
            inspectFinalNode(merged, node, mergedThread);
            BotaniaSheddingBridge.register(node);
            complete = true;
            merged = null;
            mergedThread = null;
            BotaniaSheddingBootstrap.entityFinalApplied();
            JsonObject proof = new JsonObject();
            proof.addProperty("revision", REVISION);
            proof.addProperty("phase", "MIXIN_EXTENSIONS_POST_APPLY");
            proof.addProperty("actualNodeIdentityMatched", true);
            proof.addProperty("afterLateInjectionAndWrapMethod", true);
            proof.addProperty("definedClassBytesVerified", false);
            JsonArray order = new JsonArray();
            for (IExtension extension : MixinInternals.getExtensions()
                .getActiveExtensions())
                order.add(
                    new com.google.gson.JsonPrimitive(
                        extension.getClass()
                            .getName()));
            proof.add("activeExtensions", order);
            System.out.println("TLMNH_QA_BOTANIA_NATIVE_EXTENSION_APPLIED " + proof);
        } catch (RuntimeException | LinkageError problem) {
            BotaniaSheddingBootstrap.finalEntityFailed(problem);
            throw new IllegalStateException("Final injected Entity observation failed", problem);
        }
    }

    public void export(MixinEnvironment environment, String name, boolean force, ClassNode node) {}

    static boolean verified() {
        return installed && complete;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
