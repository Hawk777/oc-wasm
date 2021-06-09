package ca.chead.ocwasm;

import java.util.Objects;

/**
 * Thrown out of a Wasm module when it asks to execute a new binary.
 *
 * This is not really an error; an exception is just the only practical way to
 * implement a syscall that never returns to its caller.
 */
public final class ExecuteBinaryException extends ExecutionException {
	private static final long serialVersionUID = -1;

	/**
	 * The binary to execute.
	 */
	public final byte[] binary;

	/**
	 * Constructs a new {@code ExecuteBinaryException}.
	 *
	 * @param binary The binary to execute.
	 */
	public ExecuteBinaryException(final byte[] binary) {
		super("Execute binary");
		this.binary = Objects.requireNonNull(binary);
	}
}
