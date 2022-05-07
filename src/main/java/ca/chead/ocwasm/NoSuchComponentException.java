package ca.chead.ocwasm;

/**
 * Thrown if a component address does not exist or is inaccessible.
 */
public final class NoSuchComponentException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code NoSuchComponentException}.
	 */
	public NoSuchComponentException() {
		super(ErrorCode.NO_SUCH_COMPONENT, null);
	}
}
