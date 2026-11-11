package com.tianluoqaq.tlmnh.common.foundation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 共享运行期上下文，向下层业务包提供日志与 FML 实例引用，消除叶子包反向依赖 {@code TLMNotHard} 主入口的环路
 */
public final class ModContext {

    public static final Logger LOG = LogManager.getLogger(ModIds.MOD_ID);

    private static volatile Object modInstance;

    private ModContext() {}

    public static void bindModInstance(Object instance) {
        modInstance = instance;
    }

    public static Object modInstance() {
        Object current = modInstance;
        if (current == null) {
            throw new IllegalStateException(FoundationDiagnostics.MSG_MODCONTEXT_MODINSTANCE_ACCESSED_BEFORE_FML);
        }
        return current;
    }
}
