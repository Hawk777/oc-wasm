package ca.chead.ocwasm;

/**
 * Thrown if a module requires a syscall that is not available.
 */
public final class LinkingException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code LinkingException}.
	 *
	 * @param message An explanatory message.
	 */
	public LinkingException(final String message) {
		super(message);
	}
}
