package ca.chead.ocwasm;

/**
 * Thrown if a string provided to a call is invalid UTF-8.
 */
public final class StringDecodeException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code StringDecodeException}.
	 */
	public StringDecodeException() {
		super(ErrorCode.STRING_DECODE);
	}
}
