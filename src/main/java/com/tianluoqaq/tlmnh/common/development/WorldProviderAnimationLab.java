package com.tianluoqaq.tlmnh.common.development;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderFlat;

/** 仅供显式开发模式使用的空气维度，时间与天气不写入共享的主世界数据 */
public final class WorldProviderAnimationLab extends WorldProvider {

    @Override
    protected void registerWorldChunkManager() {
        worldChunkMgr = new WorldChunkManagerHell(BiomeGenBase.plains, 0);
    }

    @Override
    public IChunkProvider createChunkGenerator() {
        return new ChunkProviderFlat(worldObj, getSeed(), false, "2;0;1;");
    }

    @Override
    public long getSeed() {
        return 0x544c4d4e48L;
    }

    @Override
    public long getWorldTime() {
        return 6000;
    }

    @Override
    public void setWorldTime(long time) {}

    @Override
    public float calculateCelestialAngle(long time, float partialTick) {
        return 0;
    }

    @Override
    public void calculateInitialWeather() {
        updateWeather();
    }

    @Override
    public void updateWeather() {
        worldObj.setRainStrength(0);
        worldObj.setThunderStrength(0);
    }

    @Override
    public void setAllowedSpawnTypes(boolean hostile, boolean peaceful) {
        super.setAllowedSpawnTypes(false, false);
    }

    @Override
    public boolean canDoLightning(Chunk chunk) {
        return false;
    }

    @Override
    public boolean canDoRainSnowIce(Chunk chunk) {
        return false;
    }

    @Override
    public ChunkCoordinates getSpawnPoint() {
        return new ChunkCoordinates(0, 65, -5);
    }

    @Override
    public boolean canRespawnHere() {
        return false;
    }

    @Override
    public String getDimensionName() {
        return "TLMNH Animation Lab";
    }

    @Override
    public String getSaveFolder() {
        return "TLMNHAnimationLab";
    }
}
