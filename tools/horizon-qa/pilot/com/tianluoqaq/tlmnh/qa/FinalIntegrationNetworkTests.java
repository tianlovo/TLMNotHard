package com.tianluoqaq.tlmnh.qa;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;
import com.gtnewhorizons.horizonqa.internal.GameTestChunkLoader;
import com.gtnewhorizons.horizonqa.world.VoidChunkProvider;
import com.tianluoqaq.tlmnh.common.compat.CompatServices;
import com.tianluoqaq.tlmnh.common.config.MaidBehaviorConfig;
import com.tianluoqaq.tlmnh.common.development.DevelopmentArtifactIdentity;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventoryLayout;
import com.tianluoqaq.tlmnh.common.work.bell.MaintenanceBellData;
import com.tianluoqaq.tlmnh.common.work.coordinator.MaidWorkItems;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;

/** 独立专服、真实两连接和标准容器/运动协议的组合验收 */
@GameTestHolder(value = "tlmnhqapilot", requiredMods = { "tlmnh", "horizonqa", "etfuturum" })
public final class FinalIntegrationNetworkTests {

    private FinalIntegrationNetworkTests() {}

    @GameTest(template = "modern_core_cell", timeoutTicks = 18000, batch = "final_real_two_client_network")
    public static void realClientsObserveInventoryWorkFlightAndTrackingLifecycles(GameTestHelper h) throws Exception {
        var scene = new Scene(h);
        h.afterTest(scene::close);
        scene.start();
        h.onEachTick("real two-client final combination", () -> {
            try {
                scene.tick();
            } catch (Exception failure) {
                scene.failure = failure.toString();
                scene.write();
                throw new IllegalStateException("Final native network scene failed", failure);
            }
        });
        h.succeedWhen(() -> scene.completed);
    }

    public static final class Scene {

        private static final int X = 512, Y = 100, Z = 512;
        final GameTestHelper h;
        final WorldServer world;
        final MinecraftServer server = MinecraftServer.getServer();
        final GameTestChunkLoader chunks = new GameTestChunkLoader();
        final Path output;
        final String batch;
        final List<EntityMaid> owned = new ArrayList<>();
        final List<ItemStack> flightInputs = new ArrayList<>();
        final List<int[]> blocks = new ArrayList<>();
        final boolean oldAnimals;
        final String oldRegen;
        final NativeSpectatorContract spectator;
        final JsonObject productionArtifact, pilotArtifact, devtoolsArtifact;
        EntityPlayerMP owner, observer;
        PlayerInputs ownerInputs, observerInputs;
        EntityMaid inventoryMaid;
        ItemStack bag, bell;
        String phase = "waiting-owner", failure;
        long phaseAt;
        int observerConnections;
        boolean completed, closed, registered, built, unknownObject;
        boolean ownedCleanupCompleted, chunkLeaseReleased, nativeRulesRestored;
        JsonObject lastState = new JsonObject();
        boolean ownerRestored, observerRestored, fixtureInputsReleased, inventoryReplaced;
        int bagInserted, bellInserted;
        String restoreRequestId;
        final java.util.Set<String> processedRestoreFiles = new java.util.HashSet<>();
        JsonObject ownerRestoration, observerRestoration;

        Scene(GameTestHelper h) throws Exception {
            this.h = h;
            require(
                Boolean.getBoolean("tlmnh.devCommands") && Boolean.getBoolean("tlmnh.qaFinalNetwork")
                    && "interactive".equals(System.getProperty("horizonqa.mode"))
                    && "void".equals(System.getProperty("horizonqa.world"))
                    && "true".equals(System.getProperty("horizonqa.autoRun"))
                    && "false".equals(System.getProperty("horizonqa.stopServer"))
                    && "1".equals(System.getProperty("horizonqa.turbo")),
                "Explicit isolated interactive final network policy required");
            batch = System.getProperty("tlmnh.qaFinalNetworkBatch", "");
            require(batch.matches("final-network-[0-9]+"), "Unique final batch required");
            require(
                MaidBehaviorConfig.profile()
                    .isEnhanced(),
                "Actual final server behavior must be Enhanced");
            output = Paths.get(System.getProperty("horizonqa.reportDir"))
                .toRealPath();
            world = h.getWorld();
            require(
                world.provider.dimensionId == 0
                    && world.getChunkProvider() instanceof net.minecraft.world.gen.ChunkProviderServer
                    && world.getWorldInfo()
                        .getWorldName()
                        .equals("TLMNH-" + batch),
                "Fresh dedicated world identity differs");
            require(
                ((net.minecraft.world.gen.ChunkProviderServer) world
                    .getChunkProvider()).currentChunkProvider instanceof VoidChunkProvider,
                "Actual Horizon-QA void chunk provider required");
            var inputs = new JsonParser()
                .parse(new String(Files.readAllBytes(output.resolve("runtime-inputs.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
            spectator = NativeSpectatorContract.open(Paths.get("."), inputs);
            productionArtifact = DevelopmentArtifactIdentity.capture(EntityMaid.class);
            pilotArtifact = DevelopmentArtifactIdentity.capture(
                FinalIntegrationNetworkTests.class,
                Scene.class,
                FinalNetworkStateSnapshot.class,
                FinalNetworkStateFiles.class);
            devtoolsArtifact = DevelopmentArtifactIdentity.capture(DevelopmentArtifactIdentity.class);
            oldAnimals = server.getCanSpawnAnimals();
            oldRegen = world.getGameRules()
                .getGameRuleStringValue("naturalRegeneration");
        }

        void start() throws Exception {
            require(
                server.getConfigurationManager().playerEntityList.isEmpty(),
                "Fresh independent server has an existing player");
            for (int center : new int[] { X, X + 176 }) {
                chunks.forceChunksStrict(world, center - 48, 0, Z - 48, center + 47, 255, Z + 47);
                for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                    for (int dy = 0; dy < 35; dy++)
                        require(world.isAirBlock(center + dx, Y + dy, Z + dz), "Occupied network safety volume");
                    require(
                        world.setBlock(center + dx, Y, Z + dz, Blocks.wool, 0, 2),
                        "Safety platform placement failed");
                    blocks.add(new int[] { center + dx, Y, Z + dz });
                }
            }
            built = true;
            server.setCanSpawnAnimals(true);
            world.getGameRules()
                .setOrCreateGameRule("naturalRegeneration", "false");
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance()
                .bus()
                .register(this);
            registered = true;
            phaseAt = world.getTotalWorldTime();
            write();
        }

        @SubscribeEvent
        public void login(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.player instanceof EntityPlayerMP actual)) return;
            try {
                String name = actual.getCommandSenderName();
                require(name.equals("TLMNHTest") || name.equals("TLMNHObserver"), "Unexpected real player preserved");
                connection(actual);
                require(actual.worldObj == world, "Actual player joined another world");
                boolean primary = name.equals("TLMNHTest");
                protect(actual, primary);
                position(actual, X + (primary ? -2.5 : 2.5), Y + 1, Z - (primary ? 3.5 : 6.5));
                if (primary) {
                    require(owner == null, "Owner reconnection is outside this representative sequence");
                    owner = actual;
                    ownerInputs = new PlayerInputs(actual);
                } else {
                    require(
                        observer == null || !observer.playerNetServerHandler.netManager.isChannelOpen(),
                        "Duplicate real observer connection");
                    observer = actual;
                    observerConnections++;
                    observerRestored = false;
                    observerRestoration = null;
                    if (observerInputs == null) observerInputs = new PlayerInputs(actual);
                }
                write();
            } catch (Exception problem) {
                failure = problem.toString();
                write();
            }
        }

        @SubscribeEvent
        public void protectPlayer(TickEvent.PlayerTickEvent event) {
            if (event.side == Side.SERVER && event.phase == TickEvent.Phase.START
                && event.player instanceof EntityPlayerMP actual
                && ((actual == owner && !ownerRestored) || (actual == observer && !observerRestored)))
                protect(actual, actual == owner);
        }

        @SubscribeEvent
        public void joining(EntityJoinWorldEvent event) {
            if (closed || event.world != world || !within(event.entity)) return;
            if (event.entity == owner || event.entity == observer || owned.contains(event.entity)) return;
            if (event.entity instanceof EntityPlayerMP player && (player.getCommandSenderName()
                .equals("TLMNHTest")
                || player.getCommandSenderName()
                    .equals("TLMNHObserver")))
                return;
            unknownObject = true;
            failure = "Unknown native entity preserved: " + event.entity.getClass()
                .getName() + "/" + event.entity.getUniqueID();
        }

        void tick() throws Exception {
            try (var requests = Files.newDirectoryStream(output, "restore-request-*.json")) {
                for (Path restorePath : requests) {
                    if (processedRestoreFiles.contains(
                        restorePath.getFileName()
                            .toString()))
                        continue;
                    var request = new JsonParser()
                        .parse(new String(Files.readAllBytes(restorePath), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                    require(
                        batch.equals(
                            request.get("batch")
                                .getAsString()),
                        "Stale restore batch refused");
                    String id = request.get("id")
                        .getAsString();
                    if (!id.equals(restoreRequestId)) {
                        String kind = request.get("kind")
                            .getAsString();
                        if (kind.equals("observer")) {
                            require(
                                phase.equals("disconnect") && observerInputs != null,
                                "Observer restore is restricted to the actual disconnect boundary");
                            restoreObserver();
                        } else {
                            require(kind.equals("abort"), "Unknown restore operation refused");
                            restorePlayers();
                        }
                        restoreRequestId = id;
                        processedRestoreFiles.add(
                            restorePath.getFileName()
                                .toString());
                        write();
                        if (kind.equals("abort"))
                            throw new IllegalStateException("Driver requested failed-scene restoration");
                    }
                }
            }
            require(failure == null && !unknownObject, "Retained network failure: " + failure);
            for (Object raw : world.loadedEntityList) if (raw instanceof Entity entity && within(entity)
                && entity != owner
                && entity != observer
                && !owned.contains(entity))
                throw new IllegalStateException(
                    "Unknown existing entity preserved: " + entity.getClass()
                        .getName());
            if (owner != null) {
                connection(owner);
                require(owner.getHealth() == 20 && owner.capabilities.disableDamage, "Real owner protection failed");
            }
            if (observer != null && observer.playerNetServerHandler.netManager.isChannelOpen()) {
                connection(observer);
                require(
                    observer.getHealth() == 20 && spectator.observe(observer).accepted,
                    "Actual native spectator protection failed");
            }
            if (phase.equals("waiting-owner") && owner != null) {
                createInventory();
                enter("inventory");
            }
            Path next = output.resolve("advance-" + phase + ".json");
            if (Files.isRegularFile(next)) {
                var receipt = new JsonParser().parse(new String(Files.readAllBytes(next), StandardCharsets.UTF_8))
                    .getAsJsonObject();
                require(
                    receipt.get("batch")
                        .getAsString()
                        .equals(batch)
                        && receipt.get("phase")
                            .getAsString()
                            .equals(phase),
                    "Stale phase advance refused");
                advance();
            }
            write();
        }

        void createInventory() {
            require(owner.inventory.getItemStack() == null, "Owner cursor must be empty before fixtures");
            for (int slot = 0; slot < owner.inventory.getSizeInventory(); slot++)
                owner.inventory.setInventorySlotContents(slot, null);
            inventoryReplaced = true;
            inventoryMaid = maid("inventory", X, true);
            for (Object raw : Item.itemRegistry) if (raw instanceof Item item) {
                String name = Item.itemRegistry.getNameForObject(item);
                if (name == null || !name.startsWith("adventurebackpack:")) continue;
                var candidate = new ItemStack(item);
                if (!CompatServices.backpacks()
                    .isSupportedBackpack(candidate)) continue;
                if (CompatServices.backpacks()
                    .insertIntoBackpack(candidate, new ItemStack(Items.iron_ingot, 3), false) != null) continue;
                bag = tagged(candidate, "nested-backpack");
                break;
            }
            require(bag != null, "Actual registered nested backpack unavailable");
            bell = tagged(new ItemStack(MaidWorkItems.maintenanceBell), "work-bell");
            MaintenanceBellData.toggleChunk(bell, 0, X >> 4, Z >> 4);
            owner.inventory.setInventorySlotContents(0, bag.copy());
            bagInserted = 1;
            owner.inventory.setInventorySlotContents(1, bell.copy());
            bellInserted = 1;
            owner.inventory.currentItem = 8;
            owner.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(8));
            owner.inventoryContainer.detectAndSendChanges();
        }

        void advance() {
            switch (phase) {
                case "inventory" -> {
                    require(
                        ItemStack.areItemStacksEqual(
                            bag,
                            inventoryMaid.getMaidInventory()
                                .getStackInSlot(MaidInventoryLayout.BACKPACK_SHOW_SLOT))
                            && ItemStack.areItemStacksEqual(
                                bell,
                                inventoryMaid.getMaidBaubleInventory()
                                    .getStackInSlot(29))
                            && inventoryMaid.getTask() == MaidTask.MAINTENANCE_HATCH
                            && owner.inventory.getItemStack() == null,
                        "Real container/task actions did not reach server authority");
                    owner.closeScreen();
                    enter("late-join");
                }
                case "late-join" -> {
                    require(
                        observer != null && server.getConfigurationManager().playerEntityList.size() == 2,
                        "Two actual clients required");
                    connection(observer);
                    require(!connection(owner).equals(connection(observer)), "Distinct actual TCP endpoints required");
                    position(observer, X + 176.5, Y + 1, Z - 6.5);
                    enter("retrack-out");
                }
                case "retrack-out" -> {
                    position(observer, X + 2.5, Y + 1, Z - 6.5);
                    enter("retrack-back");
                }
                case "retrack-back" -> enter("disconnect");
                case "disconnect" -> {
                    require(
                        observer != null && !observer.playerNetServerHandler.netManager.isChannelOpen()
                            && !server.getConfigurationManager().playerEntityList.contains(observer),
                        "Actual observer TCP disconnect not complete");
                    enter("reconnect");
                }
                case "reconnect" -> {
                    require(
                        observerConnections == 2 && observer != null
                            && server.getConfigurationManager().playerEntityList.contains(observer),
                        "Actual same-identity observer relogin required");
                    createFlight();
                    enter("flight");
                }
                case "flight" -> {
                    require(owner.posY > Y + 5, "Actual owner client did not move upward");
                    for (int index = 1; index < owned.size(); index++) {
                        var maid = owned.get(index);
                        require(
                            maid.posY > Y + 3 && maid.getDistanceSqToEntity(owner) < 64
                                && maid.getFlightController()
                                    .isActivelyHoveringOrFlying()
                                && maid.getHealth() == 20
                                && ItemStack
                                    .areItemStacksEqual(flightInputs.get(index - 1), maid.getEquipmentInSlot(3)),
                            "Real flight family failed movement/zero-consumption: " + index);
                    }
                    enter("restoring");
                }
                case "restoring" -> {
                    restorePlayers();
                    completed = true;
                    enter("completed");
                }
                default -> throw new IllegalStateException("Unapproved final network phase advance");
            }
        }

        void createFlight() {
            owner.closeScreen();
            String[] classes = { "com.darkona.adventurebackpack.item.ItemCopterPack",
                "ic2.core.item.armor.ItemArmorJetpackElectric", "gravisuite.ItemAdvancedJetPack",
                "gravisuite.ItemAdvancedNanoChestPlate", "gravisuite.ItemGraviChestPlate" };
            for (int index = 0; index < classes.length; index++) {
                ItemStack equipment = null;
                for (Object raw : Item.itemRegistry) if (raw instanceof Item item && item.getClass()
                    .getName()
                    .equals(classes[index])) {
                        equipment = new ItemStack(item);
                        break;
                    }
                require(equipment != null, "Actual flight provider item absent: " + classes[index]);
                var maid = maid("flight-" + index, X - 4 + index * 2, false);
                maid.setCurrentItemOrArmor(3, equipment);
                flightInputs.add(
                    maid.getEquipmentInSlot(3)
                        .copy());
            }
        }

        EntityMaid maid(String suffix, double x, boolean sitting) {
            var maid = new EntityMaid(world);
            owned.add(maid);
            require(maid.bindTo(owner.getUniqueID()), "Real TCP owner binding failed");
            maid.setCustomNameTag("QA-" + batch + "-" + suffix);
            maid.setSitting(sitting);
            maid.setPickup(false);
            maid.setTask(MaidTask.IDLE);
            if (sitting) maid.setFavorability(
                maid.getFavorabilityManager()
                    .getPointByLevel(3));
            maid.getHomeArea()
                .setEnabled(false);
            maid.func_110163_bv();
            maid.setPosition(x + .5, Y + 1, Z);
            require(world.spawnEntityInWorld(maid), "Owned actual maid spawn rejected");
            return maid;
        }

        void enter(String next) {
            phase = next;
            phaseAt = world.getTotalWorldTime();
        }

        void write() {
            try {
                var row = new JsonObject();
                row.addProperty("schema", "final-real-network-server-v1");
                row.addProperty("batch", batch);
                row.addProperty("phase", phase);
                row.addProperty("phaseAt", phaseAt);
                row.addProperty("worldTime", world.getTotalWorldTime());
                row.addProperty("failure", failure);
                row.addProperty("unknownObjectObserved", unknownObject);
                row.addProperty("completed", completed);
                row.addProperty("closed", closed);
                row.addProperty("ownedCleanupCompleted", ownedCleanupCompleted);
                row.addProperty("chunkLeaseReleased", chunkLeaseReleased);
                row.addProperty("nativeRulesRestored", nativeRulesRestored);
                row.addProperty("dimension", world.provider.dimensionId);
                row.add("network", FinalNetworkStateSnapshot.serverNetwork(server));
                row.addProperty(
                    "behaviorProfile",
                    MaidBehaviorConfig.profile()
                        .name());
                row.addProperty("observerConnections", observerConnections);
                row.addProperty(
                    "inputsRestored",
                    ownerInputs != null && ownerRestored && (observerInputs == null || observerRestored));
                row.addProperty("restoreRequestId", restoreRequestId);
                var restorations = new JsonObject();
                if (ownerRestoration != null) restorations.add("owner", ownerRestoration);
                if (observerRestoration != null) restorations.add("observer", observerRestoration);
                row.add("playerRestorations", restorations);
                row.addProperty("baselineBoundary", "protected real login before fixture inputs");
                row.add("productionArtifact", productionArtifact);
                row.add("pilotArtifact", pilotArtifact);
                row.add("devtoolsArtifact", devtoolsArtifact);
                if (owner != null) {
                    row.add("owner", player(owner));
                    row.add("ownerContainer", FinalNetworkStateSnapshot.container(owner));
                }
                if (observer != null) row.add("observer", player(observer));
                var maids = new JsonArray();
                for (var maid : owned) if (!maid.isDead) maids.add(FinalNetworkStateSnapshot.maid(maid));
                row.add("maids", maids);
                row.addProperty("expectedBagNativeNbtBase64", FinalNetworkStateSnapshot.item(bag));
                row.addProperty("expectedBellNativeNbtBase64", FinalNetworkStateSnapshot.item(bell));
                FinalNetworkStateFiles.publish(output.resolve("final-network-state.json"), row.toString());
                lastState = row;
            } catch (Exception problem) {
                throw new IllegalStateException("Cannot publish actual final server state", problem);
            }
        }

        void restoreObserver() {
            if (observerRestored) return;
            observerInputs.verifyInventory(observer);
            observerRestoration = observerInputs.restore(observer);
            observerRestored = true;
        }

        void restorePlayers() {
            if (ownerInputs != null && !ownerRestored) {
                ownerInputs.verifyIdentity(owner);
                if (inventoryReplaced) verifyGeneratedItems();
                else ownerInputs.verifyInventory(owner);
                if (observerInputs != null && !observerRestored) observerInputs.verifyInventory(observer);
                if (inventoryReplaced) {
                    owner.inventory.setItemStack(null);
                    for (var maid : owned) {
                        for (int slot = 0; slot < maid.getMaidInventory()
                            .getSizeInventory(); slot++)
                            maid.getMaidInventory()
                                .setInventorySlotContents(slot, null);
                        for (int slot = 0; slot < maid.getMaidBaubleInventory()
                            .getSizeInventory(); slot++)
                            maid.getMaidBaubleInventory()
                                .setInventorySlotContents(slot, null);
                    }
                }
                ownerRestoration = ownerInputs.restore(owner);
                ownerRestored = true;
                fixtureInputsReleased = true;
            }
            if (observerInputs != null) restoreObserver();
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                restorePlayers();
                if (fixtureInputsReleased) for (var maid : owned) {
                    for (int slot = 0; slot < maid.getMaidInventory()
                        .getSizeInventory(); slot++)
                        require(
                            maid.getMaidInventory()
                                .getStackInSlot(slot) == null,
                            "Unknown maid inventory successor preserved after restoration");
                    for (int slot = 0; slot < maid.getMaidBaubleInventory()
                        .getSizeInventory(); slot++)
                        require(
                            maid.getMaidBaubleInventory()
                                .getStackInSlot(slot) == null,
                            "Unknown maid bauble successor preserved after restoration");
                }
                for (var maid : owned) {
                    Entity current = world.getEntityByID(maid.getEntityId());
                    require(current == null || current == maid, "Cleanup refused foreign entity successor");
                    world.removePlayerEntityDangerously(maid);
                    require(
                        world.getEntityByID(maid.getEntityId()) == null,
                        "Owned maid remains indexed after native cleanup");
                }
                for (var at : blocks) {
                    require(
                        world.getBlock(at[0], at[1], at[2]) == Blocks.wool
                            && world.getBlockMetadata(at[0], at[1], at[2]) == 0,
                        "Cleanup refused changed safety platform");
                    world.setBlockToAir(at[0], at[1], at[2]);
                }
                ownedCleanupCompleted = true;
            } finally {
                if (registered) {
                    MinecraftForge.EVENT_BUS.unregister(this);
                    FMLCommonHandler.instance()
                        .bus()
                        .unregister(this);
                }
                world.getGameRules()
                    .setOrCreateGameRule("naturalRegeneration", oldRegen);
                server.setCanSpawnAnimals(oldAnimals);
                nativeRulesRestored = oldRegen.equals(
                    world.getGameRules()
                        .getGameRuleStringValue("naturalRegeneration"))
                    && server.getCanSpawnAnimals() == oldAnimals;
                chunks.releaseAll();
                chunkLeaseReleased = true;
                write();
            }
        }

        void verifyGeneratedItems() {
            int[] counts = new int[2 + flightInputs.size()];
            for (int slot = 0; slot < owner.inventory.getSizeInventory(); slot++)
                countItem(owner.inventory.getStackInSlot(slot), counts);
            countItem(owner.inventory.getItemStack(), counts);
            for (var maid : owned) {
                for (int slot = 0; slot < maid.getMaidInventory()
                    .getSizeInventory(); slot++)
                    countItem(
                        maid.getMaidInventory()
                            .getStackInSlot(slot),
                        counts);
                for (int slot = 0; slot < maid.getMaidBaubleInventory()
                    .getSizeInventory(); slot++)
                    countItem(
                        maid.getMaidBaubleInventory()
                            .getStackInSlot(slot),
                        counts);
            }
            require(
                counts[0] == bagInserted && counts[1] == bellInserted,
                "Generated bag/bell input was lost or duplicated");
            for (int index = 2; index < counts.length; index++)
                require(counts[index] == 1, "Generated flight input was lost or duplicated");
        }

        void countItem(ItemStack stack, int[] counts) {
            if (stack == null) return;
            if (ItemStack.areItemStacksEqual(stack, bag)) {
                counts[0]++;
                return;
            }
            if (ItemStack.areItemStacksEqual(stack, bell)) {
                counts[1]++;
                return;
            }
            for (int i = 0; i < flightInputs.size(); i++)
                if (ItemStack.areItemStacksEqual(stack, flightInputs.get(i))) {
                    counts[2 + i]++;
                    return;
                }
            throw new IllegalStateException("Unknown inventory successor preserved before cleanup");
        }

        JsonObject player(EntityPlayerMP player) {
            var row = new JsonObject();
            row.addProperty("name", player.getCommandSenderName());
            row.addProperty(
                "uuid",
                player.getUniqueID()
                    .toString());
            row.addProperty("objectIdentity", System.identityHashCode(player));
            row.addProperty(
                "tcp",
                player.playerNetServerHandler.netManager.getSocketAddress()
                    .toString());
            row.addProperty("connected", player.playerNetServerHandler.netManager.isChannelOpen());
            row.addProperty("registered", server.getConfigurationManager().playerEntityList.contains(player));
            row.addProperty(
                "mode",
                player.theItemInWorldManager.getGameType()
                    .getID());
            row.addProperty("health", player.getHealth());
            row.addProperty("invulnerable", player.capabilities.disableDamage);
            row.addProperty("flying", player.capabilities.isFlying);
            row.addProperty("x", player.posX);
            row.addProperty("y", player.posY);
            row.addProperty("z", player.posZ);
            return row;
        }

        static String connection(EntityPlayerMP player) {
            require(
                player.getClass() == EntityPlayerMP.class && player.playerNetServerHandler != null
                    && player.playerNetServerHandler.netManager.isChannelOpen()
                    && player.playerNetServerHandler.netManager.getSocketAddress() instanceof InetSocketAddress address
                    && address.getAddress()
                        .isLoopbackAddress(),
                "Exact native real loopback TCP player required");
            return player.playerNetServerHandler.netManager.getSocketAddress()
                .toString();
        }

        static void protect(EntityPlayerMP player, boolean primary) {
            GameType mode = primary ? GameType.SURVIVAL : GameType.getByID(3);
            if (player.theItemInWorldManager.getGameType() != mode) player.setGameType(mode);
            player.capabilities.disableDamage = true;
            if (primary) player.capabilities.allowFlying = player.capabilities.isFlying = true;
            player.fallDistance = 0;
            player.sendPlayerAbilities();
        }

        static void position(EntityPlayerMP player, double x, double y, double z) {
            player.motionX = player.motionY = player.motionZ = 0;
            player.playerNetServerHandler.setPlayerLocation(x, y, z, 0, 0);
        }

        static boolean within(Entity entity) {
            return (Math.abs(entity.posX - X) <= 32 || Math.abs(entity.posX - (X + 176)) <= 32)
                && Math.abs(entity.posZ - Z) <= 32;
        }

        ItemStack tagged(ItemStack stack, String suffix) {
            stack.setStackDisplayName("QA-" + batch + "-" + suffix);
            return stack;
        }
    }

    private static final class PlayerInputs {

        final NBTTagList inventory = new NBTTagList();
        final NBTTagCompound abilities = new NBTTagCompound();
        final int selected;
        final double x, y, z;
        final float yaw, pitch;
        final GameType mode;
        final java.util.UUID uuid;
        final WorldServer world;
        final JsonObject expected;

        PlayerInputs(EntityPlayerMP player) {
            require(player.inventory.getItemStack() == null, "Initial real player cursor is occupied");
            player.inventory.writeToNBT(inventory);
            player.capabilities.writeCapabilitiesToNBT(abilities);
            selected = player.inventory.currentItem;
            x = player.posX;
            y = player.posY;
            z = player.posZ;
            yaw = player.rotationYaw;
            pitch = player.rotationPitch;
            mode = player.theItemInWorldManager.getGameType();
            uuid = player.getUniqueID();
            world = (WorldServer) player.worldObj;
            expected = FinalNetworkStateSnapshot.playerInputs(player);
        }

        void verifyIdentity(EntityPlayerMP player) {
            Scene.connection(player);
            require(
                player.getUniqueID()
                    .equals(uuid) && player.worldObj == world
                    && MinecraftServer.getServer()
                        .getConfigurationManager().playerEntityList.contains(player),
                "Restoration requires the exact admitted real player identity and world");
        }

        void verifyInventory(EntityPlayerMP player) {
            verifyIdentity(player);
            var current = new NBTTagList();
            player.inventory.writeToNBT(current);
            require(
                current.equals(inventory) && player.inventory.getItemStack() == null,
                "Unknown player inventory successor retained before restoration");
        }

        JsonObject restore(EntityPlayerMP player) {
            verifyIdentity(player);
            require(player.inventory.getItemStack() == null, "Unknown active cursor retained before restoration");
            player.closeScreen();
            player.inventory.readFromNBT((NBTTagList) inventory.copy());
            player.inventory.currentItem = selected;
            player.playerNetServerHandler.sendPacket(new S09PacketHeldItemChange(selected));
            player.setGameType(mode);
            player.capabilities.readCapabilitiesFromNBT((NBTTagCompound) abilities.copy());
            player.sendPlayerAbilities();
            player.playerNetServerHandler.setPlayerLocation(x, y, z, yaw, pitch);
            player.inventoryContainer.detectAndSendChanges();
            var after = new NBTTagList();
            player.inventory.writeToNBT(after);
            var afterAbilities = new NBTTagCompound();
            player.capabilities.writeCapabilitiesToNBT(afterAbilities);
            require(
                after.equals(inventory) && player.inventory.currentItem == selected
                    && afterAbilities.equals(abilities)
                    && player.theItemInWorldManager.getGameType() == mode,
                "Real player inventory/abilities restoration differs");
            require(
                player.posX == x && player.posY == y
                    && player.posZ == z
                    && player.rotationYaw == yaw
                    && player.rotationPitch == pitch,
                "Real player position/orientation restoration differs");
            var receipt = new JsonObject();
            receipt.add("expected", expected);
            receipt.add("serverActual", FinalNetworkStateSnapshot.playerInputs(player));
            receipt.addProperty("serverVerified", true);
            receipt.addProperty("playerObjectIdentity", System.identityHashCode(player));
            return receipt;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
