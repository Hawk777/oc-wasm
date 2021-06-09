package ca.chead.ocwasm;

/**
 * Thrown if a method call was made with unacceptable parameters.
 */
public final class BadParametersException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code BadParametersException}.
	 */
	public BadParametersException() {
		super(ErrorCode.BAD_PARAMETERS);
	}
}
