package ca.chead.ocwasm;

/**
 * Thrown out of a Wasm module instance due to the module instance’s own
 * actions (rather than a failure in OC-Wasm or OpenComputers).
 */
public class ExecutionException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code ExecutionException}.
	 *
	 * @param message The explanatory message.
	 */
	public ExecutionException(final String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code ExecutionException}.
	 *
	 * @param message The explanatory message.
	 * @param cause The underlying cause of this exception.
	 */
	public ExecutionException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
