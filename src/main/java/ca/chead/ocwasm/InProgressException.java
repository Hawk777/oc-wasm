package ca.chead.ocwasm;

/**
 * Thrown if a method call has started but not yet finished.
 *
 * This may or may not be considered an error depending on the situation.
 */
public final class InProgressException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code InProgressException}.
	 */
	public InProgressException() {
		super("Operation in progress");
	}
}
