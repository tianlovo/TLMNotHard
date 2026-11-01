package com.tianluoqaq.tlmnh.reference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** 在固定Minecraft 1.20.1的实际类上生成纯数值输入，不启动游戏，也不替代自然O/F对照 */
public final class SourceBowMathOracle {

    public static void main(String[] args) throws Exception {
        var text = new StringBuilder("# Minecraft 1.20.1 actual Vec3/RandomSource/Mth numeric oracle\n");
        for (var type : new Class<?>[] { Vec3.class, RandomSource.class, Mth.class, RandomSource.create(0).getClass() }) {
            try (var input = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
                text.append("# ").append(type.getName()).append(' ')
                    .append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())))
                    .append('\n');
            }
        }
        double[][] cases = {
            { 0, 0, 0, 0, 1, 0, 514 },
            { 1, .1, -1, 1, 1, 1, 555 },
            { 16, 2, 3, 16, 1, 2, 87 },
            { -32, -8, 5, 32, .75, 3, 114 },
            { 120, 60, -160, 200, 1, 3, 115 },
            { .00001, 0, 0, 1, 1, 2, 116 },
            { .0001, 0, 0, 10, 1, 2, 117 }
        };
        for (int index = 0; index < cases.length; index++) {
            var row = cases[index];
            float distance = (float) row[3], charge = (float) row[4];
            int difficulty = (int) row[5];
            var random = RandomSource.create((long) row[6]);
            double damage = (double) (charge * 2F) + random.triangle(difficulty * .11, .57425);
            float speed = Mth.clamp(distance / 10F, 1.6F, 3.2F);
            float inaccuracy = 1F - Mth.clamp(distance / 100F, 0F, .9F);
            double spread = .0172275 * (double) inaccuracy;
            var velocity = new Vec3(row[0], row[1], row[2]).normalize()
                .add(random.triangle(0, spread), random.triangle(0, spread), random.triangle(0, spread))
                .scale((double) speed);
            text.append(index);
            for (double value : row) text.append('\t').append(value);
            for (double value : new double[] { damage, velocity.x, velocity.y, velocity.z })
                text.append('\t').append(Long.toHexString(Double.doubleToLongBits(value)));
            text.append('\n');
        }
        Files.writeString(Path.of(args[0]), text, StandardCharsets.UTF_8);
    }
}
