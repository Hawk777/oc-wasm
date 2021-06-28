package ca.chead.ocwasm;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * Provides helper methods for working with linear memory.
 */
public final class MemoryUtils {
	/**
	 * The number of bytes consumed by a binary UUID.
	 */
	public static final int UUID_BYTES = 2 * Long.BYTES;

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
	 * Reads a UUID from the module’s linear memory.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address from which to read.
	 * @return The UUID.
	 * @throws MemoryFaultException If the memory area is invalid.
	 */
	public static UUID readUUID(final ByteBuffer memory, final int pointer) throws MemoryFaultException {
		final ByteBuffer view = region(memory, pointer, UUID_BYTES);
		view.order(ByteOrder.BIG_ENDIAN);
		final long msw;
		final long lsw;
		try {
			msw = view.getLong();
			lsw = view.getLong();
		} catch(final IndexOutOfBoundsException exp) {
			throw new MemoryFaultException();
		}
		return new UUID(msw, lsw);
	}

	/**
	 * Writes a UUID to the module’s linear memory.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address at which to write.
	 * @param uuid The UUID to write.
	 * @throws MemoryFaultException If the memory area is invalid.
	 */
	public static void writeUUID(final ByteBuffer memory, final int pointer, final UUID uuid) throws MemoryFaultException {
		final ByteBuffer view = region(memory, pointer, UUID_BYTES);
		view.order(ByteOrder.BIG_ENDIAN);
		try {
			view.putLong(uuid.getMostSignificantBits());
			view.putLong(uuid.getLeastSignificantBits());
		} catch(final IndexOutOfBoundsException exp) {
			throw new MemoryFaultException();
		}
	}

	/**
	 * Obtains a subslice of a linear memory.
	 *
	 * @param memory The linear memory.
	 * @param pointer The pointer.
	 * @return A {@code ByteBuffer} which is backed by {@code memory}, with its
	 * position set to {@code pointer}, and with its limit set to the end of
	 * memory.
	 * @throws MemoryFaultException If {@code pointer} points outside valid
	 * memory.
	 */
	public static ByteBuffer region(final ByteBuffer memory, final int pointer) throws MemoryFaultException {
		if(pointer <= 0 || pointer > memory.limit()) {
			throw new MemoryFaultException();
		}
		final ByteBuffer dup = memory.duplicate();
		dup.position(pointer);
		return dup;
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
