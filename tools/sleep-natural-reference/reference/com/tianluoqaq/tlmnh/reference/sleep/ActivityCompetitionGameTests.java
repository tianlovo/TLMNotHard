package com.tianluoqaq.tlmnh.reference.sleep;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 同合同三机制；原完整Brain自然更新，使用现有原生PlayerList登记和START/END观察器。 */
@GameTestHolder("tlmnh_activity_competition")
@PrefixGameTestTemplate(false)
public final class ActivityCompetitionGameTests {
    private ActivityCompetitionGameTests() {}

    @GameTest(template="cell",timeoutTicks=300,batch="activity_wait")
    public static void waitingThreatFood(GameTestHelper h){collect(h,"waiting_threat_food");}
    @GameTest(template="cell",timeoutTicks=360,batch="activity_air")
    public static void naturalMealLowAir(GameTestHelper h){collect(h,"meal_low_air");}
    @GameTest(template="cell",timeoutTicks=1060,batch="activity_cake")
    public static void sharedCakeInterruption(GameTestHelper h){collect(h,"shared_cake_interruption");}

    private static void collect(GameTestHelper h,String mechanism){
        FollowMechanismGameTests.collect(h,new Session(h,mechanism),"tlmnh.activityCompetitionProbe");
    }

    static class Session extends FollowMechanismGameTests.Session {
        final String mechanism;
        EntityMaid second;
        Creeper threat;
        int secondLiving,secondAge,triggerAt=-1,releaseAt=-1,previousBites;
        boolean extrasClosed;

        Session(GameTestHelper h,String mechanism){
            this(h,mechanism,"/tlmnh-activity-competition/contract.json","ACTIVITY_COMPETITION_V1",
                "activity-competition-original-v1","activity-competition-original-");
        }

        Session(GameTestHelper h,String mechanism,String resource,String expectedContract,String schema,String prefix){
            super(h,mechanism,resource,expectedContract,schema,prefix);
            this.mechanism=mechanism;
        }

        @Override void setup(){
            super.setup();
            for(int x=0;x<25;x++)for(int y=0;y<8;y++)for(int z=0;z<25;z++)helper.setBlock(x,y,z,y==0?Blocks.STONE:Blocks.AIR);
            output.addProperty("inputSchedule","fixed events and reactive inputs occur only after the recorded natural END frame");
            output.addProperty("crossEngineFullNbtEqualityRequired",false);
        }

        @Override String identityProperty(){return "tlmnh.activityCompetition.buildIdentity";}
        @Override int frameLimit(){return scene.get("frames").getAsInt();}
        @Override void onLiving(Entity entity){super.onLiving(entity);if(entity==second)secondLiving++;}

        @Override void spawn(){
            super.spawn();
            output.add("initialActorState",state(maid));
            if(second!=null)output.add("secondInitialState",state(second));
        }

        @Override void beforeSpawn(){
            output.addProperty("actualPreparedRestrictionRadius",maid.getRestrictRadius());
            output.addProperty("expectedPreparedRestrictionRadius",contract.get("restrictionRadius").getAsFloat());
            super.beforeSpawn();
            maid.setHealth(scene.get("health").getAsFloat());
            if(mechanism.equals("meal_low_air")){
                var soup=new ItemStack(Items.MUSHROOM_STEW);maid.getMaidInv().setStackInSlot(0,soup);
                output.addProperty("initialNonEmptyItemStackCount",1);
                output.addProperty("actualFoodDuration",soup.getUseDuration());
                var nutrition=soup.getFoodProperties(maid);
                helper.assertTrue(nutrition!=null,"Original soup nutrition unavailable");
                output.addProperty("actualFoodNutrition",nutrition.getNutrition()+nutrition.getNutrition()*nutrition.getSaturationModifier()*2);
            }else if(mechanism.equals("waiting_threat_food")){
                threat=new Creeper(EntityType.CREEPER,level);threat.setPersistenceRequired();threat.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
                var p=origin.offset(16,1,12);threat.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
                helper.assertTrue(level.addFreshEntity(threat)&&threat.isPersistenceRequired()&&level.getEntity(threat.getUUID())==threat,
                    "Controlled original persistent native threat was replaced or unregistered");
                var input=raw(threat);input.addProperty("persistent",threat.isPersistenceRequired());
                input.addProperty("movementBase",threat.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue());
                input.addProperty("movementValue",threat.getAttributeValue(Attributes.MOVEMENT_SPEED));
                input.addProperty("health",threat.getHealth());input.addProperty("maxHealth",threat.getMaxHealth());
                output.add("threatInitial",input);
            }else{
                maid.setPos(origin.getX()+10.5,origin.getY()+1,origin.getZ()+12.5);
                helper.setBlock(12,1,12,InitBlocks.SNACK_CABINET.get());helper.setBlock(12,2,12,Blocks.CAKE);
                second=new EntityMaid(level);second.setOwnerUUID(owner.getUUID());second.setTame(true);
                second.setTask(TaskManager.getIdleTask());second.setSchedule(MaidSchedule.ALL);second.setHunger(0);second.setPickup(false);
                second.setModelId(contract.get("model").getAsString());second.setSoundPackId(contract.get("initialSoundPackId").getAsString());
                second.setPos(origin.getX()+15.5,origin.getY()+1,origin.getZ()+12.5);
                second.setYRot(0);second.setXRot(0);second.setYHeadRot(0);second.setYBodyRot(0);
                second.yRotO=second.xRotO=second.yHeadRotO=second.yBodyRotO=0;
                helper.assertTrue(level.addFreshEntity(second)&&second.getOwner()==owner&&!second.isNoAi(),"Second original natural root registration failed");
                output.add("secondInitial",raw(second));output.add("secondAttributeInputs",SleepAttributeSnapshot.capture(second));
            }
        }

        @Override void afterFrame(int index){
            var row=frames.get(index).getAsJsonObject();row.add("maid",state(maid));
            if(second!=null){
                helper.assertTrue(second.tickCount==secondAge+1&&secondLiving==1&&second.getOwner()==owner&&!second.isNoAi(),
                    "Second original actor skipped a natural tick or changed owner/root");
                secondAge=second.tickCount;secondLiving=0;row.add("second",state(second));
            }
            if(threat!=null){row.addProperty("threatRegistered",level.getEntity(threat.getUUID())==threat);
                row.addProperty("threatDistanceSquared",maid.distanceToSqr(threat));}
            if(mechanism.equals("waiting_threat_food")){
                for(var spec:scene.getAsJsonArray("events")){
                    var e=spec.getAsJsonObject();if(index!=e.get("frame").getAsInt())continue;
                    var event=event(index,e.get("kind").getAsString());
                    if(e.get("kind").getAsString().equals("remove_threat"))threat.discard();
                    else{maid.setInSittingPose(false);plannedOwner=absolute(e.getAsJsonArray("ownerPosition"));}
                    event.addProperty("sittingAfter",maid.isInSittingPose());
                }
            }else if(mechanism.equals("meal_low_air")){
                helper.assertTrue(count(maid,Items.MUSHROOM_STEW)+count(maid,Items.BOWL)==1,
                    "Original natural meal lost or duplicated its only soup/container");
                if(triggerAt<0&&usingSoup(maid)){
                    triggerAt=index;event(index,"natural_meal_low_air");water(true);maid.setAirSupply(60);
                }else if(triggerAt>=0&&releaseAt<0&&index==triggerAt+40){
                    releaseAt=index;event(index,"clear_water_input");water(false);
                }
            }else cakeFrame(index,row);
            if(index+1==frameLimit()){
                output.addProperty("reactiveTriggerFrame",triggerAt);output.addProperty("reactiveReleaseFrame",releaseAt);
                output.addProperty("scenarioInputExercised",mechanism.equals("waiting_threat_food")||triggerAt>=0&&releaseAt>=0);
                output.addProperty("resourceAssertionsPassed",true);
                output.addProperty("resourceAssertionScope",mechanism.equals("meal_low_air")?"one_soup_or_bowl_total_each_frame"
                    :mechanism.equals("shared_cake_interruption")?"one_native_cake_monotonic_bites_zero_to_seven"
                    :"no_material_assertion_dropped_apple_may_be_naturally_consumed");
            }
        }

        void cakeFrame(int index,JsonObject row){
                int bites=cakeBites();row.addProperty("cakeBitesConsumed",bites);
                helper.assertTrue(bites>=previousBites&&bites<=7&&bites-previousBites<=2,
                    "One original shared cake produced an impossible per-tick bite change");previousBites=bites;
                if(triggerAt<0&&bites>0){triggerAt=index;event(index,"first_shared_cake_bite_wait");maid.setInSittingPose(true);second.setInSittingPose(true);}
                else if(triggerAt>=0&&releaseAt<0&&index==triggerAt+40){releaseAt=index;event(index,"release_both_waiting");maid.setInSittingPose(false);second.setInSittingPose(false);}
        }

        JsonObject event(int index,String kind){var r=new JsonObject();r.addProperty("frame",index);r.addProperty("kind",kind);
            r.addProperty("phase","END_after_recorded_frame");r.add("maidBefore",state(maid));if(second!=null)r.add("secondBefore",state(second));events.add(r);return r;}
        void water(boolean present){for(int x=11;x<=13;x++)for(int y=1;y<=3;y++)for(int z=11;z<=13;z++)
            level.setBlock(origin.offset(x,y,z),present?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),2);}
        boolean usingSoup(EntityMaid actor){return actor.isUsingItem()&&actor.getUseItem().is(Items.MUSHROOM_STEW);}
        JsonObject state(EntityMaid actor){var r=new JsonObject();r.addProperty("age",actor.tickCount);r.addProperty("health",actor.getHealth());
            r.addProperty("air",actor.getAirSupply());r.addProperty("hunger",actor.getHunger());r.addProperty("favorability",actor.getFavorability());
            r.addProperty("sitting",actor.isInSittingPose());r.addProperty("inWater",actor.isInWater());r.addProperty("usingFood",usingSoup(actor));
            r.addProperty("goingToBreath",actor.getSwimManager().isGoingToBreath());
            r.addProperty("soup",count(actor,Items.MUSHROOM_STEW));r.addProperty("bowls",count(actor,Items.BOWL));r.addProperty("apples",count(actor,Items.APPLE));
            r.add("position",relative(actor.position()));r.addProperty("activity",actor.getBrain().getActiveActivities().toString());return r;}
        int count(EntityMaid actor,Item item){int n=0;for(int i=0;i<actor.getMaidInv().getSlots();i++){var s=actor.getMaidInv().getStackInSlot(i);if(s.is(item))n+=s.getCount();}
            if(actor.getMainHandItem().is(item))n+=actor.getMainHandItem().getCount();if(actor.getOffhandItem().is(item))n+=actor.getOffhandItem().getCount();return n;}
        int cakeBites(){var state=level.getBlockState(origin.offset(12,2,12));return state.is(Blocks.CAKE)?state.getValue(CakeBlock.BITES):7;}
        JsonObject raw(Entity actor){var tag=new CompoundTag();actor.saveWithoutId(tag);var r=new JsonObject();r.addProperty("class",actor.getClass().getName());
            r.addProperty("uuid",actor.getUUID().toString());r.add("position",relative(actor.position()));
            r.addProperty("rawNBT",tag.toString());r.add("binaryNBT",SleepAttributeSnapshot.binaryNbt(tag));return r;}

        @Override JsonArray layout(){var out=new JsonArray();
            for(int x=0;x<25;x++)for(int y=0;y<8;y++)for(int z=0;z<25;z++){
                var block=level.getBlockState(origin.offset(x,y,z));if(block.isAir())continue;var r=new JsonObject();
                r.addProperty("x",x);r.addProperty("y",y);r.addProperty("z",z);
                r.addProperty("semantic",block.is(Blocks.STONE)?"stone":block.is(Blocks.CAKE)?"cake":block.is(InitBlocks.SNACK_CABINET.get())?"snack_cabinet":"unknown");
                r.addProperty("registry",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                r.addProperty("rawBlockState",block.toString());r.addProperty("metadata",block.is(Blocks.CAKE)?block.getValue(CakeBlock.BITES):0);out.add(r);
            }return out;}

        @Override void close(){
            if(closed||extrasClosed)return;extrasClosed=true;
            try{if(second!=null)second.discard();if(threat!=null)threat.discard();
                for(int x=0;x<25;x++)for(int y=0;y<8;y++)for(int z=0;z<25;z++)helper.setBlock(x,y,z,Blocks.AIR);
                output.addProperty("extraCleanupComplete",true);
            }finally{super.close();}
        }
    }
}
