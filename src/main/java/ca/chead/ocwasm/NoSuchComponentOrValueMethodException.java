package ca.chead.ocwasm;

/**
 * Thrown if a method name does not exist.
 */
public final class NoSuchComponentOrValueMethodException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code NoSuchComponentOrValueException}.
	 */
	public NoSuchComponentOrValueMethodException() {
		super(ErrorCode.NO_SUCH_METHOD);
	}
}
