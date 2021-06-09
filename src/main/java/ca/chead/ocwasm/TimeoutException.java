package ca.chead.ocwasm;

/**
 * Thrown during execution of compiled Wasm code if it runs for longer than the
 * allowed timeslice.
 */
public final class TimeoutException extends ExecutionException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code Timeoutexception}.
	 */
	public TimeoutException() {
		super("Timeslice expired");
	}
}
