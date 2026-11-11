/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/util/RateLimiter.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt and licenses/GeckoLib-MIT.txt
 * Adaptation: Java 8 collections and pure animation target; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.util;

public class RateLimiter {

    private final float interval;
    private float aggregate;
    private float lastRequestTime;

    public RateLimiter(int limitPerSec) {
        if (limitPerSec <= 0) throw new IllegalArgumentException("Rate must be positive");
        this.interval = 1f / limitPerSec;
        this.aggregate = this.interval;
        this.lastRequestTime = 0;
    }

    public boolean request(float time) {
        if (!Float.isFinite(time) || time < this.lastRequestTime) return false;
        this.aggregate += time - this.lastRequestTime;
        this.lastRequestTime = time;

        if (this.aggregate < this.interval) {
            return false;
        }

        this.aggregate = this.aggregate % this.interval;
        return true;
    }
}
