package com.tianluoqaq.tlmnh.common.development.interaction;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.DimensionManager;

import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskAttack;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.tianluoqaq.tlmnh.common.ai.MaidCombatTargeting;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidBrainProfile;
import com.tianluoqaq.tlmnh.common.ai.policy.MaidPolicyRuntime;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldController;
import com.tianluoqaq.tlmnh.common.combat.shield.MaidShieldItems;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.development.interaction.MaidCareInteractionFixture.Session;
import com.tianluoqaq.tlmnh.common.entity.MaidTask;
import com.tianluoqaq.tlmnh.common.inventory.MaidInventory;
import com.tianluoqaq.tlmnh.common.lifecycle.MaidSpawnChecks;
import com.tianluoqaq.tlmnh.common.protection.ProtectedItemCodec;
import com.tianluoqaq.tlmnh.compat.tconstruct.common.tool.TConstructToolData;
import com.tianluoqaq.tlmnh.compat.tinkersdefense.development.shield.MaidShieldFixtures;

/** 自然F举盾与真实飞行箭的受限输入；原场景和玩家库存由现Care会话完整保管 */
final class MaidNaturalShieldActions {

    private static final String KEY = "NaturalShield", ENTITY_KEY = "TLMNHNaturalShield";
    private static final Map<String, Attempt> ATTEMPTS = new HashMap<>();
    private static final Set<String> FIELDS = new HashSet<>(
        Arrays.asList(
            "Schema",
            "Stage",
            "Kind",
            "Inventory",
            "Variants",
            "BeforeShield",
            "Movement",
            "Knockback",
            "Threat",
            "Arrow",
            "StartBlock",
            "StartCaught",
            "StartBodyArrows",
            "BeforeHealth",
            "LaunchTime",
            "ActiveObserved",
            "FrontObserved",
            "RearObserved"));
    private static final Set<String> STAGES = new HashSet<>(
        Arrays.asList(
            "equipped",
            "active-armed",
            "active-verified",
            "active-stopped",
            "front-flight",
            "front-verified",
            "rear-flight",
            "rear-verified",
            "removed"));

    private MaidNaturalShieldActions() {}

    static boolean handles(String action) {
        return action != null && action.startsWith("world-shield-");
    }

    static void validateSnapshot(NBTTagCompound data) {
        if (!data.hasKey(KEY)) return;
        require(
            data.hasKey(KEY, 10) && data.getString("Phase")
                .equals("natural-shield"),
            "Unknown natural shield record retained");
        var record = data.getCompoundTag(KEY);
        for (Object field : record.func_150296_c())
            require(FIELDS.contains(field), "Unknown natural shield field retained");
        require(
            record.hasKey("Schema", 3) && record.getInteger("Schema") == 1
                && record.hasKey("Stage", 8)
                && STAGES.contains(record.getString("Stage"))
                && record.hasKey("Kind", 8)
                && (record.getString("Kind")
                    .equals("bg")
                    || record.getString("Kind")
                        .equals("td")),
            "Unknown natural shield version or stage retained");
        require(
            record.hasKey("Movement", 6) && Double.isFinite(record.getDouble("Movement"))
                && record.hasKey("Knockback", 6)
                && Double.isFinite(record.getDouble("Knockback"))
                && record.hasKey("BeforeShield", 10),
            "Unknown natural shield source retained");
        list(record, "Inventory");
        var variants = list(record, "Variants");
        for (int i = 0; i < variants.tagCount(); i++) {
            var variant = variants.getCompoundTagAt(i);
            for (Object key : variant.func_150296_c())
                require(key.equals("Wear") || key.equals("Inventory"), "Unknown native shield result retained");
            require(
                variant.hasKey("Wear", 3) && variant.getInteger("Wear") == i - 1,
                "Unknown native shield wear result retained");
            list(variant, "Inventory");
        }
        for (String key : new String[] { "Threat", "Arrow" }) if (record.hasKey(key)) require(
            record.hasKey(key, 8) && UUID.fromString(record.getString(key))
                .toString()
                .equals(record.getString(key)),
            "Unknown natural projectile identity retained");
        for (String key : new String[] { "StartBlock", "StartCaught", "LaunchTime" }) require(
            !record.hasKey(key) || record.hasKey(key, 4) && record.getLong(key) >= 0,
            "Unknown shield clock retained");
        require(
            !record.hasKey("StartBodyArrows")
                || record.hasKey("StartBodyArrows", 3) && record.getInteger("StartBodyArrows") >= 0,
            "Unknown shield body count retained");
        require(
            !record.hasKey("BeforeHealth")
                || record.hasKey("BeforeHealth", 5) && Float.isFinite(record.getFloat("BeforeHealth")),
            "Unknown shield health input retained");
        for (String key : new String[] { "ActiveObserved", "FrontObserved", "RearObserved" }) require(
            record.hasKey(key, 1) && record.getByte(key) >= 0 && record.getByte(key) <= 1,
            "Unknown natural shield observation retained");
    }

    static void verifyInventory(Session session) {
        validateSnapshot(session.data);
        var record = session.data.getCompoundTag(KEY);
        var actual = inventory(session);
        var attempt = ATTEMPTS.get(session.data.getString("Run"));
        if (!record.getString("Stage")
            .equals("removed") && attempt != null)
            require(
                attempt.session == session && session.maid.getOffhandItem() == attempt.shield,
                "Unknown natural shield object successor retained");
        if (actual.equals(record.getTagList("Inventory", 10))) return;
        require(
            attempt != null && attempt.session == session
                && record.getString("Stage")
                    .equals("front-flight")
                && confirmed(attempt)
                && session.maid.getShieldController()
                    .getLastBlock().blocked,
            "Unknown natural shield equipment successor retained");
        require(actual.equals(frontResult(attempt)), "Full native shield result differs; unknown NBT retained");
    }

    static void run(Session session, String action) {
        require(MaidCareInteractionFixture.owns(session), "Original live care session required");
        if (action.equals("world-shield-bedrock-bg") || action.equals("world-shield-gecko-td")) {
            prepare(session, action.endsWith("td"));
            return;
        }
        require(session.data.hasKey(KEY, 10), "Prepare an independent natural shield case first");
        verifyInventory(session);
        var attempt = ATTEMPTS.get(session.data.getString("Run"));
        require(
            attempt != null && attempt.session == session,
            "Original live shield inputs required; saved data retained");
        var record = session.data.getCompoundTag(KEY);
        switch (action) {
            case "world-shield-arm-active" -> {
                stage(record, "equipped");
                require(
                    ownedThreat(attempt) && session.maid.getShieldController()
                        .getSnapshot().using == false,
                    "Fresh registered shield threat required");
                session.maid.setSitting(false);
                session.maid.setTask(MaidTask.ATTACK);
                session.maid.getBrainCoordinator()
                    .setSuspended(false);
                var attackTask = new TaskAttack();
                require(
                    !attackTask.farAway(attempt.cow, session.maid) && session.maid.getDistanceToEntity(attempt.cow) < 8
                        && attackTask.hasAssaultWeapon(session.maid)
                        && session.maid.canUseShield(),
                    "Actual TaskAttack owner range, weapon and MaidUseShieldTask range must admit the fixed threat");
                require(
                    session.maid.attackEntityFrom(DamageSource.causeMobDamage(attempt.cow), 1)
                        && session.maid.getAITarget() == attempt.cow
                        && MaidCombatTargeting.canAttack(session.maid, session.player, attempt.cow),
                    "Registered native melee hurt did not establish an eligible recent threat");
                record.setString("Stage", "active-armed");
            }
            case "world-shield-verify-active" -> {
                stage(record, "active-armed");
                var state = session.maid.getShieldController()
                    .getSnapshot();
                require(
                    ownedThreat(attempt) && state.using
                        && state.activeTaskRunning
                        && state.cause == MaidShieldController.Cause.ACTIVE
                        && session.maid.getAttackTarget() == attempt.cow
                        && MaidCombatTargeting.recentThreat(session.maid, session.player, attempt.cow),
                    "Actual full faithful natural shield start not yet observed");
                record.setBoolean("ActiveObserved", true);
                record.setString("Stage", "active-verified");
            }
            case "world-shield-stop-active" -> {
                stage(record, "active-verified");
                session.maid.setTask(MaidTask.IDLE);
                session.maid.getBrainCoordinator()
                    .setSuspended(true);
                require(
                    !session.maid.getShieldController()
                        .getSnapshot().using,
                    "Ordinary task boundary did not stop the active shield lease");
                record.setString("Stage", "active-stopped");
            }
            case "world-shield-front" -> {
                stage(record, "active-stopped");
                shoot(attempt, false);
            }
            case "world-shield-verify-front" -> {
                stage(record, "front-flight");
                require(confirmed(attempt), "Wait for this exact native flying arrow impact");
                var result = session.maid.getShieldController()
                    .getLastBlock();
                require(
                    result.blocked && result.outcome == MaidShieldController.Outcome.BLOCKED
                        && !result.equipmentChangedDuringCallback
                        && session.maid.getShieldController()
                            .getSnapshot().caughtArrows == record.getLong("StartCaught") + 1
                        && session.maid.getArrowCountInEntity() == record.getInteger("StartBodyArrows")
                        && session.maid.getHealth() == record.getFloat("BeforeHealth")
                        && inventory(session).equals(frontResult(attempt)),
                    "Actual front collision/wear/count/full NBT did not settle once");
                record.setTag("Inventory", frontResult(attempt));
                record.setBoolean("FrontObserved", true);
                record.setString("Stage", "front-verified");
            }
            case "world-shield-rear" -> {
                stage(record, "front-verified");
                shoot(attempt, true);
            }
            case "world-shield-verify-rear" -> {
                stage(record, "rear-flight");
                require(confirmed(attempt), "Wait for the exact rear native flying arrow impact");
                var result = session.maid.getShieldController()
                    .getLastBlock();
                require(
                    !result.blocked && result.outcome == MaidShieldController.Outcome.DIRECTION_REJECTED
                        && !result.wearCallbackInvoked
                        && session.maid.getShieldController()
                            .getSnapshot().caughtArrows == record.getLong("StartCaught")
                        && session.maid.getArrowCountInEntity() == record.getInteger("StartBodyArrows") + 1
                        && session.maid.getHealth() < record.getFloat("BeforeHealth")
                        && inventory(session).equals(record.getTagList("Inventory", 10)),
                    "Actual rear collision did not reject capture/wear and retain native damage");
                record.setBoolean("RearObserved", true);
                record.setString("Stage", "rear-verified");
            }
            case "world-shield-remove" -> {
                stage(record, "rear-verified");
                removeEquipment(attempt);
            }
            case "world-shield-verify-removed" -> {
                stage(record, "removed");
                require(
                    record.getBoolean("ActiveObserved") && record.getBoolean("FrontObserved")
                        && record.getBoolean("RearObserved")
                        && !session.maid.getShieldController()
                            .getSnapshot().using
                        && session.maid.getItemUseState()
                            .peek() == null
                        && inventory(session).tagCount() == 0,
                    "Natural shield cycle or ordinary equipment cancellation incomplete");
            }
            default -> throw new IllegalArgumentException("Unknown owned natural shield action");
        }
    }

    private static void prepare(Session session, boolean tinkers) {
        require(
            session.data.getString("Phase")
                .equals("prepared") && !session.data.hasKey(KEY)
                && !session.data.hasKey("WorldActions")
                && session.maid.ridingEntity == null
                && session.maid.riddenByEntity == null
                && inventory(session).tagCount() == 0,
            "Separate prepared empty natural shield actor required");
        require(!ATTEMPTS.containsKey(session.data.getString("Run")), "Prior natural shield inputs remain owned");
        var construction = new JsonObject();
        ItemStack shield;
        try {
            shield = MaidShieldFixtures.createWoodShields(construction)
                .get(tinkers ? "tinkersdefense:Round Shield" : "battlegear2:shield.wood")
                .copy();
        } catch (Exception failure) {
            throw new IllegalStateException("Actual native shield factory unavailable", failure);
        }
        if (!shield.hasTagCompound()) shield.setTagCompound(new NBTTagCompound());
        shield.getTagCompound()
            .setString(ENTITY_KEY, session.data.getString("Run"));
        shield.getTagCompound()
            .setIntArray("TLMNHNaturalOpaque", new int[] { 196, 7, 2 });
        shield.getTagCompound()
            .setShort("arrows", (short) 0);
        require(
            MaidShieldItems.inspect(shield)
                .isReady(),
            "Actual fresh shield is not ready");
        var record = new NBTTagCompound();
        record.setInteger("Schema", 1);
        record.setString("Stage", "equipped");
        record.setString("Kind", tinkers ? "td" : "bg");
        record.setTag("Inventory", new NBTTagList());
        record.setTag("Variants", new NBTTagList());
        record.setTag("BeforeShield", ProtectedItemCodec.encode(shield));
        record.setDouble(
            "Movement",
            session.maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                .getBaseValue());
        record.setDouble(
            "Knockback",
            session.maid.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
                .getBaseValue());
        for (String flag : new String[] { "ActiveObserved", "FrontObserved", "RearObserved" })
            record.setBoolean(flag, false);
        session.data.setTag(KEY, record);
        session.data.setString("Phase", "natural-shield");
        session.hand(null, null);
        var attempt = new Attempt(session, shield, construction);
        ATTEMPTS.put(session.data.getString("Run"), attempt);
        var maid = session.maid;
        String model = tinkers ? "geckolib:winefox_hanfu" : "touhou_little_maid:hakurei_reimu";
        maid.setModelId(model);
        session.data.setString("ExpectedModel", model);
        maid.setPickup(false);
        maid.setHunger(20);
        maid.setSitting(false);
        maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        maid.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        maid.setLocationAndAngles(4.5, 65, 4.5, 0, 0);
        face(session, 0);
        var sword = new ItemStack(Items.wooden_sword);
        sword.setTagCompound(new NBTTagCompound());
        sword.getTagCompound()
            .setString(ENTITY_KEY, session.data.getString("Run"));
        maid.setCurrentItemOrArmor(0, sword);
        maid.setOffhandItem(shield);
        record.setTag("Inventory", inventory(session));
        var variants = new NBTTagList();
        for (int wear = -1; wear <= 8; wear++) {
            var candidate = shield.copy();
            if (wear >= 0) candidate.damageItem(wear, maid);
            var capture = MaidShieldItems.prepareArrowCapture(candidate);
            require(capture != null, "Actual native shield counter protocol required");
            var changed = capture.applyTo(candidate);
            require(changed != null, "Fresh native shield counter result missing");
            candidate.setTagCompound(changed);
            var variant = new NBTTagCompound();
            variant.setInteger("Wear", wear);
            variant.setTag("Inventory", replaceOffhand(record.getTagList("Inventory", 10), candidate));
            variants.appendTag(variant);
        }
        record.setTag("Variants", variants);
        var selected = maid.getBrainCoordinator()
            .selectProfileForDevelopment(MaidBrainProfile.FAITHFUL_TLM);
        require(
            selected == MaidPolicyRuntime.SelectionResult.SELECTED
                || selected == MaidPolicyRuntime.SelectionResult.UNCHANGED,
            "Actual full faithful factory required for natural shield behavior");
        maid.setTask(MaidTask.IDLE);
        maid.getBrainCoordinator()
            .setSuspended(true);
        attempt.cow = new EntityCow(maid.worldObj);
        attempt.cow.getEntityData()
            .setTag(ENTITY_KEY, identity(session));
        attempt.cow.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(0);
        attempt.cow.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(1);
        attempt.cow.setPosition(4.5, 65, 9.5);
        record.setString(
            "Threat",
            attempt.cow.getUniqueID()
                .toString());
        require(
            maid.worldObj.spawnEntityInWorld(attempt.cow) && ownedThreat(attempt),
            "Exact temporary native threat registration failed");
        session.player.playerNetServerHandler.setPlayerLocation(4.5, 65, 2.5, 0, 10);
        session.data.setDouble("ExpectedX", 4.5);
        session.data.setDouble("ExpectedY", 65);
        session.data.setDouble("ExpectedZ", 2.5);
        session.protect();
    }

    private static void shoot(Attempt attempt, boolean rear) {
        var session = attempt.session;
        var maid = session.maid;
        require(
            ownedThreat(attempt) && (attempt.arrow == null || attempt.arrow.isDead)
                && maid.hurtResistantTime <= maid.maxHurtResistantTime / 2,
            "Wait for the owned arrow and native hurt window before the next input");
        maid.setTask(MaidTask.IDLE);
        maid.getBrainCoordinator()
            .setSuspended(true);
        face(session, rear ? 180 : 0);
        var record = session.data.getCompoundTag(KEY);
        var last = maid.getShieldController()
            .getLastBlock();
        record.setLong("StartBlock", last == null ? 0 : last.sequence);
        record.setLong(
            "StartCaught",
            maid.getShieldController()
                .getSnapshot().caughtArrows);
        record.setInteger("StartBodyArrows", maid.getArrowCountInEntity());
        record.setFloat("BeforeHealth", maid.getHealth());
        record.setLong("LaunchTime", maid.worldObj.getTotalWorldTime());
        var arrow = new EntityArrow(maid.worldObj, maid.posX, maid.posY + .8, maid.posZ + 4);
        arrow.getEntityData()
            .setTag(ENTITY_KEY, identity(session));
        arrow.shootingEntity = attempt.cow;
        arrow.setDamage(2);
        arrow.setIsCritical(false);
        arrow.setThrowableHeading(0, 0, -1, 1.5F, 0);
        attempt.arrow = arrow;
        attempt.arrows.add(arrow);
        record.setString(
            "Arrow",
            arrow.getUniqueID()
                .toString());
        record.setString("Stage", rear ? "rear-flight" : "front-flight");
        require(
            maid.worldObj.spawnEntityInWorld(arrow) && MaidSpawnChecks.present(arrow, maid.worldObj),
            "Exact native flying arrow registration failed");
    }

    private static void face(Session session, float yaw) {
        var maid = session.maid;
        maid.setLocationAndAngles(4.5, 65, 4.5, yaw, 0);
        maid.rotationYawHead = maid.prevRotationYawHead = maid.renderYawOffset = maid.prevRenderYawOffset = yaw;
        maid.prevRotationYaw = yaw;
        maid.motionX = maid.motionY = maid.motionZ = 0;
        maid.getLookHelper()
            .setLookPosition(maid.posX, maid.posY + maid.getEyeHeight(), maid.posZ + (yaw == 0 ? 5 : -5), 180, 180);
    }

    private static boolean confirmed(Attempt attempt) {
        var record = attempt.session.data.getCompoundTag(KEY);
        var result = attempt.session.maid.getShieldController()
            .getLastBlock();
        return attempt.arrow != null && attempt.arrow.isDead
            && attempt.arrow.ticksExisted >= 2
            && result != null
            && result.sequence > record.getLong("StartBlock")
            && result.directEntity.equals(
                attempt.arrow.getUniqueID()
                    .toString())
            && result.sourceEntity.equals(
                attempt.cow.getUniqueID()
                    .toString())
            && attempt.arrow.shootingEntity == attempt.cow;
    }

    private static NBTTagList frontResult(Attempt attempt) {
        var session = attempt.session;
        var result = session.maid.getShieldController()
            .getLastBlock();
        var record = session.data.getCompoundTag(KEY);
        require(
            result != null && result.blocked
                && !result.equipmentChangedDuringCallback
                && session.maid.getShieldController()
                    .getSnapshot().caughtArrows == record.getLong("StartCaught") + 1,
            "Exact native front shield settlement required");
        int wear = result.requestedWear + 1;
        var variants = record.getTagList("Variants", 10);
        require(wear >= 0 && wear < variants.tagCount(), "Unplanned native wear retained without equipment cleanup");
        return (NBTTagList) variants.getCompoundTagAt(wear)
            .getTagList("Inventory", 10)
            .copy();
    }

    private static void removeEquipment(Attempt attempt) {
        var session = attempt.session;
        verifyInventory(session);
        session.maid.setTask(MaidTask.IDLE);
        session.maid.getBrainCoordinator()
            .setSuspended(true);
        session.maid.setOffhandItem(null);
        session.maid.setCurrentItemOrArmor(0, null);
        var record = session.data.getCompoundTag(KEY);
        record.setTag("Inventory", new NBTTagList());
        record.setString("Stage", "removed");
        require(
            !session.maid.getShieldController()
                .getSnapshot().using && session.maid.getItemUseState()
                    .peek() == null,
            "Actual equipment removal did not release the shield lease");
    }

    static void restore(Session session) {
        if (!session.data.hasKey(KEY)) return;
        verifyInventory(session);
        var record = session.data.getCompoundTag(KEY);
        var attempt = ATTEMPTS.get(session.data.getString("Run"));
        require(
            attempt != null && attempt.session == session,
            "Original natural shield entities required; snapshot retained");
        if (!record.getString("Stage")
            .equals("removed")) removeEquipment(attempt);
        for (var arrow : attempt.arrows) discard(attempt, arrow);
        discard(attempt, attempt.cow);
        session.maid.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
            .setBaseValue(record.getDouble("Movement"));
        session.maid.getEntityAttribute(SharedMonsterAttributes.knockbackResistance)
            .setBaseValue(record.getDouble("Knockback"));
        ATTEMPTS.remove(session.data.getString("Run"));
        session.data.removeTag(KEY);
    }

    static boolean isSpawning(Entity entity) {
        for (var attempt : ATTEMPTS.values()) if (MaidCareInteractionFixture.owns(attempt.session) && !entity.isDead
            && (entity == attempt.cow || entity == attempt.arrow)
            && ownedIdentity(attempt, entity)) return true;
        return false;
    }

    private static boolean ownedIdentity(Attempt attempt, Entity entity) {
        return entity.worldObj == attempt.session.maid.worldObj && AnimationLab.isLab(entity.worldObj)
            && DimensionManager.getWorld(entity.dimension) == entity.worldObj
            && entity.getEntityData()
                .hasKey(ENTITY_KEY, 10)
            && identity(attempt.session).equals(
                entity.getEntityData()
                    .getCompoundTag(ENTITY_KEY));
    }

    private static boolean ownedThreat(Attempt attempt) {
        var cow = attempt.cow;
        return cow != null && cow.getClass() == EntityCow.class
            && cow.isEntityAlive()
            && isSpawning(cow)
            && MaidSpawnChecks.present(cow, attempt.session.maid.worldObj)
            && cow.getCustomNameTag()
                .isEmpty();
    }

    private static void discard(Attempt attempt, Entity entity) {
        boolean sameObject = entity != null && entity == attempt.cow;
        for (var arrow : attempt.arrows) if (arrow == entity) sameObject = true;
        require(
            sameObject && MaidCareInteractionFixture.owns(attempt.session) && ownedIdentity(attempt, entity),
            "Unknown temporary shield entity successor retained");
        var mapped = entity.worldObj.getEntityByID(entity.getEntityId());
        require(mapped == null || mapped == entity, "Unknown temporary entity ID successor retained");
        if (entity instanceof EntityCow cow) for (int slot = 0; slot < 5; slot++)
            require(cow.getEquipmentInSlot(slot) == null, "Unknown threat equipment retained");
        if (entity instanceof EntityArrow arrow)
            require(arrow.shootingEntity == attempt.cow, "Unknown native projectile owner retained");
        if (mapped == entity || entity.worldObj.loadedEntityList.contains(entity))
            entity.worldObj.removePlayerEntityDangerously(entity);
    }

    static JsonObject observation(Session session) {
        var result = MaidVisibleStateSnapshot.capture(session.maid);
        var record = session.data.getCompoundTag(KEY);
        var attempt = ATTEMPTS.get(session.data.getString("Run"));
        result.addProperty("stage", record.getString("Stage"));
        result.addProperty("activeObserved", record.getBoolean("ActiveObserved"));
        result.addProperty("frontObserved", record.getBoolean("FrontObserved"));
        result.addProperty("rearObserved", record.getBoolean("RearObserved"));
        result.add(
            "state",
            new Gson().toJsonTree(
                session.maid.getShieldController()
                    .getSnapshot()));
        result.add(
            "lastBlock",
            new Gson().toJsonTree(
                session.maid.getShieldController()
                    .getLastBlock()));
        result.addProperty("bodyArrowCount", session.maid.getArrowCountInEntity());
        result.addProperty("wear", wear(session.maid.getOffhandItem()));
        result.addProperty("hurtResistantTime", session.maid.hurtResistantTime);
        result.addProperty(
            "nativeHurtWindowReady",
            session.maid.hurtResistantTime <= session.maid.maxHurtResistantTime / 2);
        result.addProperty("inventoryNbt", inventory(session).toString());
        result.addProperty("canVerifyImpact", attempt != null && confirmed(attempt));
        result.addProperty(
            "passiveScope",
            "actual arrow collision while full faithful factory is installed and active brain paused");
        if (attempt != null) {
            result.add("construction", attempt.construction);
            if (attempt.cow != null) {
                result.add("threat", entity(attempt.cow));
                result.addProperty("ownerThreatDistance", session.player.getDistanceToEntity(attempt.cow));
                result.addProperty("maidThreatDistance", session.maid.getDistanceToEntity(attempt.cow));
                result.addProperty(
                    "restrictionRadius",
                    session.maid.getHomeArea()
                        .restrictionRadius());
                result.addProperty(
                    "homeEnabled",
                    session.maid.getHomeArea()
                        .enabled());
                result.addProperty("taskTargetTooFar", new TaskAttack().farAway(attempt.cow, session.maid));
            }
            if (attempt.arrow != null) result.add("arrow", entity(attempt.arrow));
        }
        return result;
    }

    private static JsonObject entity(Entity entity) {
        var result = new JsonObject();
        result.addProperty(
            "uuid",
            entity.getUniqueID()
                .toString());
        result.addProperty("registered", MaidSpawnChecks.present(entity));
        result.addProperty("dead", entity.isDead);
        result.addProperty("age", entity.ticksExisted);
        result.addProperty("x", entity.posX);
        result.addProperty("y", entity.posY);
        result.addProperty("z", entity.posZ);
        var raw = new NBTTagCompound();
        entity.writeToNBT(raw);
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                CompressedStreamTools.write(raw, output);
            }
            result.addProperty(
                "nativeNbtBase64",
                Base64.getEncoder()
                    .encodeToString(bytes.toByteArray()));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot preserve native shield input evidence", failure);
        }
        return result;
    }

    private static int wear(ItemStack stack) {
        if (stack == null) return -1;
        var tool = TConstructToolData.read(stack);
        return tool == null ? stack.getItemDamage() : tool.getInteger("Damage");
    }

    private static NBTTagCompound identity(Session session) {
        var tag = new NBTTagCompound();
        for (String key : new String[] { "Owner", "Run", "Actor" }) tag.setString(key, session.data.getString(key));
        return tag;
    }

    private static NBTTagList inventory(Session session) {
        var result = new NBTTagList();
        for (int slot = 0; slot < session.maid.getMaidInventory()
            .getSizeInventory(); slot++) {
            var item = session.maid.getMaidInventory()
                .getStackInSlot(slot);
            if (item == null) continue;
            var raw = ProtectedItemCodec.encode(item);
            raw.setByte("Slot", (byte) slot);
            result.appendTag(raw);
        }
        return result;
    }

    private static NBTTagList replaceOffhand(NBTTagList source, ItemStack stack) {
        var result = new NBTTagList();
        for (int i = 0; i < source.tagCount(); i++) {
            var raw = (NBTTagCompound) source.getCompoundTagAt(i)
                .copy();
            if ((raw.getByte("Slot") & 255) == MaidInventory.OFF_HAND) {
                raw = ProtectedItemCodec.encode(stack);
                raw.setByte("Slot", (byte) MaidInventory.OFF_HAND);
            }
            result.appendTag(raw);
        }
        return result;
    }

    private static NBTTagList list(NBTTagCompound data, String key) {
        require(
            data.hasKey(key, 9) && ((NBTTagList) data.getTag(key)).tagCount() == data.getTagList(key, 10)
                .tagCount(),
            "Unknown native shield inventory retained");
        return data.getTagList(key, 10);
    }

    private static void stage(NBTTagCompound record, String expected) {
        require(
            record.getString("Stage")
                .equals(expected),
            "Natural shield stage must be " + expected);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static final class Attempt {

        final Session session;
        final ItemStack shield;
        final JsonObject construction;
        final List<EntityArrow> arrows = new ArrayList<>();
        EntityCow cow;
        EntityArrow arrow;

        Attempt(Session session, ItemStack shield, JsonObject construction) {
            this.session = session;
            this.shield = shield;
            this.construction = construction;
        }
    }
}
