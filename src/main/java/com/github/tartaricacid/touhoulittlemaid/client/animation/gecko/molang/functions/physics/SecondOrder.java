/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original:
 * src/main/java/com/github/tartaricacid/touhoulittlemaid/client/animation/gecko/molang/functions/physics/SecondOrder.
 * java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: built-in animation frame data only; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.client.animation.gecko.molang.functions.physics;

/**
 * @author MicroCraft
 *
 *         <a href="https://www.youtube.com/watch?v=KPoeNZZ6H4s">Giving Personality to Procedural Animations using
 *         Math</a>
 */
public class SecondOrder implements IPhysics {

    private static final float MAX_TIME_STEP = 1;

    private float inputFunction = 0;
    private float lastSimulation = 0;
    private float lastSimulationDot = 0;
    private float arg0;
    private float arg1;
    private float arg2;
    private float arg3;

    public SecondOrder(float input, float frequency, float coefficient, float response) {
        this.arg0 = input;
        this.arg1 = Math.max(0, Math.min(5, frequency));
        this.arg2 = Math.max(0, Math.min(1, coefficient));
        this.arg3 = response;
    }

    @Override
    public void update(float timeStep) {
        if (!Float.isFinite(timeStep) || timeStep <= 0) return;
        if (!Float.isFinite(arg0) || !Float.isFinite(arg1) || !Float.isFinite(arg2) || !Float.isFinite(arg3)) return;
        // 长时间未绘制后只追算一秒，正常渲染间隔保持上游公式
        timeStep = Math.min(timeStep, MAX_TIME_STEP);
        float input = arg0;
        float frequency = Math.max(0, Math.min(5, arg1));
        float coefficient = Math.max(0, Math.min(1, arg2));
        float response = arg3;
        if (frequency <= 0) {
            inputFunction = lastSimulation = input;
            lastSimulationDot = 0;
            return;
        }

        float k1 = coefficient / (float) Math.PI / frequency;
        float k2 = 1 / (2 * (float) Math.PI * frequency) / (2 * (float) Math.PI * frequency);
        float k3 = response * coefficient / 2 / (float) Math.PI / frequency;

        float inputFunctionDot = (input - this.inputFunction) / timeStep;
        this.inputFunction = input;

        float maxTimeStep = (float) Math.sqrt(4 * k2 + k1 * k1) - k1;
        int cycleTime = (int) Math.ceil(timeStep / maxTimeStep);
        timeStep = timeStep / cycleTime;

        var lastSimulationDot = this.lastSimulationDot;
        var lastSimulation = this.lastSimulation;
        for (; cycleTime > 0; cycleTime--) {
            lastSimulation = lastSimulation + timeStep * lastSimulationDot;
            lastSimulationDot = lastSimulationDot
                + timeStep * (k3 * inputFunctionDot + input - lastSimulation - k1 * lastSimulationDot) / k2;
        }
        if (Float.isFinite(lastSimulation) && Float.isFinite(lastSimulationDot)) {
            this.lastSimulation = lastSimulation;
            this.lastSimulationDot = lastSimulationDot;
        } else {
            this.lastSimulation = input;
            this.lastSimulationDot = 0;
        }
    }

    @Override
    public void setArgs(float arg0, float arg1, float arg2, float arg3) {
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.arg3 = arg3;
    }

    @Override
    public float getValue() {
        return lastSimulation;
    }
}
