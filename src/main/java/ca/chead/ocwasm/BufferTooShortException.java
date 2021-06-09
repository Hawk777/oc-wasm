package ca.chead.ocwasm;

/**
 * Thrown if a buffer provided to a syscall is too short to hold the data to be
 * returned.
 */
public final class BufferTooShortException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code BufferTooShortException}.
	 */
	public BufferTooShortException() {
		super(ErrorCode.BUFFER_TOO_SHORT);
	}
}
