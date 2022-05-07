package ca.chead.ocwasm;

/**
 * Thrown if a method call fails for a reason that does not have a more
 * specific exception.
 */
public final class OtherException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code OtherException}.
	 *
	 * @param cause The cause of this exception.
	 */
	public OtherException(final Throwable cause) {
		super(ErrorCode.OTHER, cause);
	}
}
