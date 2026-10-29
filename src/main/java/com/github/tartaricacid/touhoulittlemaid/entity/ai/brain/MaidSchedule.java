/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: entity/ai/brain/MaidSchedule.java, MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: the 1.7.10 entity uses a byte DataWatcher instead of EntityDataSerializer
 */
package com.github.tartaricacid.touhoulittlemaid.entity.ai.brain;

public enum MaidSchedule {
    DAY,
    NIGHT,
    ALL;
}
