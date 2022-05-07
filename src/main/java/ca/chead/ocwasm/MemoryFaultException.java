package ca.chead.ocwasm;

/**
 * Thrown if a memory area specified in a call is invalid.
 */
public final class MemoryFaultException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code MemoryFaultException}.
	 */
	public MemoryFaultException() {
		super(ErrorCode.MEMORY_FAULT, null);
	}
}
