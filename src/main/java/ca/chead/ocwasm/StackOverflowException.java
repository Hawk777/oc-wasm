package ca.chead.ocwasm;

/**
 * Thrown during execution of compiled Wasm code if it calls a function and
 * there is not enough free memory in the OpenComputers computer to hold that
 * function’s locals and operand stack.
 */
public final class StackOverflowException extends ExecutionException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code StackOverflowException}.
	 */
	public StackOverflowException() {
		super("Stack overflow");
	}
}
