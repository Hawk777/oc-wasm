package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.ErrorCode;
import ca.chead.ocwasm.ExecuteBinaryException;
import ca.chead.ocwasm.MemoryUtils;
import ca.chead.ocwasm.WrappedException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * The syscalls available for import into a Wasm module in the {@code execute}
 * module.
 */
public final class Execute {
	/**
	 * The CPU.
	 */
	private final CPU cpu;

	/**
	 * The linear memory.
	 */
	private final ByteBuffer memory;

	/**
	 * The buffer holding the data accumulated so far, or {@code null} if the
	 * execution buffer has not yet been initialized.
	 */
	private ByteBuffer buffer;

	/**
	 * Constructs a new {@code Execute}.
	 *
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 */
	public Execute(final CPU cpu, final ByteBuffer memory) {
		super();
		this.cpu = Objects.requireNonNull(cpu);
		this.memory = Objects.requireNonNull(memory);
		buffer = null;
	}

	/**
	 * Loads an {@code Execute} that was previously saved.
	 *
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 * @param buffer The execution buffer from the snapshot, or an empty
	 * optional if the snapshot does not contain an execution buffer (because
	 * {@link #save} returned {@code null}).
	 */
	public Execute(final CPU cpu, final ByteBuffer memory, final Optional<byte[]> buffer) {
		super();
		this.cpu = Objects.requireNonNull(cpu);
		this.memory = Objects.requireNonNull(memory);
		if(buffer.isPresent()) {
			this.buffer = ByteBuffer.allocate(cpu.getInstalledRAM());
			this.buffer.put(buffer.get());
		} else {
			this.buffer = null;
		}
	}

	/**
	 * Saves the {@code Execute} into a byte array to store in a snapshot.
	 *
	 * @return The byte array to store, or an empty optional to not store
	 * anything.
	 */
	public Optional<byte[]> save() {
		if(buffer == null || buffer.position() == 0) {
			return Optional.empty();
		} else {
			final ByteBuffer dup = buffer.asReadOnlyBuffer();
			dup.flip();
			final byte[] ret = new byte[dup.remaining()];
			dup.get(ret);
			return Optional.of(ret);
		}
	}

	/**
	 * Clears the execution buffer.
	 *
	 * At the start of a program’s execution, the execution buffer is empty, so
	 * loading can commence without invoking this syscall first. However, if
	 * the program starts loading a binary then needs to abort, this syscall
	 * can be used to discard the already-loaded portion.
	 */
	@Syscall
	public void clear() {
		if(buffer != null) {
			buffer.position(0);
		}
	}

	/**
	 * Writes data to the execution buffer.
	 *
	 * @param pointer A pointer to the bytes to add to the buffer.
	 * @param length The number of bytes to add to the buffer.
	 * @return 0 on success, {@link ErrorCode#OTHER} if this call would make
	 * the contents of the buffer larger than the computer’s installed RAM, or
	 * {@link ErrorCode#MEMORY_FAULT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int add(final int pointer, final int length) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			// Get the source region.
			final ByteBuffer src = MemoryUtils.region(memory, pointer, length);

			// If the buffer does not exist, or if it is empty and is the wrong
			// size compared to the computer’s installed RAM, create a new
			// buffer.
			final int ramSize = cpu.getInstalledRAM();
			if(buffer == null || (buffer.position() == 0 && buffer.limit() != ramSize)) {
				buffer = ByteBuffer.allocate(ramSize);
			}

			// Check for space.
			if(buffer.remaining() < src.remaining()) {
				return ErrorCode.OTHER.asNegative();
			}

			// Write into buffer.
			buffer.put(src);
			return 0;
		});
	}

	/**
	 * Executes the Wasm binary contained in the execution buffer.
	 * <p>
	 * This syscall never returns.
	 * <p>
	 * If a component call is pending and has not yet been performed, or if a
	 * component call has been performed and its return value has not yet been
	 * fetched, it is cancelled as if by {@link Component#invokeCancel}. Then,
	 * all {@link ca.chead.ocwasm.DescriptorTable descriptors} are closed
	 * before execution of the new binary begins.
	 *
	 * @throws ExecuteBinaryException Always.
	 */
	@Syscall
	public void execute() throws ExecuteBinaryException {
		final byte[] binary;
		if(buffer == null) {
			binary = new byte[0];
		} else {
			buffer.flip();
			binary = new byte[buffer.remaining()];
			buffer.get(binary);
		}
		throw new ExecuteBinaryException(binary);
	}
}
