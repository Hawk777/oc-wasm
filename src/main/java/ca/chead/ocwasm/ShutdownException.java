package ca.chead.ocwasm;

/**
 * Thrown out of a Wasm module when it asks to shut down the computer.
 *
 * This is not really an error; an exception is just the only practical way to
 * implement a syscall that never returns to its caller.
 */
public final class ShutdownException extends ExecutionException {
	/**
	 * The possible shutdown modes.
	 */
	public enum Mode {
		/**
		 * Shut down and power off.
		 */
		POWER_OFF,

		/**
		 * Shut down and reboot.
		 */
		REBOOT,

		/**
		 * Halt and display an error message.
		 */
		ERROR;
	}

	private static final long serialVersionUID = 1;

	/**
	 * The shutdown mode.
	 */
	public final Mode mode;

	/**
	 * Constructs a new {@code ShutdownException}.
	 *
	 * @param message The exception message.
	 * @param mode The shutdown mode.
	 */
	public ShutdownException(final String message, final Mode mode) {
		super(message);
		this.mode = mode;
	}
}
