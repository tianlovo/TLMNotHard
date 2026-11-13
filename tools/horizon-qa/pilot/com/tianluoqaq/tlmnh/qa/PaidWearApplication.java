package com.tianluoqaq.tlmnh.qa;

/** 仅记录已执行的Mixin应用，不引用或提前加载Minecraft目标类 */
public final class PaidWearApplication {
    private static int applications;

    private PaidWearApplication() {}

    public static boolean requested() {
        return "v2".equals(System.getProperty("tlmnh.qaPaidWear", ""));
    }

    public static void applied(String target, String mixin) {
        if (!requested() || !target.equals("net.minecraft.item.ItemStack")
            || !mixin.equals("com.tianluoqaq.tlmnh.compat.hodgepodge.development.population.mixin.PaidWearItemStackMixin"))
            throw new IllegalStateException("Unexpected paid-wear mixin application");
        applications++;
        System.out.println("TLMNH_QA_PAID_WEAR_APPLIED " + target + " " + mixin);
    }

    public static int count() {
        return applications;
    }
}
