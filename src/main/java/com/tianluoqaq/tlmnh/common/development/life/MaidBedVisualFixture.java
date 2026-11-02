package com.tianluoqaq.tlmnh.common.development.life;

import java.util.UUID;

import net.minecraft.block.BlockBed;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.tianluoqaq.tlmnh.TLMNotHard;
import com.tianluoqaq.tlmnh.common.development.AnimationLab;
import com.tianluoqaq.tlmnh.common.life.TileEntityMaidBed;
import com.tianluoqaq.tlmnh.common.life.bed.MaidBedColor;
import com.tianluoqaq.tlmnh.common.registry.MaidLifeContent;

/** 仅在自有虚空展台创建无物品来源的床显示夹具；持久标记用于校验后清理，不恢复未知改动 */
public final class MaidBedVisualFixture {

    private static final String DATA = "TLMNH_BedVisualFixture";

    private MaidBedVisualFixture() {}

    public static void set(EntityPlayerMP player, String action) {
        if (!Boolean.getBoolean("tlmnh.devCommands")) throw new IllegalStateException("Development fixture required");
        AnimationLab.ownedSceneMaid(player);
        var world = player.worldObj;
        var journal = journal(world);
        if (!journal.kind.isEmpty() && !journal.owner.equals(
            player.getUniqueID()
                .toString()))
            throw new IllegalStateException("Another observer owns the bed fixture");
        if (action.equals("color-black") || action.equals("color-white")) {
            if (!journal.kind.equals("palette") || !journal.ready)
                throw new IllegalStateException("Ready palette required");
            validate(world, journal);
            var color = action.equals("color-black") ? MaidBedColor.BLACK : MaidBedColor.WHITE;
            var bed = tile(world, cells(journal)[0]);
            journal.firstBlack = color == MaidBedColor.BLACK;
            journal.markDirty();
            if (bed.getColor() != color && !bed.setColor(color))
                throw new IllegalStateException("Fixture color update refused");
            log(world, journal);
            return;
        }
        if (!(action.equals("palette") || action.equals("rotation") || action.equals("clear")))
            throw new IllegalArgumentException("Unknown bed visual fixture action");
        if (!journal.kind.isEmpty()) {
            validate(world, journal);
            for (var cell : cells(journal)) {
                world.setBlock(cell.x, 65, cell.z, Blocks.air, 0, 2);
                world.setBlock(cell.footX(), 65, cell.footZ(), Blocks.air, 0, 2);
            }
            journal.kind = journal.owner = "";
            journal.ready = false;
            journal.firstBlack = false;
            journal.markDirty();
        }
        if (action.equals("clear")) {
            TLMNotHard.LOG.info("TLMNH_BED_VISUAL_CLEAR complete=true");
            return;
        }
        var next = new Journal(DATA);
        next.kind = action;
        for (var cell : cells(next)) {
            require(
                world.isAirBlock(cell.x, 65, cell.z) && world.getTileEntity(cell.x, 65, cell.z) == null
                    && world.isAirBlock(cell.footX(), 65, cell.footZ())
                    && world.getTileEntity(cell.footX(), 65, cell.footZ()) == null
                    && world.getBlock(cell.x, 64, cell.z) == Blocks.wool
                    && world.getBlock(cell.footX(), 64, cell.footZ()) == Blocks.wool,
                "Fixture area is not empty lab floor");
        }
        journal.kind = action;
        journal.owner = player.getUniqueID()
            .toString();
        journal.ready = false;
        journal.firstBlack = false;
        journal.markDirty();
        for (var cell : cells(journal)) {
            require(
                world.setBlock(cell.footX(), 65, cell.footZ(), MaidLifeContent.maidBed, cell.direction, 2),
                "Foot write failed");
            require(
                world.setBlock(cell.x, 65, cell.z, MaidLifeContent.maidBed, cell.direction | 8, 2),
                "Head write failed");
            var bed = tile(world, cell);
            require(bed.getColor() == cell.color || bed.setColor(cell.color), "Fixture color write failed");
        }
        journal.ready = true;
        journal.markDirty();
        validate(world, journal);
        log(world, journal);
    }

    private static void validate(World world, Journal journal) {
        for (var cell : cells(journal)) {
            boolean headAir = world.isAirBlock(cell.x, 65, cell.z),
                footAir = world.isAirBlock(cell.footX(), 65, cell.footZ());
            if (!journal.ready && headAir && footAir) continue;
            require(!journal.ready || !headAir && !footAir, "A ready fixture bed was removed; retained for review");
            if (!headAir) {
                require(
                    world.getBlock(cell.x, 65, cell.z) == MaidLifeContent.maidBed
                        && world.getBlockMetadata(cell.x, 65, cell.z) == (cell.direction | 8),
                    "Changed fixture head retained");
                var bed = tile(world, cell);
                require(
                    !bed.dropQueued()
                        && (bed.getColor() == cell.color || !journal.ready && bed.getColor() == MaidBedColor.PINK),
                    "Changed fixture color or item transfer retained");
            }
            if (!footAir) require(
                world.getBlock(cell.footX(), 65, cell.footZ()) == MaidLifeContent.maidBed
                    && world.getBlockMetadata(cell.footX(), 65, cell.footZ()) == cell.direction
                    && world.getTileEntity(cell.footX(), 65, cell.footZ()) == null,
                "Changed fixture foot retained");
        }
    }

    private static TileEntityMaidBed tile(World world, Cell cell) {
        var raw = world.getTileEntity(cell.x, 65, cell.z);
        require(raw instanceof TileEntityMaidBed && !raw.isInvalid(), "Exact bed tile required");
        return (TileEntityMaidBed) raw;
    }

    private static Cell[] cells(Journal journal) {
        if (journal.kind.equals("rotation"))
            return new Cell[] { new Cell(-6, 0, 0, MaidBedColor.PINK), new Cell(-2, 0, 1, MaidBedColor.PINK),
                new Cell(2, 0, 2, MaidBedColor.PINK), new Cell(6, 0, 3, MaidBedColor.PINK) };
        if (journal.kind.equals("palette"))
            return new Cell[] { new Cell(-6, -3, 2, journal.firstBlack ? MaidBedColor.BLACK : MaidBedColor.WHITE),
                new Cell(-2, -3, 2, MaidBedColor.BLACK), new Cell(2, -3, 2, MaidBedColor.YELLOW),
                new Cell(6, -3, 2, MaidBedColor.BLUE), new Cell(-6, 3, 2, MaidBedColor.GREEN),
                new Cell(-2, 3, 2, MaidBedColor.PURPLE), new Cell(2, 3, 2, MaidBedColor.PINK) };
        return new Cell[0];
    }

    private static void log(World world, Journal journal) {
        for (var cell : cells(journal)) TLMNotHard.LOG.info(
            "TLMNH_BED_VISUAL_CELL kind={} head={},{},{} color={} direction={} tile_valid={}",
            journal.kind,
            cell.x,
            65,
            cell.z,
            cell.color.resourceName,
            cell.direction,
            tile(world, cell).complete());
    }

    private static Journal journal(World world) {
        require(AnimationLab.isLab(world) && world.mapStorage != null, "Own animation lab storage required");
        var journal = (Journal) world.mapStorage.loadData(Journal.class, DATA);
        var file = world.getSaveHandler()
            .getMapFileFromName(DATA);
        require(
            file == null || !file.exists() || journal != null && journal.readComplete,
            "Unreadable fixture journal retained");
        if (journal == null) {
            journal = new Journal(DATA);
            journal.readComplete = true;
            world.mapStorage.setData(DATA, journal);
        }
        return journal;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static final class Cell {

        final int x, z, direction;
        final MaidBedColor color;

        Cell(int x, int z, int direction, MaidBedColor color) {
            this.x = x;
            this.z = z;
            this.direction = direction;
            this.color = color;
        }

        int footX() {
            return x - BlockBed.field_149981_a[direction][0];
        }

        int footZ() {
            return z - BlockBed.field_149981_a[direction][1];
        }
    }

    public static final class Journal extends WorldSavedData {

        String owner = "", kind = "";
        boolean ready, firstBlack, readComplete;
        NBTTagCompound raw;

        public Journal(String name) {
            super(name);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            raw = (NBTTagCompound) tag.copy();
            require(tag.hasKey("Schema", 3) && tag.getInteger("Schema") == 1, "Unknown fixture schema retained");
            owner = tag.getString("Owner");
            kind = tag.getString("Kind");
            require(
                kind.isEmpty() || kind.equals("palette") || kind.equals("rotation"),
                "Unknown fixture layout retained");
            if (!kind.isEmpty()) UUID.fromString(owner);
            ready = tag.getBoolean("Ready");
            firstBlack = tag.getBoolean("FirstBlack");
            readComplete = true;
        }

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            if (!readComplete && raw != null) {
                for (Object key : raw.func_150296_c()) tag.setTag(
                    (String) key,
                    raw.getTag((String) key)
                        .copy());
                return;
            }
            tag.setInteger("Schema", 1);
            tag.setString("Owner", owner);
            tag.setString("Kind", kind);
            tag.setBoolean("Ready", ready);
            tag.setBoolean("FirstBlack", firstBlack);
        }
    }
}
