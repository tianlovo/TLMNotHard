package com.tianluoqaq.tlmnh.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;

/** 按精确签名绑定可选 API，避免枚举未引用的客户端方法类型 */
public final class CompatExactMethod {

    private final MethodHandle handle;
    private final boolean isStatic;

    private CompatExactMethod(MethodHandle handle, boolean isStatic) {
        this.handle = handle;
        this.isStatic = isStatic;
    }

    public static CompatExactMethod virtual(Class<?> owner, String name, Class<?> returns, Class<?>... args)
        throws ReflectiveOperationException {
        return new CompatExactMethod(
            MethodHandles.publicLookup()
                .findVirtual(owner, name, MethodType.methodType(returns, args)),
            false);
    }

    public static CompatExactMethod staticMethod(Class<?> owner, String name, Class<?> returns, Class<?>... args)
        throws ReflectiveOperationException {
        return new CompatExactMethod(
            MethodHandles.publicLookup()
                .findStatic(owner, name, MethodType.methodType(returns, args)),
            true);
    }

    public Object invoke(Object receiver, Object... arguments) throws ReflectiveOperationException {
        var values = new ArrayList<Object>();
        if (!isStatic) values.add(receiver);
        Collections.addAll(values, arguments);
        try {
            return handle.invokeWithArguments(values);
        } catch (Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new InvocationTargetException(failure);
        }
    }
}
