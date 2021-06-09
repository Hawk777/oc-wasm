package ca.chead.ocwasm;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An {@link InputStream} backed by a {@link ByteBuffer}.
 */
public final class ByteBufferInputStream extends InputStream {
	/**
	 * The byte buffer.
	 */
	private final ByteBuffer buffer;

	/**
	 * Wraps a byte buffer in an input stream.
	 *
	 * The stream will read from the buffer’s position to its limit. The caller
	 * must not modify those values while the input stream exists.
	 *
	 * @param buffer The buffer to wrap.
	 * @return An input stream that reads from {@code buffer}.
	 */
	public static InputStream wrap(final ByteBuffer buffer) {
		if(buffer.hasArray()) {
			return new ByteArrayInputStream(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
		} else {
			return new ByteBufferInputStream(buffer);
		}
	}

	@Override
	public int available() {
		return buffer.remaining();
	}

	@Override
	public void mark(final int limit) {
		buffer.mark();
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public int read() {
		if(buffer.hasRemaining()) {
			return buffer.get() & ((1 << Byte.SIZE) - 1);
		} else {
			return -1;
		}
	}

	@Override
	public int read(final byte[] dest, final int offset, final int length) {
		if(length == 0) {
			return 0;
		} else if(!buffer.hasRemaining()) {
			return -1;
		} else {
			final int toCopy = Math.min(buffer.remaining(), length);
			buffer.get(dest, offset, toCopy);
			return toCopy;
		}
	}

	@Override
	public void reset() {
		buffer.reset();
	}

	@Override
	public long skip(final long n) {
		if(n <= 0) {
			return 0;
		} else {
			final int toSkip = (int) Math.min(n, buffer.remaining());
			buffer.position(buffer.position() + toSkip);
			return toSkip;
		}
	}

	private ByteBufferInputStream(final ByteBuffer buffer) {
		this.buffer = buffer;

		// Clear the mark.
		final int position = buffer.position();
		final int limit = buffer.limit();
		buffer.clear();
		buffer.position(position).limit(limit);
	}
}
