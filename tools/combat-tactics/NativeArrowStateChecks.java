import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.tianluoqaq.tlmnh.common.combat.ballistics.NativeArrowState;

/** 用实际原版箭的公开NBT入口重现嵌入后保留速度，覆盖重新起飞与未知子类 */
public final class NativeArrowStateChecks {

    public static void main(String[] args) {
        EntityArrow arrow = new EntityArrow((World) null);
        arrow.motionX = 1.5;
        arrow.motionY = -.1;
        arrow.motionZ = .01;
        require(!NativeArrowState.embedded(arrow), "Actual flying native arrow was excluded");
        NBTTagCompound state = new NBTTagCompound();
        arrow.writeEntityToNBT(state);
        state.setByte("inGround", (byte) 1);
        arrow.readEntityFromNBT(state);
        require(
            arrow.motionX == 1.5 && NativeArrowState.embedded(arrow),
            "Native embedded arrow with retained nonzero velocity remained a flying threat");
        require(
            arrow.motionX == 1.5 && arrow.motionY == -.1 && arrow.motionZ == .01,
            "Passive state read changed actual motion");
        state.setByte("inGround", (byte) 0);
        arrow.readEntityFromNBT(state);
        require(!NativeArrowState.embedded(arrow), "Native dislodged arrow stayed excluded");

        EntityArrow unknown = new EntityArrow((World) null) {};
        state.setByte("inGround", (byte) 1);
        unknown.readEntityFromNBT(state);
        require(!NativeArrowState.embedded(unknown), "Unknown arrow subclass gained native-state exclusion");
        require(!NativeArrowState.embedded(null), "Missing arrow became a known embedded arrow");
        System.out.println(
            "NativeArrowStateChecks: actual native flying/embedded/dislodged state and unknown boundary passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
