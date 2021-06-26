package ca.chead.ocwasm;

import asmble.ast.Node;
import asmble.compile.jvm.AsmToBinary;
import asmble.compile.jvm.AstToAsm;
import asmble.compile.jvm.ClsContext;
import asmble.compile.jvm.CompileErr;
import asmble.compile.jvm.FuncBuilder;
import asmble.compile.jvm.InsnReworker;
import asmble.compile.jvm.SyntheticFuncBuilder;
import asmble.io.BinaryToAst;
import asmble.io.IoErr;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalInt;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Compiles Wasm binaries into executable modules.
 */
public final class Compiler {
	/**
	 * The result of a {@link #compile} call.
	 */
	public static final class Result {
		/**
		 * The binary.
		 */
		public final byte[] binary;

		/**
		 * The maximum size of the linear memory as specified in the Wasm
		 * module.
		 */
		public final OptionalInt maxLinearMemory;

		/**
		 * The size of the mutable globals.
		 */
		public final int mutableGlobalsSize;

		/**
		 * The compiled JVM class.
		 */
		public final Class<? extends ModuleBase> clazz;

		/**
		 * The “fixed memory size” of the module instance.
		 *
		 * See {@link Postprocessor.Result#fixedMemorySize} for details of what
		 * is included here.
		 */
		private final int fixedMemorySize;

		/**
		 * Constructs a new {@code Result}.
		 *
		 * @param binary The Wasm binary.
		 * @param module The parsed Wasm module.
		 * @param clazz The compiled JVM class.
		 * @param ppResult The postprocessing result.
		 */
		public Result(final byte[] binary, final Node.Module module, final Class<? extends ModuleBase> clazz, final Postprocessor.Result ppResult) {
			super();
			this.binary = Objects.requireNonNull(binary);
			final Integer declaredMax = module.getMemories().get(0).getLimits().getMaximum();
			maxLinearMemory = declaredMax != null ? OptionalInt.of(declaredMax) : OptionalInt.empty();
			mutableGlobalsSize = module.getGlobals().stream().filter(i -> i.getType().getMutable()).mapToInt(i -> getValueBytes(i.getType().getContentType())).sum();
			this.clazz = Objects.requireNonNull(clazz);
			fixedMemorySize = ppResult.fixedMemorySize;
		}

		/**
		 * Calculates the memory size of the module.
		 *
		 * @param memory The linear memory.
		 * @return The size, in bytes, of the module.
		 */
		public int moduleSize(final ByteBuffer memory) {
			return memory.limit() + fixedMemorySize;
		}
	}

	/**
	 * The size of a single word.
	 */
	private static final int WORD_SIZE = 4;

	/**
	 * The name of the package to compile to.
	 */
	private static final String TARGET_PACKAGE = "ca.chead.ocwasm.compiled";

	/**
	 * The name of the class to compile to.
	 */
	private static final String TARGET_CLASS = "CompiledClass";

	/**
	 * The type of the class.
	 */
	private static final Type TARGET_TYPE = Type.getObjectType((TARGET_PACKAGE + "." + TARGET_CLASS).replace('.', '/'));

	/**
	 * A cache of previously compiled Wasm binaries, keyed by the binary image.
	 *
	 * All accesses to the cache must be made while holding the cache object’s
	 * own monitor. This is not done via {@link
	 * java.util.Collections#synchronizedMap} because the documentation of that
	 * method is unclear on whether it makes {@link #java.util.Map#putIfAbsent}
	 * atomic, and we require that operation to be atomic for efficiency.
	 */
	private static final Cache<HashableByteArray, Result> CACHE = new Cache<HashableByteArray, Result>();

	/**
	 * Compiles a Wasm binary.
	 *
	 * @param binary The Wasm binary.
	 * @return The parsed and compiled forms of the module.
	 * @throws BadWasmException If the module fails to parse or fails to
	 * compile to JVM bytecode altogether.
	 * @throws PostprocessException If the module compiles to JVM bytecode but
	 * the result cannot be postprocessed (for example, it does not implement
	 * the {@link ModuleBase} API).
	 */
	public static Result compile(final byte[] binary) throws BadWasmException, PostprocessException {
		// Check if it’s already in the cache and, if so, return it.
		synchronized(CACHE) {
			final Result cachedResult = CACHE.get(new HashableByteArray(binary));
			if(cachedResult != null) {
				return cachedResult;
			}
		}

		// Parse the binary.
		final Node.Module module;
		try {
			module = BinaryToAst.Companion.toModule(binary);
		} catch(final IoErr exp) {
			throw new BadWasmException(exp);
		}

		// Compile the Wasm module to JVM bytecode.
		final ClassNode classNode = new ClassNode(Opcodes.ASM6 /* Asmble depends on Asm 6 */);
		classNode.name = TARGET_TYPE.getInternalName();
		final ClsContext classContext = new ClsContext(
			TARGET_PACKAGE, // packageName
			TARGET_CLASS, // className
			module, // mod
			classNode, // cls
			AccountingByteBufferMem.INSTANCE, // mem
			null, // modName (default)
			InsnReworker.Companion, // reworker (default)
			new asmble.util.Logger.Print(asmble.util.Logger.Level.OFF), // logger (default)
			FuncBuilder.Companion, // funcBuilder (default)
			SyntheticFuncBuilder.Companion, // syntheticFuncBuilder (default)
			true, // checkTruncOverflow (default)
			3, // nonAdjacentMemAccessesRequiringLocalVar (default)
			true, // eagerFailLargeMemOffset (default)
			false, // preventMemIndexOverflow (default)
			true, // accurateNawnBits (default)
			true, // checkSignedDivIntegerOverflow (default)
			5000, // jumpTableChunkSize (default)
			false); // includeBinary (default)
		try {
			AstToAsm.Companion.fromModule(classContext);
		} catch(final CompileErr exp) {
			throw new BadWasmException(exp);
		}

		// Postprocess the JVM bytecode to make it OC-Wasm-compatible.
		final Postprocessor.Result ppResult = Postprocessor.postprocess(classNode, module, binary, classContext);

		// Load the class.
		final byte[] bytecode = AsmToBinary.Companion.fromClassNode(classNode, () -> new ClassWriter(ClassWriter.COMPUTE_FRAMES));
		final ClassLoader loader = new ClassLoader(Compiler.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(final String name) throws ClassNotFoundException {
				if(name.equals(TARGET_TYPE.getClassName())) {
					return defineClass(name, bytecode, 0, bytecode.length);
				} else {
					return super.findClass(name);
				}
			}
		};
		final Class<? extends ModuleBase> clazz;
		try {
			clazz = Class.forName(TARGET_TYPE.getClassName(), true, loader).asSubclass(ModuleBase.class);
		} catch(final ClassNotFoundException exp) {
			throw new RuntimeException("Impossible ClassNotFoundException, we just defined it", exp);
		}

		// Put it in the cache. If it ended up already there, because some
		// other thread compiled and identical binary at the same time we were
		// doing so, then use that result and throw away the one we just
		// finished compiling, in order to save space by sharing data.
		synchronized(CACHE) {
			final Result r = new Result(binary, module, clazz, ppResult);
			final Result old = CACHE.putIfAbsent(new HashableByteArray(binary), r);
			if(old == null) {
				return r;
			} else {
				return old;
			}
		}
	}

	/**
	 * Returns the size, in bytes, of all globals in a module.
	 *
	 * @param module The module.
	 * @return The size of the globals.
	 */
	public static int globalsSize(final Node.Module module) {
		return module.getGlobals().stream().mapToInt(i -> getValueBytes(i.getType().getContentType())).sum();
	}

	/**
	 * Returns the number of bytes needed to store a given Wasm value type.
	 *
	 * @param type The Wasm type.
	 * @return The size.
	 */
	private static int getValueBytes(final Node.Type.Value type) {
		if(type instanceof Node.Type.Value.I32) {
			return WORD_SIZE;
		} else if(type instanceof Node.Type.Value.I64) {
			return WORD_SIZE * 2;
		} else if(type instanceof Node.Type.Value.F32) {
			return WORD_SIZE;
		} else if(type instanceof Node.Type.Value.F64) {
			return WORD_SIZE * 2;
		} else {
			throw new IllegalArgumentException("Unknown Wasm value type " + type);
		}
	}

	private Compiler() {
	}
}
