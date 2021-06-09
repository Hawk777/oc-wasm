package ca.chead.ocwasm;

/**
 * Thrown if a descriptor provided to a call does not identify an existing
 * opaque value.
 */
public final class BadDescriptorException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code BadDescriptorException}.
	 */
	public BadDescriptorException() {
		super(ErrorCode.BAD_DESCRIPTOR);
	}
}
