package com.tianluoqaq.tlmnh.common.config;

import java.util.ArrayList;
import java.util.Arrays;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.biome.BiomeGenBase;

import com.tianluoqaq.tlmnh.common.protection.IntegrationGuard;

import cpw.mods.fml.relauncher.Side;

/** 自然生成只使用明确有效的群系，配置原值和不可用条目的诊断分别保留 */
public final class MaidAcquisitionDiagnostics {

    private MaidAcquisitionDiagnostics() {}

    public static BiomeGenBase[] resolveBiomes(int[] configured) {
        var biomes = new ArrayList<BiomeGenBase>();
        var valid = new ArrayList<Integer>();
        var invalid = new ArrayList<Integer>();
        var registry = BiomeGenBase.getBiomeGenArray();
        if (configured != null) for (int id : configured) {
            var biome = id >= 0 && id < registry.length ? registry[id] : null;
            if (biome == null) {
                if (!invalid.contains(id)) invalid.add(id);
            } else if (!biomes.contains(biome)) {
                biomes.add(biome);
                valid.add(id);
            }
        }
        if (biomes.isEmpty() || !invalid.isEmpty()) {
            var issue = new InvalidBiomes(
                Arrays.toString(configured),
                valid.toString(),
                invalid.toString(),
                biomes.isEmpty());
            IntegrationGuard
                .report(Side.SERVER, "acquisition/natural_biomes/" + (biomes.isEmpty() ? "none" : "partial"), issue);
        }
        return biomes.toArray(new BiomeGenBase[0]);
    }

    /** 复用统一告警的每玩家去重，只为这类配置问题补充准确的部分/全部失效说明 */
    public static ChatComponentTranslation message(IntegrationGuard.Fault fault) {
        if (!(fault.cause instanceof InvalidBiomes issue)) return null;
        return issue.none
            ? new ChatComponentTranslation("chat.tlmnh.acquisition.biomes_none", fault.code, issue.configured)
            : new ChatComponentTranslation(
                "chat.tlmnh.acquisition.biomes_partial",
                fault.code,
                issue.invalid,
                issue.valid);
    }

    private static final class InvalidBiomes extends IllegalArgumentException {

        private final String configured, valid, invalid;
        private final boolean none;

        private InvalidBiomes(String configured, String valid, String invalid, boolean none) {
            super(
                "Natural maid biome configuration contains no usable biomes or unavailable IDs: configured="
                    + configured
                    + "; usable="
                    + valid
                    + "; unavailable="
                    + invalid
                    + (none ? "; no natural spawn registration" : "; usable entries remain enabled")
                    + "; configuration unchanged; correct it and restart");
            this.configured = configured;
            this.valid = valid;
            this.invalid = invalid;
            this.none = none;
        }
    }
}
