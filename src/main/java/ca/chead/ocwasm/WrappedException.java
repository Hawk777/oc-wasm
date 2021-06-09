package ca.chead.ocwasm;

/**
 * A wrapper that encapsulates {@link RuntimeException} objects, allowing them
 * to pass through a Wasm module instance while being distinguishable from
 * exceptions originating within the module instance itself.
 *
 * We want to distinguish between exceptions originating within the Wasm module
 * instance itself and (non-{@link SyscallErrorException}) exceptions thrown
 * within syscalls. The former should result in clean error terminations of the
 * computer, while the latter should be rethrown out of {@link CPU#runThreaded}
 * because they represent failures of OpenComputers or OC-Wasm itself. To
 * distinguish, all syscalls wrap any exceptions thrown in their implementation
 * in an instance of this class to throw it through Wasm code. On the other
 * end, the exception is unwrapped and rethrown by {@link ExceptionTranslator}.
 */
public final class WrappedException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code WrappedException}.
	 *
	 * @param cause The exception to wrap.
	 */
	public WrappedException(final RuntimeException cause) {
		super(cause);
	}
}
