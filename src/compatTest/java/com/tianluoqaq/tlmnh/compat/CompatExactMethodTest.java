package com.tianluoqaq.tlmnh.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** 覆盖可选类含未安装客户端参数类型时的专服解析故障 */
public class CompatExactMethodTest {

    @Test
    public void exactDescriptorDoesNotResolveUnrelatedMissingClientSignature() throws Exception {
        var bytes = new ClassWriter(0);
        bytes.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/OptionalApi", null, "java/lang/Object", null);
        var good = bytes.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "serverValue", "()I", null, null);
        good.visitCode();
        good.visitIntInsn(Opcodes.BIPUSH, 7);
        good.visitInsn(Opcodes.IRETURN);
        good.visitMaxs(1, 0);
        good.visitEnd();
        var client = bytes.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "registerClient",
            "(Lfixture/MissingClientType;)V",
            null,
            null);
        client.visitCode();
        client.visitInsn(Opcodes.RETURN);
        client.visitMaxs(0, 1);
        client.visitEnd();
        bytes.visitEnd();
        var type = new FixtureLoader().define(bytes.toByteArray());
        try {
            type.getMethod("serverValue");
            fail("Control reflection must expose the missing client type");
        } catch (NoClassDefFoundError expected) {
            // Class.getMethod scans signatures beyond the requested public method.
        }
        assertEquals(
            7,
            CompatExactMethod.staticMethod(type, "serverValue", int.class)
                .invoke(null));
    }

    private static final class FixtureLoader extends ClassLoader {

        Class<?> define(byte[] bytes) {
            return defineClass("fixture.OptionalApi", bytes, 0, bytes.length);
        }
    }
}
