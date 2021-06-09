package ca.chead.ocwasm;

/**
 * Thrown if a method call fails for a reason that does not have a more
 * specific exception.
 */
public final class OtherException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code OtherException}.
	 */
	public OtherException() {
		super(ErrorCode.OTHER);
	}
}
