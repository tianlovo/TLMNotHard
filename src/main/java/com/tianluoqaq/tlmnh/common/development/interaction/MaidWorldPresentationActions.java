package com.tianluoqaq.tlmnh.common.development.interaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.common.util.Constants;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.ai.faithful.FollowMoveState;
import com.tianluoqaq.tlmnh.common.ai.faithful.MaidFaithfulBrain;
import com.tianluoqaq.tlmnh.common.ai.faithful.ModernFollowCoreActor;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.entity.MaidPose;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.life.MaidLifeRules;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** Finite world inputs: the full native brain supplies actions; this fixture never writes a render pose. */
final class MaidWorldPresentationActions {

    private static final String KEY = "WorldPresentation";
    private static final Map<String, Attempt> LIVE = new HashMap<>();
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList("Schema", "Kind", "Stage", "Inventory", "Cells", "CakeBites"));

    private MaidWorldPresentationActions() {}

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static boolean handles(String action) {
        return action.startsWith("world-scene-");
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(
            data.hasKey(KEY, Constants.NBT.TAG_COMPOUND) && data.getString("Phase")
                .equals("world-presentation"),
            "Unknown world presentation snapshot retained");
        var row = data.getCompoundTag(KEY);
        for (Object field : row.func_150296_c()) require(FIELDS.contains(field), "Unknown presentation field retained");
        require(
            row.hasKey("Schema", Constants.NBT.TAG_INT) && row.getInteger("Schema") == 1
                && row.hasKey("Kind", Constants.NBT.TAG_STRING)
                && row.hasKey("Stage", Constants.NBT.TAG_STRING)
                && row.hasKey("Inventory", Constants.NBT.TAG_LIST)
                && row.hasKey("Cells", Constants.NBT.TAG_LIST),
            "Incomplete presentation scope retained");
        if (row.hasKey("CakeBites")) require(
            row.hasKey("CakeBites", Constants.NBT.TAG_INT) && row.getInteger("CakeBites") >= 0
                && row.getInteger("CakeBites") <= 6
                && row.getString("Kind")
                    .equals("cake"),
            "Unknown persisted cake progress retained");
    }

    private static Attempt attempt(Session session) {
        validateSnapshot(session.data);
        var attempt = LIVE.get(session.data.getString("Run"));
        require(
            attempt != null && attempt.session == session && MaidCareInteractionFixture.owns(session),
            "Original live presentation scope required; backups retained");
        return attempt;
    }

    static boolean isSpawning(Entity entity) {
        for (var attempt : LIVE.values()) {
            if (!MaidCareInteractionFixture.owns(attempt.session) || entity.worldObj != attempt.session.player.worldObj)
                continue;
            if (entity == attempt.cow) return true;
            if (entity instanceof EntityArrow arrow && arrow.shootingEntity == attempt.session.maid
                && attempt.kind.equals("bow")
                && !attempt.row.getString("Stage")
                    .equals("stopped")) {
                if (!attempt.arrows.contains(arrow)) attempt.arrows.add(arrow);
                return true;
            }
        }
        return false;
    }

    /** Restore-only reattachment; it never creates an entity, starts a factory or edits the original snapshot. */
    static void recover(Session session) {
        if (!session.data.hasKey(KEY)) return;
        session.validateSnapshot();
        session.validatePlayer();
        session.inventoryOwned();
        require(
            MaidCareInteractionFixture.owns(session) && session.validNonce(session.maid)
                && session.player.worldObj == session.maid.worldObj
                && session.maid.worldObj.getEntityByID(session.maid.getEntityId()) == session.maid
                && session.maid.isEntityAlive()
                && session.maid.isOwner(session.player)
                && session.maid.getModelId()
                    .equals(session.data.getString("ExpectedModel"))
                && session.maid.getCustomNameTag()
                    .isEmpty(),
            "Exact loaded presentation actor required");
        require(
            session.maid.getMaidInventory()
                .recovery()
                .write()
                .tagCount() == 0,
            "Foreign recovery contents retained");
        String run = session.data.getString("Run");
        var retained = LIVE.get(run);
        if (retained != null) {
            if (retained.session == session) return;
            require(
                retained.session.player == session.player && retained.session.maid == session.maid
                    && retained.session.data == session.data,
                "Unknown retained presentation session successor");
            retained.verify();
            retained.session = session;
            return;
        }
        for (String key : new String[] { "Owner", "Actor", "Run" }) require(
            session.data.getString(key)
                .equals(System.getProperty("tlmnh.careRestore" + key)),
            "Exact one-start recovery identity required");
        require(
            session.player.theItemInWorldManager.getGameType()
                .getID() == 3
                && session.data.getInteger("OriginalMode") == 3
                && session.data.getTag("ExpectedInventory")
                    .equals(session.data.getTag("ResultInventory")),
            "Protected original restore-only player required");
        verifyOriginalPlayerPlan(session);
        var row = session.data.getCompoundTag(KEY);
        String kind = row.getString("Kind");
        require(
            Arrays.asList("cake", "sleep", "light")
                .contains(kind),
            "Transient target/projectile context was not persisted; original scope retained");
        var restored = new Attempt(session, kind, row);
        restored.recoveryOnly = true;
        var inventory = row.getTagList("Inventory", Constants.NBT.TAG_COMPOUND);
        require(inventory.tagCount() == 12, "Complete original presentation inventory required");
        for (int index = 0; index < 12; index++) {
            var tag = inventory.getCompoundTagAt(index);
            require(
                tag.hasKey("Slot", Constants.NBT.TAG_INT) && tag.getInteger("Slot") == index,
                "Unknown original presentation slot retained");
            var item = (NBTTagCompound) tag.copy();
            item.removeTag("Slot");
            if (item.hasNoTags()) continue;
            var stack = ItemStack.loadItemStackFromNBT(item);
            require(
                index == 5 && stack != null
                    && stack.getItem() == Items.compass
                    && stack.stackSize == 1
                    && stack.getItemDamage() == 0
                    && stack.hasTagCompound()
                    && run.equals(
                        stack.getTagCompound()
                            .getString(KEY))
                    && raw(stack).equals(item),
                "Unknown complete original presentation item retained");
            restored.equipment[index] = stack;
        }
        require(
            restored.equipment[5] != null && session.maid.getItemUseState()
                .peek() == null,
            "Original empty-use presentation equipment required");
        var cells = row.getTagList("Cells", Constants.NBT.TAG_COMPOUND);
        require(cells.tagCount() > 0 && cells.tagCount() <= 160, "Bounded original presentation cells required");
        var positions = new HashSet<String>();
        for (int index = 0; index < cells.tagCount(); index++) {
            var tag = cells.getCompoundTagAt(index);
            for (Object field : tag.func_150296_c()) require(
                Arrays.asList("X", "Y", "Z", "Block", "Metadata", "Tile")
                    .contains(field),
                "Unknown original cell field retained");
            for (String field : new String[] { "X", "Y", "Z", "Block", "Metadata" })
                require(tag.hasKey(field, Constants.NBT.TAG_INT), "Unknown original cell scalar retained");
            int x = tag.getInteger("X"), y = tag.getInteger("Y"), z = tag.getInteger("Z");
            require(
                x >= -10 && x <= 10
                    && z >= -10
                    && z <= 10
                    && y >= 65
                    && y <= 68
                    && positions.add(x + ":" + y + ":" + z),
                "Unknown or duplicate original cell retained");
            var block = Block.getBlockById(tag.getInteger("Block"));
            boolean allowed = kind.equals("cake") ? block == MaidLifeContent.snackCabinet || block == Blocks.cake
                : kind.equals("sleep") ? block == MaidLifeContent.maidBed
                    : block == Blocks.stone || block == Blocks.redstone_torch || block == Blocks.stained_glass;
            require(allowed, "Unknown original presentation block retained");
            var cell = new Cell(x, y, z, block, tag.getInteger("Metadata"));
            cell.tile = session.maid.worldObj.getTileEntity(x, y, z);
            if (tag.hasKey("Tile")) {
                require(tag.hasKey("Tile", Constants.NBT.TAG_COMPOUND), "Unknown original tile snapshot retained");
                cell.tileSnapshot = (NBTTagCompound) tag.getCompoundTag("Tile")
                    .copy();
            }
            require((cell.tile == null) == (cell.tileSnapshot == null), "Original native tile presence changed");
            restored.cells.add(cell);
        }
        verifyOriginalCells(restored);
        if (kind.equals("cake")) {
            require(cells.tagCount() == 2, "Original cabinet/cake pair required");
            String explicit = System.getProperty("tlmnh.worldPresentationRestoreCakeMetadata");
            if (explicit != null) {
                require(explicit.matches("[0-6]"), "Exact native cake consumption expectation required");
                restored.expectedRecoveryCakeMetadata = Integer.parseInt(explicit);
                if (row.hasKey("CakeBites")) require(
                    row.getInteger("CakeBites") == restored.expectedRecoveryCakeMetadata,
                    "Explicit recovery expectation differs from recorded native progress");
            } else {
                require(
                    row.hasKey("CakeBites", Constants.NBT.TAG_INT),
                    "No original persisted cake progress; scope retained");
                restored.expectedRecoveryCakeMetadata = row.getInteger("CakeBites");
            }
        }
        restored.capturePlan();
        restored.verify();
        LIVE.put(run, restored);
    }

    private static void verifyOriginalCells(Attempt attempt) {
        if (attempt.kind.equals("cake")) {
            require(attempt.cells.size() == 2, "Original cabinet/cake pair required");
            for (var cell : attempt.cells) require(
                cell.x == 4 && cell.z == 3
                    && (cell.y == 65 && cell.block == MaidLifeContent.snackCabinet && cell.meta == 2
                        || cell.y == 66 && cell.block == Blocks.cake && cell.meta == 0),
                "Unknown original cabinet/cake layout retained");
        } else if (attempt.kind.equals("sleep")) {
            require(attempt.cells.size() == 2, "Original complete bed pair required");
            var foot = attempt.cells.get(0);
            var head = attempt.cells.get(1);
            boolean original = foot.x == 4 && foot.y == 65
                && foot.z == 2
                && foot.meta == 0
                && head.x == 4
                && head.y == 65
                && head.z == 3
                && head.meta == 8;
            boolean adjacent = foot.x == 6 && foot.y == 65
                && foot.z == -1
                && foot.meta == 1
                && head.x == 5
                && head.y == 65
                && head.z == -1
                && head.meta == 9;
            require(
                (original || adjacent) && head.tile instanceof TileEntityMaidBed bed
                    && bed.complete()
                    && bed.available(attempt.session.maid),
                "Unknown original bed layout or foreign native bed lease retained");
        } else {
            require(attempt.cells.size() == 124, "Complete original light chamber required");
            for (var cell : attempt.cells) {
                boolean roof = cell.y == 68 && cell.x >= 3 && cell.x <= 9 && cell.z >= 1 && cell.z <= 7;
                boolean wall = cell.y >= 65 && cell.y < 68
                    && ((cell.x == 3 || cell.x == 9) && cell.z >= 1 && cell.z <= 7
                        || (cell.z == 1 || cell.z == 7) && cell.x >= 3 && cell.x <= 9);
                boolean torch = cell.x == 8 && cell.y == 65 && cell.z == 6;
                boolean glass = cell.x == 6 && (cell.y == 65 || cell.y == 66) && cell.z == 3;
                require(
                    (roof || wall) && cell.block == Blocks.stone && cell.meta == 0
                        || torch && cell.block == Blocks.redstone_torch && cell.meta == 5
                        || glass && cell.block == Blocks.stained_glass && cell.meta == 3,
                    "Unknown original light chamber layout retained");
            }
        }
    }

    private static void verifyOriginalPlayerPlan(Session session) {
        var original = session.data.getTagList("OriginalInventory", Constants.NBT.TAG_COMPOUND);
        var remaining = new NBTTagList();
        var hand = ProtectedItemCodec.decodeField(session.data, "OriginalHand");
        boolean present = false;
        for (int index = 0; index < original.tagCount(); index++) {
            var tag = original.getCompoundTagAt(index);
            if ((tag.getByte("Slot") & 255) != session.slot()) remaining.appendTag(tag.copy());
            else {
                require(!present && hand != null, "Unknown original retained hand plan");
                var saved = (NBTTagCompound) tag.copy();
                saved.removeTag("Slot");
                require(raw(hand).equals(saved), "Original complete retained hand differs");
                present = true;
            }
        }
        require(
            present == session.data.getBoolean("HandPresent") && (present || hand == null)
                && remaining.equals(session.data.getTagList("ExpectedInventory", Constants.NBT.TAG_COMPOUND)),
            "Original full player inventory/selected-slot plan differs");
    }

    static void run(Session session, String action) {
        if (Arrays
            .asList(
                "world-scene-follow-bedrock",
                "world-scene-bow-gecko",
                "world-scene-sleep-bedrock",
                "world-scene-cake-gecko",
                "world-scene-light-bedrock",
                "world-scene-light-gecko")
            .contains(action)) {
            prepare(session, action);
            return;
        }
        var attempt = attempt(session);
        require(!attempt.recoveryOnly, "Recovered scope admits cleanup only, never a new scene or telemetry");
        attempt.verify();
        var maid = session.maid;
        switch (action) {
            case "world-scene-melee" -> {
                require(
                    attempt.kind.equals("follow") && attempt.followObserved,
                    "Actual follow movement required before the melee transition");
                attempt.target(6.5, -.5);
                maid.setTask(MaidTask.ATTACK);
                require(
                    maid.attackEntityFrom(DamageSource.causeMobDamage(attempt.cow), 1),
                    "Native recent threat required");
                attempt.row.setString("Stage", "melee");
            }
            case "world-scene-wake" -> {
                require(
                    attempt.kind.equals("sleep") && attempt.sleepObserved,
                    "Natural bed entry required before waking");
                maid.setSchedule(MaidSchedule.DAY);
                attempt.row.setString("Stage", "waking");
            }
            case "world-scene-verify" -> {
                boolean complete = switch (attempt.kind) {
                    case "follow" -> attempt.followObserved && attempt.actor()
                        .meleeNativeAccepted() > 0 && attempt.cow.getHealth() < 200;
                    case "bow" -> attempt.bowObserved && attempt.shots > 0;
                    case "sleep" -> attempt.sleepObserved && !maid.hasPose(MaidPose.SLEEPING)
                        && maid.getSleepingBedDirection() == -1;
                    case "cake" -> attempt.actor()
                        .cakeBites() > 0;
                    case "light" -> maid.worldObj.getSavedLightValue(EnumSkyBlock.Sky, 6, 65, 4) == 0
                        && maid.worldObj.getBlockLightValue(6, 65, 4) <= 7;
                    default -> false;
                };
                require(complete, "Natural world transition is not complete");
                attempt.stop();
                attempt.row.setString("Stage", "stopped");
            }
            default -> throw new IllegalArgumentException("Unknown world presentation action");
        }
        attempt.verify();
    }

    private static void prepare(Session session, String action) {
        require(
            session.data.getString("Phase")
                .equals("prepared") && !session.data.hasKey(KEY),
            "A separate original empty Care session is required");
        session.emptyActor();
        String kind = action.contains("follow") ? "follow"
            : action.contains("bow") ? "bow"
                : action.contains("sleep") ? "sleep" : action.contains("cake") ? "cake" : "light";
        var row = new NBTTagCompound();
        row.setInteger("Schema", 1);
        row.setString("Kind", kind);
        row.setString("Stage", "prepared");
        row.setTag("Inventory", new NBTTagList());
        row.setTag("Cells", new NBTTagList());
        session.data.setTag(KEY, row);
        session.data.setString("Phase", "world-presentation");
        var attempt = new Attempt(session, kind, row);
        LIVE.put(session.data.getString("Run"), attempt);
        var maid = session.maid;
        String model = action.endsWith("gecko") ? "geckolib:winefox_hanfu" : "touhou_little_maid:hakurei_reimu";
        maid.setModelId(model);
        session.data.setString("ExpectedModel", model);
        maid.setPickup(false);
        maid.setHunger(20);
        maid.setSitting(false);
        maid.setSchedule(MaidSchedule.DAY);
        maid.setLocationAndAngles(4.5, 65, kind.equals("follow") ? 5.5 : .5, 180, 0);
        maid.rotationYawHead = maid.prevRotationYawHead = maid.renderYawOffset = maid.prevRenderYawOffset = 180;
        attempt.startX = maid.posX;
        attempt.startZ = maid.posZ;
        attempt.startOwnerDistance = maid.getDistanceSqToEntity(session.player);
        attempt.equip(5, Items.compass, 1);
        if (kind.equals("follow")) attempt.equip(6, Items.iron_sword, 1);
        if (kind.equals("bow")) {
            attempt.equip(6, Items.bow, 1);
            attempt.equip(0, Items.arrow, 64);
            attempt.camera(.5, 2.5);
        }
        if (kind.equals("sleep")) {
            // CORE follow precedes REST bed selection and a live walk target excludes the bed task.
            // Start inside the ordinary follow threshold and next to the complete bed, without calling sleepAt.
            maid.setLocationAndAngles(4.5, 65, -1.5, 180, 0);
            int direction = 1, headX = 5, headZ = -1;
            attempt.cell(
                headX - BlockBed.field_149981_a[direction][0],
                65,
                headZ - BlockBed.field_149981_a[direction][1],
                MaidLifeContent.maidBed,
                direction);
            attempt.cell(headX, 65, headZ, MaidLifeContent.maidBed, direction | 8);
            require(
                maid.worldObj.getTileEntity(headX, 65, headZ) instanceof TileEntityMaidBed bed && bed.complete(),
                "Actual native bed direction and both halves must be complete");
            maid.setSchedule(MaidSchedule.NIGHT);
        }
        if (kind.equals("cake")) {
            attempt.cell(4, 65, 3, MaidLifeContent.snackCabinet, 2);
            attempt.cell(4, 66, 3, Blocks.cake, 0);
        }
        if (kind.equals("light")) attempt.chamber();
        attempt.save();
        session.hand(null, null);
        if (!kind.equals("light")) {
            var selected = maid.getBrainCoordinator()
                .selectProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM);
            require(
                selected == MaidPolicyRuntime.SelectionResult.SELECTED
                    || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED,
                "Actual full faithful factory required");
            var brain = maid.getBrainCoordinator()
                .peekFaithfulBrain();
            require(brain != null, "Actual selected faithful brain required");
            attempt.faithfulBrain = brain;
            attempt.faithful = brain.getActor();
            maid.getBrainCoordinator()
                .setSuspended(false);
            if (kind.equals("bow")) {
                attempt.target(4.5, 7.5);
                maid.setTask(MaidTask.RANGED_ATTACK);
                require(
                    maid.attackEntityFrom(DamageSource.causeMobDamage(attempt.cow), 1),
                    "Native ranged recent threat required");
            }
        }
        row.setString("Stage", "running");
        attempt.verify();
    }

    static void verifyInventory(Session session) {
        attempt(session).verify();
    }

    static void restore(Session session) {
        if (!session.data.hasKey(KEY)) return;
        var attempt = attempt(session);
        attempt.verify();
        attempt.stop();
        attempt.verify();
        require(
            session.maid.getItemUseState()
                .peek() == null && !session.maid.hasPose(MaidPose.SLEEPING),
            "Native brain cleanup has not released the current lease");
        for (var arrow : attempt.arrows) if (arrow.worldObj.getEntityByID(arrow.getEntityId()) == arrow)
            arrow.worldObj.removePlayerEntityDangerously(arrow);
        if (attempt.cow != null) {
            require(
                attempt.cow.worldObj.getEntityByID(attempt.cow.getEntityId()) == attempt.cow
                    && attempt.cow.getEntityData()
                        .getString(KEY)
                        .equals(session.data.getString("Run")),
                "Unknown target successor retained");
            attempt.cow.worldObj.removePlayerEntityDangerously(attempt.cow);
        }
        for (int index = attempt.cells.size() - 1; index >= 0; index--) {
            var cell = attempt.cells.get(index);
            cell.verify(attempt);
            // A verified sixth native bite already removed this scope's cake. Air-to-air is not a block change.
            if (cell.block == Blocks.cake && session.maid.worldObj.isAirBlock(cell.x, cell.y, cell.z)) continue;
            // These exact empty tiles came from air in this scope; no material or return queue may be manufactured.
            if (cell.tile != null) session.maid.worldObj.removeTileEntity(cell.x, cell.y, cell.z);
            require(
                session.maid.worldObj.setBlock(cell.x, cell.y, cell.z, Blocks.air, 0, 2),
                "Owned fixture cell removal failed");
            require(
                session.maid.worldObj.isAirBlock(cell.x, cell.y, cell.z)
                    && session.maid.worldObj.getTileEntity(cell.x, cell.y, cell.z) == null,
                "Presentation cleanup callback left an unknown successor; scope retained");
        }
        for (int slot = 0; slot < session.maid.getMaidInventory()
            .getSizeInventory(); slot++)
            session.maid.getMaidInventory()
                .setInventorySlotContents(slot, null);
        LIVE.remove(session.data.getString("Run"));
        session.data.removeTag(KEY);
    }

    static JsonObject observation(Session session) {
        var attempt = attempt(session);
        attempt.verify();
        var result = MaidVisibleStateSnapshot.capture(session.maid);
        result.addProperty("kind", attempt.kind);
        result.addProperty("stage", attempt.row.getString("Stage"));
        result.addProperty("recoveryOnly", attempt.recoveryOnly);
        result.addProperty("followObserved", attempt.followObserved);
        result.addProperty("followMoveObserved", attempt.followMoveObserved);
        result.addProperty("bowObserved", attempt.bowObserved);
        result.addProperty("sleepObserved", attempt.sleepObserved);
        result.addProperty("registeredShots", attempt.shots);
        result.addProperty("worldTime", session.maid.worldObj.getWorldTime());
        result.addProperty("usingItem", session.maid.hasPose(MaidPose.USING_ITEM));
        result.addProperty("aimingBow", session.maid.hasPose(MaidPose.AIMING_BOW));
        result.addProperty(
            "nativeUsedTicks",
            session.maid.getItemUseState()
                .getUsedTicks());
        result.addProperty("sleeping", session.maid.hasPose(MaidPose.SLEEPING));
        result.addProperty("swingProgressInt", session.maid.swingProgressInt);
        result.addProperty("x", session.maid.posX);
        result.addProperty("y", session.maid.posY);
        result.addProperty("z", session.maid.posZ);
        if (attempt.recoveryOnly && attempt.kind.equals("cake")) {
            result.addProperty("expectedRecoveryCakeMetadata", attempt.expectedRecoveryCakeMetadata);
        } else if (!attempt.recoveryOnly && !attempt.kind.equals("light")) {
            result.addProperty(
                "meleeNativeAccepted",
                attempt.actor()
                    .meleeNativeAccepted());
            result.addProperty(
                "cakeBites",
                attempt.actor()
                    .cakeBites());
            result.addProperty(
                "sleepEntries",
                attempt.actor()
                    .sleepEntries());
        }
        if (attempt.cow != null) result.addProperty("targetHealth", attempt.cow.getHealth());
        int x = MathHelper.floor_double(session.maid.posX), y = MathHelper.floor_double(session.maid.posY),
            z = MathHelper.floor_double(session.maid.posZ);
        result.addProperty("skyLight", session.maid.worldObj.getSavedLightValue(EnumSkyBlock.Sky, x, y, z));
        result.addProperty("blockLight", session.maid.worldObj.getSavedLightValue(EnumSkyBlock.Block, x, y, z));
        result.addProperty("combinedLight", session.maid.worldObj.getBlockLightValue(x, y, z));
        result.addProperty("canSeeSky", session.maid.worldObj.canBlockSeeTheSky(x, y, z));
        result.addProperty(
            "schedule",
            session.maid.getSchedule()
                .name());
        result.addProperty(
            "restBySchedule",
            MaidLifeRules.isRest(session.maid.getSchedule(), session.maid.worldObj.getWorldTime()));
        if (attempt.kind.equals("sleep")) {
            for (var cell : attempt.cells) if (cell.tile instanceof TileEntityMaidBed bed) {
                result.addProperty("bedComplete", bed.complete());
                result.addProperty("bedDirection", cell.meta & 3);
                result.addProperty("bedDistanceSq", session.maid.getDistanceSq(cell.x + .5, cell.y + .5, cell.z + .5));
            }
        }
        return result;
    }

    private static NBTTagCompound raw(ItemStack stack) {
        return stack == null ? new NBTTagCompound()
            : stack.copy()
                .writeToNBT(new NBTTagCompound());
    }

    private static final class Attempt {

        Session session;
        final String kind;
        final NBTTagCompound row;
        final ItemStack[] equipment = new ItemStack[12];
        final List<Cell> cells = new ArrayList<>();
        final List<EntityArrow> arrows = new ArrayList<>();
        final Set<EntityArrow> registered = new HashSet<>();
        EntityCow cow;
        ModernFollowCoreActor faithful;
        MaidFaithfulBrain faithfulBrain;
        NBTTagCompound plan;
        double startX, startZ, startOwnerDistance;
        boolean followObserved, followMoveObserved, bowObserved, sleepObserved;
        boolean recoveryOnly;
        int expectedRecoveryCakeMetadata, lastVerifiedCakeBites;
        int shots;

        Attempt(Session session, String kind, NBTTagCompound row) {
            this.session = session;
            this.kind = kind;
            this.row = row;
        }

        ModernFollowCoreActor actor() {
            require(faithful != null, "Original full faithful presentation factory required");
            return faithful;
        }

        void equip(int slot, Item item, int count) {
            var stack = new ItemStack(item, count);
            stack.setTagCompound(new NBTTagCompound());
            stack.getTagCompound()
                .setString(KEY, session.data.getString("Run"));
            session.maid.getMaidInventory()
                .setInventorySlotContents(slot, stack);
            equipment[slot] = stack.copy();
        }

        void camera(double x, double z) {
            session.player.playerNetServerHandler.setPlayerLocation(x, 65, z, 0, 10);
            session.data.setDouble("ExpectedX", x);
            session.data.setDouble("ExpectedY", 65);
            session.data.setDouble("ExpectedZ", z);
        }

        void target(double x, double z) {
            require(cow == null, "Exact prior target retained");
            cow = new EntityCow(session.maid.worldObj);
            cow.getEntityData()
                .setString(KEY, session.data.getString("Run"));
            cow.getEntityAttribute(SharedMonsterAttributes.maxHealth)
                .setBaseValue(200);
            cow.setHealth(200);
            cow.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .setBaseValue(0);
            cow.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .setBaseValue(1);
            cow.setPosition(x, 65, z);
            require(
                session.player.getDistanceSqToEntity(cow) < 64,
                "Actual owner threat range must remain within eight blocks");
            require(session.maid.worldObj.spawnEntityInWorld(cow), "Exact native target registration required");
        }

        void cell(int x, int y, int z, Block block, int meta) {
            var world = session.maid.worldObj;
            require(
                world.blockExists(x, y, z) && world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null,
                "Original empty presentation cell required");
            var cell = new Cell(x, y, z, block, meta);
            cells.add(cell);
            require(world.setBlock(x, y, z, block, meta, 2), "Presentation cell write failed");
            cell.tile = world.getTileEntity(x, y, z);
            if (cell.tile != null) {
                cell.tileSnapshot = new NBTTagCompound();
                cell.tile.writeToNBT(cell.tileSnapshot);
            }
        }

        void chamber() {
            for (int x = 3; x <= 9; x++) for (int z = 1; z <= 7; z++) cell(x, 68, z, Blocks.stone, 0);
            for (int y = 65; y < 68; y++) {
                for (int x = 3; x <= 9; x++) {
                    cell(x, y, 1, Blocks.stone, 0);
                    cell(x, y, 7, Blocks.stone, 0);
                }
                for (int z = 2; z < 7; z++) {
                    cell(3, y, z, Blocks.stone, 0);
                    cell(9, y, z, Blocks.stone, 0);
                }
            }
            cell(8, 65, 6, Blocks.redstone_torch, 5);
            cell(6, 65, 3, Blocks.stained_glass, 3);
            cell(6, 66, 3, Blocks.stained_glass, 3);
            session.maid.setLocationAndAngles(6.5, 65, 4.5, 180, 0);
            camera(6.5, 2.5);
        }

        void save() {
            var inventory = new NBTTagList();
            for (int slot = 0; slot < equipment.length; slot++) {
                var tag = raw(equipment[slot]);
                tag.setInteger("Slot", slot);
                inventory.appendTag(tag);
            }
            row.setTag("Inventory", inventory);
            var list = new NBTTagList();
            for (var cell : cells) {
                var tag = new NBTTagCompound();
                tag.setInteger("X", cell.x);
                tag.setInteger("Y", cell.y);
                tag.setInteger("Z", cell.z);
                tag.setInteger("Block", Block.getIdFromBlock(cell.block));
                tag.setInteger("Metadata", cell.meta);
                if (cell.tileSnapshot != null) tag.setTag("Tile", cell.tileSnapshot.copy());
                list.appendTag(tag);
            }
            row.setTag("Cells", list);
            if (kind.equals("cake")) row.setInteger("CakeBites", 0);
            capturePlan();
        }

        void capturePlan() {
            plan = (NBTTagCompound) row.copy();
            plan.removeTag("Stage");
            plan.removeTag("CakeBites");
            lastVerifiedCakeBites = row.getInteger("CakeBites");
        }

        void verify() {
            var actualPlan = (NBTTagCompound) row.copy();
            actualPlan.removeTag("Stage");
            actualPlan.removeTag("CakeBites");
            require(plan != null && plan.equals(actualPlan), "Unknown complete presentation plan retained");
            require(
                Arrays.asList("prepared", "running", "melee", "waking", "stopped")
                    .contains(row.getString("Stage")),
                "Unknown presentation stage retained");
            for (var arrow : arrows)
                if (arrow.worldObj.getEntityByID(arrow.getEntityId()) == arrow) registered.add(arrow);
            shots = registered.size();
            if (!recoveryOnly && kind.equals("follow")
                && row.getString("Stage")
                    .equals("running")) {
                followMoveObserved |= faithfulBrain.getMoveState()
                    .getStatus() == FollowMoveState.Status.RUNNING;
                followObserved |= followMoveObserved
                    && Math.hypot(session.maid.posX - startX, session.maid.posZ - startZ) > .5
                    && session.maid.getDistanceSqToEntity(session.player) + 1 < startOwnerDistance;
            }
            bowObserved |= !recoveryOnly && kind.equals("bow") && session.maid.hasPose(MaidPose.USING_ITEM);
            sleepObserved |= !recoveryOnly && kind.equals("sleep") && session.maid.hasPose(MaidPose.SLEEPING);
            for (int slot = 0; slot < equipment.length; slot++) {
                var expected = equipment[slot];
                var actual = session.maid.getMaidInventory()
                    .getStackInSlot(slot);
                if (expected == null) {
                    require(actual == null, "Foreign presentation equipment retained");
                    continue;
                }
                var planned = expected.copy();
                if (kind.equals("bow") && slot == 0) planned.stackSize -= shots;
                if (planned.stackSize == 0) {
                    require(actual == null, "Unexpected native ammo successor retained");
                    continue;
                }
                require(actual != null, "Presentation material disappeared; retained");
                if (slot == 6) {
                    int limit = kind.equals("bow") ? shots
                        : kind.equals("follow") ? (int) actor().meleeNativeAccepted() * 2 : 0;
                    require(
                        actual.getItemDamage() >= 0 && actual.getItemDamage() <= limit,
                        "Unplanned native weapon wear retained");
                    planned.setItemDamage(actual.getItemDamage());
                }
                require(raw(planned).equals(raw(actual)), "Unknown complete presentation material successor retained");
            }
            for (var cell : cells) cell.verify(this);
            if (!recoveryOnly && kind.equals("cake")) {
                require(
                    !row.hasKey("CakeBites") || row.getInteger("CakeBites") == lastVerifiedCakeBites,
                    "Recorded native cake progress changed outside this context");
                lastVerifiedCakeBites = actor().cakeBites();
                row.setInteger("CakeBites", lastVerifiedCakeBites);
            }
        }

        void stop() {
            session.maid.setTask(MaidTask.IDLE);
            session.maid.setSchedule(MaidSchedule.DAY);
            session.maid.getBrainCoordinator()
                .setSuspended(true);
        }
    }

    private static final class Cell {

        final int x, y, z, meta;
        final Block block;
        TileEntity tile;
        NBTTagCompound tileSnapshot;

        Cell(int x, int y, int z, Block block, int meta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
        }

        void verify(Attempt attempt) {
            var world = attempt.session.maid.worldObj;
            int wanted = meta;
            if (block == Blocks.cake) {
                int bites = attempt.recoveryOnly ? attempt.expectedRecoveryCakeMetadata
                    : attempt.actor()
                        .cakeBites();
                if (bites >= 6) {
                    require(
                        world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null,
                        "Unknown consumed cake successor retained");
                    return;
                }
                wanted = bites;
            }
            if (block == MaidLifeContent.snackCabinet && attempt.kind.equals("cake")) {
                // Native TileEntitySnackCabinet's first tick upgrades the original empty display to FULL.
                require((meta & 12) == 0, "Unknown original cabinet variant retained");
                int actual = world.getBlockMetadata(x, y, z);
                require(actual == meta || actual == (meta | 4), "Unknown native cabinet display successor retained");
                wanted = actual;
            }
            if (block == MaidLifeContent.maidBed && (meta & 8) != 0 && attempt.session.maid.hasPose(MaidPose.SLEEPING))
                wanted |= 4;
            require(
                world.getBlock(x, y, z) == block && world.getBlockMetadata(x, y, z) == wanted
                    && world.getTileEntity(x, y, z) == tile,
                "Unknown presentation cell successor retained");
            if (tile != null) {
                var actual = new NBTTagCompound();
                tile.writeToNBT(actual);
                require(tileSnapshot.equals(actual), "Unknown complete presentation tile successor retained");
            }
        }
    }
}
