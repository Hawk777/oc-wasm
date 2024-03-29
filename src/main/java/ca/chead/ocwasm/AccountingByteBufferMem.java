package ca.chead.ocwasm;

import asmble.compile.jvm.ByteBufferMem;
import asmble.compile.jvm.Func;
import asmble.compile.jvm.FuncContext;
import asmble.compile.jvm.TypeRef;
import java.nio.ByteBuffer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * An implementation of {@link ByteBufferMem} that generates calls to {@link
 * #growMemory}, which checks memory grow requests against the module’s current
 * free memory.
 */
public final class AccountingByteBufferMem extends ByteBufferMem {
	/**
	 * The one and only instance of this class.
	 */
	public static final AccountingByteBufferMem INSTANCE = new AccountingByteBufferMem();

	@Override
	public Func growMemory(final FuncContext context, final Func func) {
		// The base class implements memory.grow as a call to a synthetic
		// function in the generated class. We don’t need to do that because,
		// unlike Asmble, we don’t have to generate a self-contained class with
		// no external dependencies. We can just generate a call to an ordinary
		// Java function, in our case, WasmArchitecture.growMemory.
		final TypeRef intType = new TypeRef(Type.getType(int.class));
		final TypeRef byteBufferType = new TypeRef(Type.getType(ByteBuffer.class));
		return func
			.popExpecting(intType, func.getCurrentBlock())
			.popExpecting(byteBufferType, func.getCurrentBlock())
			.addInsns(
				// Stack: ByteBuffer mem, int deltaPages
				new VarInsnNode(Opcodes.ALOAD, 0),
				// Stack: ByteBuffer mem, int deltaPages, ModuleBase this
				new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(AccountingByteBufferMem.class), "growMemoryImpl", Type.getMethodDescriptor(Type.INT_TYPE, Type.getType(ByteBuffer.class), Type.INT_TYPE, Type.getType(ModuleBase.class)), false)
				// Stack: int
			).push(intType);
	}

	/**
	 * Handles a request to grow the module instance’s linear memory.
	 *
	 * This method is not meant to be called by Java code; rather, it is called
	 * from the generated JVM bytecode in order to implement a grow-memory
	 * request.
	 *
	 * @param memory The linear memory.
	 * @param deltaPages The requested number of pages to grow by.
	 * @param instance The module instance.
	 * @return The old size, in pages, of the memory, on success, or −1 on
	 * failure.
	 */
	public static int growMemoryImpl(final ByteBuffer memory, final int deltaPages, final ModuleBase instance) {
		// Negative numbers are illegal.
		if(deltaPages < 0) {
			return -1;
		}

		// If the new size would be larger than the maximum memory size, fail.
		// Calculate as long to avoid possible overflow.
		final long deltaBytes = deltaPages * (long) OCWasm.PAGE_SIZE;
		final long newLimitBytes = memory.limit() + deltaBytes;
		if(newLimitBytes > memory.capacity()) {
			return -1;
		}

		// If the new size would leave us with negative free memory for the
		// stack, fail.
		final long deltaWords = deltaBytes / ModuleBase.FREE_MEMORY_UNIT;
		if(deltaWords > instance.freeMemory) {
			return -1;
		}

		// OK.
		final int oldLimitPages = memory.limit() / OCWasm.PAGE_SIZE;
		memory.limit((int) newLimitBytes);
		instance.freeMemory -= (int) deltaWords;
		return oldLimitPages;
	}

	private AccountingByteBufferMem() {
		super(true);
	}
}
