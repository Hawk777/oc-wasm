package ca.chead.ocwasm;

/**
 * An error that occurs while postprocessing the JVM bytecode from of a Wasm
 * module.
 */
public final class PostprocessException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code PostprocessException}.
	 */
	public PostprocessException() {
		super();
	}

	/**
	 * Constructs a new {@code PostprocessException}.
	 *
	 * @param message A message explaining the problem.
	 */
	public PostprocessException(final String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code PostprocessException}.
	 *
	 * @param cause The underlying exception that caused this exception.
	 */
	public PostprocessException(final Throwable cause) {
		super(cause);
	}

	/**
	 * Constructs a new {@code PostprocessException}.
	 *
	 * @param message A message explaining the problem.
	 * @param cause The underlying exception that caused this exception.
	 */
	public PostprocessException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
