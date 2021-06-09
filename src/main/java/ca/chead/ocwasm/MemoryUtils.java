package ca.chead.ocwasm;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * Provides helper methods for working with linear memory.
 */
public final class MemoryUtils {
	/**
	 * Writes an {@code i32} into the module’s linear memory.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address at which to write.
	 * @param value The value to write.
	 * @throws MemoryFaultException If the memory area is invalid.
	 */
	public static void writeI32(final ByteBuffer memory, final int pointer, final int value) throws MemoryFaultException {
		if(pointer == 0) {
			throw new MemoryFaultException();
		}
		try {
			memory.putInt(pointer, value);
		} catch(final IndexOutOfBoundsException exp) {
			throw new MemoryFaultException();
		}
	}

	/**
	 * Writes an {@code i32} into the module’s linear memory, or does nothing
	 * on a null pointer.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address at which to write, or null to skip the write.
	 * @param value The value to write.
	 * @throws MemoryFaultException If the memory area is invalid.
	 */
	public static void writeOptionalI32(final ByteBuffer memory, final int pointer, final int value) throws MemoryFaultException {
		if(pointer != 0) {
			writeI32(memory, pointer, value);
		}
	}

	/**
	 * Writes a block of bytes into a module’s linear memory, or does nothing
	 * on a null pointer.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address at which to write, or null to skip the write.
	 * @param length The length of the buffer.
	 * @param bytes The bytes to write.
	 * @throws MemoryFaultException If the memory area is invalid.
	 * @throws BufferTooShortException If {@code length} is less than the size
	 * of {@code value}.
	 */
	public static void writeOptionalBytes(final ByteBuffer memory, final int pointer, final int length, final byte[] bytes) throws MemoryFaultException, BufferTooShortException {
		if(pointer == 0) {
			return;
		}
		final ByteBuffer dup = region(memory, pointer, length);
		try {
			dup.put(bytes);
		} catch(final BufferOverflowException exp) {
			throw new BufferTooShortException();
		}
	}

	/**
	 * Obtains a subslice of a linear memory.
	 *
	 * @param memory The linear memory.
	 * @param pointer The pointer.
	 * @param length The length, which is ignored if {@code pointer} is zero.
	 * @return A {@code ByteBuffer} which is backed by {@code memory}, with its
	 * position set to {@code pointer}, and with its limit set such that the
	 * length is equal to {@code length} (or zero if {@code pointer} is zero).
	 * @throws MemoryFaultException If the memory area is invalid.
	 */
	public static ByteBuffer region(final ByteBuffer memory, final int pointer, final int length) throws MemoryFaultException {
		if(pointer < 0 || length < 0 || pointer + length > memory.limit()) {
			throw new MemoryFaultException();
		}
		final ByteBuffer dup = memory.duplicate();
		if(pointer != 0) {
			dup.position(pointer);
			dup.limit(pointer + length);
		} else {
			dup.position(pointer);
			dup.limit(pointer);
		}
		return dup;
	}

	private MemoryUtils() {
	}
}
