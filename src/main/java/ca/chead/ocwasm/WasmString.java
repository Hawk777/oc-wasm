package ca.chead.ocwasm;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * Handles moving strings between Wasm and Java.
 */
public final class WasmString {
	/**
	 * Copies a string from Java to Wasm.
	 *
	 * @param memory The linear memory.
	 * @param buffer The buffer to write into, or null to return the required
	 * buffer length without writing anything.
	 * @param length The length of {@code buffer}.
	 * @param string The string to copy.
	 * @return The length of the string written to {@code buffer}, on success;
	 * or the required buffer length, if {@code buffer} is null.
	 * @throws MemoryFaultException If the memory area is invalid.
	 * @throws BufferTooShortException If the encoded form of the string is
	 * longer than {@code length}.
	 */
	public static int toWasm(final ByteBuffer memory, final int buffer, final int length, final String string) throws MemoryFaultException, BufferTooShortException {
		if(buffer == 0) {
			return StandardCharsets.UTF_8.encode(string).limit();
		}
		if(buffer < 0 || length < 0 || buffer + length > memory.limit()) {
			throw new MemoryFaultException();
		}
		final ByteBuffer target = memory.duplicate();
		target.position(buffer).limit(buffer + length);
		final CharsetEncoder enc = StandardCharsets.UTF_8.newEncoder();
		CoderResult cr = enc.encode(CharBuffer.wrap(string), target, true);
		if(cr.isUnderflow()) {
			cr = enc.flush(target);
		}
		if(cr.isUnderflow()) {
			return target.position() - buffer;
		} else if(cr.isOverflow()) {
			throw new BufferTooShortException();
		} else {
			throw new RuntimeException("String is not UTF-8 encodable (this should be impossible)");
		}
	}

	/**
	 * Copies a string from Wasm to Java.
	 *
	 * @param memory The linear memory.
	 * @param pointer The address to read from.
	 * @param length The length of the string, or a negative number to scan for
	 * a NUL terminator.
	 * @return The string.
	 * @throws MemoryFaultException If the memory area is invalid, including if
	 * a scan for the NUL terminator runs off the end of the linear memory.
	 * @throws StringDecodeException If the data in the memory area is invalid
	 * UTF-8.
	 */
	public static String toJava(final ByteBuffer memory, final int pointer, final int length) throws MemoryFaultException, StringDecodeException {
		if(pointer <= 0) {
			throw new MemoryFaultException();
		}
		final ByteBuffer source = memory.asReadOnlyBuffer();
		final int actualLength;
		if(length >= 0) {
			actualLength = length;
		} else {
			source.position(pointer);
			try {
				while(source.get() != 0) {
					// Advancing source position
				}
			} catch(final BufferUnderflowException exp) {
				throw new MemoryFaultException();
			}
			actualLength = source.position() - pointer - 1;
		}
		if(pointer + actualLength > memory.limit()) {
			throw new MemoryFaultException();
		}
		source.position(pointer).limit(pointer + actualLength);
		try {
			return StandardCharsets.UTF_8.newDecoder().decode(source).toString();
		} catch(final CharacterCodingException exp) {
			throw new StringDecodeException();
		}
	}

	private WasmString() {
	}
}
