package ca.chead.ocwasm;

/**
 * Thrown if a syscall fails such that the Wasm module instance is at fault,
 * and the situation should be reported via an {@link ErrorCode}.
 */
public class SyscallErrorException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * The error code.
	 */
	private final ErrorCode code;

	/**
	 * Constructs a new {@code SyscallErrorException}.
	 *
	 * @param code The error code.
	 * @param cause The cause of this exception.
	 */
	protected SyscallErrorException(final ErrorCode code, final Throwable cause) {
		super(code.name(), cause);
		this.code = code;
	}

	/**
	 * Returns the error code.
	 *
	 * @return The error code.
	 */
	public final ErrorCode errorCode() {
		return code;
	}
}
