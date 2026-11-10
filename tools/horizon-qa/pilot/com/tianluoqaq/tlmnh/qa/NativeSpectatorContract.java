package com.tianluoqaq.tlmnh.qa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldSettings.GameType;

import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.development.PackagedClassResourceResolver;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** 固定空气场的公开原生旁观查询，只读实际身份与行为，不写玩家状态 */
final class NativeSpectatorContract {

    private static final String TYPE = "ganymedes01.etfuturum.spectator.SpectatorMode";
    private static final String VERSION = "2.6.65-GTNH";
    private static final String ARTIFACT_SHA = "dc1e1b813cdf783f9fc94216bb46fe007877c0270d52ea71f29ce0e0c86067b5";
    private static final String CLASS_SHA = "fd8d6129dc713f810225eb1db4971a9e223e93d9c9588532cc5f6d7de154c154";

    private final GameType mode;
    private final Method isSpectator;
    final JsonObject source;

    private NativeSpectatorContract(GameType mode, Method isSpectator, JsonObject source) {
        this.mode = mode;
        this.isSpectator = isSpectator;
        this.source = source;
    }

    static NativeSpectatorContract open(Path runtime, JsonObject files) throws Exception {
        require(
            Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaCombatNetwork"),
            "Explicit native network QA required");
        Class<?> type = Class.forName(TYPE, false, NativeSpectatorContract.class.getClassLoader());
        Path root = runtime.toRealPath(), artifact = PackagedClassResourceResolver.artifactOf(type)
            .toRealPath();
        require(artifact.startsWith(root) && Files.isRegularFile(artifact), "Unowned spectator API artifact");
        String relative = root.relativize(artifact)
            .toString()
            .replace('\\', '/');
        JsonObject identity = DevelopmentArtifactIdentity.capture(type);
        require(
            ARTIFACT_SHA.equals(
                identity.get("artifactSha256")
                    .getAsString())
                && files != null
                && files.has(relative)
                && ARTIFACT_SHA.equals(
                    files.get(relative)
                        .getAsString()),
            "Actual spectator artifact differs from the reviewed frozen input");
        require(
            CLASS_SHA.equals(
                identity.getAsJsonObject("classResources")
                    .get(TYPE)
                    .getAsString()),
            "Actual spectator public API class resource changed");
        JsonObject binding = identity.getAsJsonObject("classResourceBindings")
            .getAsJsonObject(TYPE);
        require(
            (TYPE.replace('.', '/') + ".class").equals(
                binding.get("logicalEntry")
                    .getAsString())
                && binding.get("logicalEntry")
                    .getAsString()
                    .equals(
                        binding.get("physicalEntry")
                            .getAsString())
                && binding.get("multiReleaseVersion")
                    .getAsInt() == 0,
            "Unexpected spectator physical class binding");
        ModContainer container = Loader.instance()
            .getIndexedModList()
            .get("etfuturum");
        require(
            container != null && "cpw.mods.fml.common.FMLModContainer".equals(
                container.getClass()
                    .getName())
                && "etfuturum".equals(container.getModId())
                && VERSION.equals(container.getVersion())
                && container.getSource() != null
                && container.getSource()
                    .toPath()
                    .toRealPath()
                    .equals(artifact)
                && container.getMod() != null
                && "ganymedes01.etfuturum.EtFuturum".equals(
                    container.getMod()
                        .getClass()
                        .getName())
                && PackagedClassResourceResolver.artifactOf(
                    container.getMod()
                        .getClass())
                    .equals(artifact),
            "Actual spectator Loader identity/source mismatch");

        Field field = type.getField("SPECTATOR_GAMETYPE");
        Method query = type.getMethod("isSpectator", EntityPlayer.class);
        require(
            field.getDeclaringClass() == type && field.getType() == GameType.class
                && Modifier.isPublic(field.getModifiers())
                && Modifier.isStatic(field.getModifiers())
                && query.getDeclaringClass() == type
                && query.getReturnType() == boolean.class
                && Modifier.isPublic(query.getModifiers())
                && Modifier.isStatic(query.getModifiers()),
            "Reviewed public spectator API signature changed");
        Object nativeMode = field.get(null);
        require(
            nativeMode instanceof GameType && ((GameType) nativeMode).getID() == 3 && nativeMode == GameType.getByID(3),
            "Actual spectator enum is not the reviewed native GameType3 object");
        JsonObject source = new JsonObject();
        source.addProperty("schema", "fixed-air-native-spectator-source-v1");
        source.addProperty("loaderModId", container.getModId());
        source.addProperty("loaderVersion", container.getVersion());
        source.addProperty("relativeArtifact", relative);
        source.addProperty(
            "codeSourceUrl",
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toExternalForm());
        source.addProperty(
            "codeSourceProtocol",
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getProtocol());
        source.addProperty("publicEnumField", field.toString());
        source.addProperty("publicQuery", query.toString());
        source.addProperty("enumName", ((GameType) nativeMode).name());
        source.addProperty("enumIdentity", System.identityHashCode(nativeMode));
        source.addProperty("transformedDefinitionBytesVerified", false);
        source.addProperty("scope", "Original fixed air observation volume; no arbitrary-terrain ghost movement claim");
        source.add("artifact", identity);
        source.addProperty("authenticated", true);
        return new NativeSpectatorContract((GameType) nativeMode, query, source);
    }

    Observation observe(EntityPlayerMP player) throws Exception {
        require(player != null && player.worldObj != null, "Actual native spectator player/world required");
        Object queried = isSpectator.invoke(null, player);
        require(queried instanceof Boolean, "Native spectator query did not return a boolean");
        GameType actualMode = player.theItemInWorldManager.getGameType();
        boolean sameMode = actualMode == mode, nativeSpectator = (Boolean) queried;
        boolean remote = player.worldObj.isRemote;
        boolean collidable = player.canBeCollidedWith(), pushable = player.canBePushed();
        JsonObject evidence = new JsonObject();
        evidence.addProperty("enumSameObject", sameMode);
        evidence.addProperty("actualGameType", actualMode.getID());
        evidence.addProperty("actualEnumName", actualMode.name());
        evidence.addProperty("actualEnumIdentity", System.identityHashCode(actualMode));
        evidence.addProperty("nativeIsSpectator", nativeSpectator);
        evidence.addProperty("worldIsRemote", remote);
        evidence.addProperty("canBeCollidedWith", collidable);
        evidence.addProperty("canBePushed", pushable);
        evidence.addProperty("rawNoClip", player.noClip);
        boolean accepted = sameMode && nativeSpectator && !remote && !collidable && !pushable;
        evidence.addProperty("nativeBehaviorAccepted", accepted);
        return new Observation(accepted, evidence);
    }

    static final class Observation {

        final boolean accepted;
        final JsonObject evidence;

        Observation(boolean accepted, JsonObject evidence) {
            this.accepted = accepted;
            this.evidence = evidence;
        }
    }

    private static void require(boolean allowed, String reason) {
        if (!allowed) throw new IllegalStateException(reason);
    }
}
