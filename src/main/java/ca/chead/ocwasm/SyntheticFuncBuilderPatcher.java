package ca.chead.ocwasm;

import asmble.compile.jvm.ClsContext;
import java.io.InputStream;
import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches Asmble’s {@link asmble.compile.jvm.SyntheticFuncBuilder} to fix <a
 * href="https://github.com/cretz/asmble/issues/40">Asmble bug 40</a>.
 */
public final class SyntheticFuncBuilderPatcher {
	/**
	 * Generates the code for the {@code bootstrapIndirect} method.
	 *
	 * @param context The compilation context.
	 * @param name The name of the method to generate.
	 * @return The generated method.
	 */
	public static MethodNode buildIndirectBootstrap(final ClsContext context, final String name) {
		// Sanity check.
		Objects.requireNonNull(context);
		Objects.requireNonNull(name);

		// Since compile-time and runtime are the same context here, we can
		// just generate an actual call to RuntimeHelpers itself, rather than
		// doing what stock Asmble does which is copy the method’s bytecode
		// into the generated class.
		final String descriptor = Type.getMethodDescriptor(Type.getType(CallSite.class), Type.getType(MethodHandles.Lookup.class), Type.getType(String.class), Type.getType(MethodType.class));
		final MethodNode method = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, descriptor, null, new String[]{Type.getInternalName(Exception.class)});
		final InsnList il = method.instructions;
		il.add(new VarInsnNode(Opcodes.ALOAD, 0));
		il.add(new VarInsnNode(Opcodes.ALOAD, 1));
		il.add(new VarInsnNode(Opcodes.ALOAD, 2));
		il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "asmble/compile/jvm/RuntimeHelpers", "bootstrapIndirect", descriptor, false));
		il.add(new InsnNode(Opcodes.ARETURN));
		return method;
	}

	/**
	 * Applies the patch.
	 */
	public static void patch() {
		patchSyntheticFuncBuilder();
		patchRuntimeHelpers();
	}

	/**
	 * Patches the {@link asmble.compile.jvm.SyntheticFuncBuilder} class itself
	 * to point at {@link SyntheticFuncBuilderPatcher#buildIndirectBootstrap}
	 * rather than the code it originally wanted to run.
	 */
	private static void patchSyntheticFuncBuilder() {
		patchClass("asmble.compile.jvm.SyntheticFuncBuilder", cn -> {
			// Get the buildIndirectBootstrap method.
			final MethodNode method = cn.methods.stream().filter(i -> i.name.equals("buildIndirectBootstrap")).findAny().get();

			// Clear all its instructions and replace them with a call to
			// SyntheticFuncBuilderPatcher.buildIndirectBootstrap.
			final String descriptor = Type.getMethodDescriptor(Type.getType(MethodNode.class), Type.getType(ClsContext.class), Type.getType(String.class));
			final InsnList il = method.instructions;
			il.clear();
			il.add(new VarInsnNode(Opcodes.ALOAD, 1));
			il.add(new VarInsnNode(Opcodes.ALOAD, 2));
			il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(SyntheticFuncBuilderPatcher.class), "buildIndirectBootstrap", descriptor, false));
			il.add(new InsnNode(Opcodes.ARETURN));
		});
	}

	/**
	 * Patches the {@link asmble.compile.jvm.RuntimeHelpers} class to make the
	 * class and its {@link
	 * asmble.compile.jvm.RuntimeHelpers#bootstrapIndirect} method public.
	 */
	private static void patchRuntimeHelpers() {
		patchClass("asmble.compile.jvm.RuntimeHelpers", cn -> {
			// Change the class’s access modifiers.
			cn.access = makePublic(cn.access);

			// Change the method’s access modifiers.
			cn.methods.stream().filter(i -> i.name.equals("bootstrapIndirect")).forEach(i -> i.access = makePublic(i.access));
		});
	}

	/**
	 * Given a set of access flags, modifies the visibility flags to be public
	 * while not changing any other flags.
	 *
	 * @param access The original flags.
	 * @return The new flags.
	 */
	private static int makePublic(final int access) {
		return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
	}

	/**
	 * Reads the bytecode of an existing class, patches it, and then loads it
	 * into the current class loader.
	 *
	 * @param className The name of the class, in dot-separated form.
	 * @param patch The patch to apply.
	 */
	private static void patchClass(final String className, final Consumer<? super ClassNode> patch) {
		// Convert the name to a resource filename.
		final String fileName = "/" + className.replace('.', '/') + ".class";

		// Load the class file into an ASM ClassNode.
		final ClassNode cn;
		try(InputStream ins = SyntheticFuncBuilderPatcher.class.getResourceAsStream(fileName)) {
			if(ins == null) {
				throw new RuntimeException("Failed to read " + fileName);
			}
			final ClassReader cr = new ClassReader(ins);
			cn = new ClassNode(Opcodes.ASM9);
			cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch(final IOException exp) {
			throw new RuntimeException("Failed to read " + fileName, exp);
		}

		// Apply the patch.
		patch.accept(cn);

		// Render it to bytecode.
		final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
		cn.accept(cw);
		final byte[] bytecode = cw.toByteArray();

		// In a horrid hack, reflectively force the current class loader to
		// define the class using the modified bytecode.
		try {
			final Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class, int.class);
			defineClass.setAccessible(true);
			defineClass.invoke(SyntheticFuncBuilderPatcher.class.getClassLoader(), className, bytecode, 0, bytecode.length);
		} catch(final ReflectiveOperationException exp) {
			OCWasm.getLogger().error("Failed to reflectively invoke ClassLoader.defineClass for patched " + className, exp);
			throw new RuntimeException(exp);
		}
	}

	private SyntheticFuncBuilderPatcher() {
	}
}
