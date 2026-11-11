/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: src/main/java/com/github/tartaricacid/touhoulittlemaid/geckolib3/core/molang/builtin/MathBinding.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Java 8 collections and context boundary; see SOURCE_PROVENANCE.md
 */
package com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin;

import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.binding.ContextBinding;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.ACos;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.ASin;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.ATan2;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Abs;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Atan;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Ceil;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Clamp;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Cos;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.DieRoll;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.DieRollInteger;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Exp;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Floor;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.HermitBlend;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Lerp;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.LerpRotate;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Ln;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Max;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Min;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.MinAngle;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Mod;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Pow;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Random;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.RandomInteger;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Round;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Sin;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Sqrt;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.molang.builtin.math.Trunc;

public class MathBinding extends ContextBinding {

    public static final MathBinding INSTANCE = new MathBinding();

    private MathBinding() {
        /* 常量 */
        constValue("pi", Math.PI);
        constValue("e", Math.E);

        /* 取整函数 */
        function("floor", new Floor());
        function("round", new Round());
        function("ceil", new Ceil());
        function("trunc", new Trunc());

        /* 比较函数 */
        function("clamp", new Clamp());
        function("max", new Max());
        function("min", new Min());

        /* 经典数学函数 */
        function("abs", new Abs());
        function("exp", new Exp());
        function("ln", new Ln());
        function("sqrt", new Sqrt());
        function("mod", new Mod());
        function("pow", new Pow());

        /* 三角函数 */
        function("sin", new Sin()); // degree
        function("cos", new Cos()); // degree
        function("acos", new ACos());
        function("asin", new ASin());
        function("atan", new Atan());
        function("atan2", new ATan2());

        /* 实用工具 */
        function("lerp", new Lerp());
        function("lerprotate", new LerpRotate());
        function("random", new Random());
        function("random_integer", new RandomInteger());
        function("die_roll", new DieRoll());
        function("die_roll_integer", new DieRollInteger());
        function("hermite_blend", new HermitBlend());

        /* 其它 */
        function("min_angle", new MinAngle());

        /* 非标准命名，兼容原 geckolib */
        function("randomi", new RandomInteger());
        function("roll", new DieRoll());
        function("rolli", new DieRollInteger());
        function("hermite", new HermitBlend());
    }
}
