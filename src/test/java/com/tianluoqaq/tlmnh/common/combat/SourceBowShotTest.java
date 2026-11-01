package com.tianluoqaq.tlmnh.common.combat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.Test;

public class SourceBowShotTest {

    @Test
    public void matchesActualModernMathOracleBitForBitAcrossClampsNoiseAndNormalizationBoundary() throws Exception {
        var input = getClass().getResourceAsStream("/fixtures/source-bow-oracle.tsv");
        assertNotNull(input);
        int cases = 0;
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                var fields = line.split("\t");
                assertEquals(12, fields.length);
                var random = new Random((long) Double.parseDouble(fields[7]));
                double damage = SourceBowShot
                    .baseDamage(Float.parseFloat(fields[5]), (int) Double.parseDouble(fields[6]), random::nextDouble);
                var velocity = SourceBowShot.velocity(
                    new BallisticVector(
                        Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]),
                        Double.parseDouble(fields[3])),
                    Float.parseFloat(fields[4]),
                    random::nextDouble);
                double[] actual = { damage, velocity.x, velocity.y, velocity.z };
                for (int i = 0; i < actual.length; i++) assertEquals(
                    "Actual Minecraft numeric case " + fields[0] + " component " + i,
                    Long.parseUnsignedLong(fields[8 + i], 16),
                    Double.doubleToLongBits(actual[i]));
                cases++;
            }
        }
        assertEquals(7, cases);
    }
}
