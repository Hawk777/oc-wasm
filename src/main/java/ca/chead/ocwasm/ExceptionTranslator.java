package ca.chead.ocwasm;

import java.util.Objects;
import li.cil.oc.api.machine.ExecutionResult;

/**
 * Translates exceptions thrown out of a Wasm module instance into the proper
 * response.
 */
public final class ExceptionTranslator {
	/**
	 * Examines an exception, optionally logs it, and either rethrows it or
	 * translates it to an ExecutionResult.
	 *
	 * @param exp The exception to examine, which must have been thrown out of
	 * a Wasm module instance.
	 * @return The execution result to return.
	 * @throws RuntimeException If {@code exp} is a {@link WrappedException},
	 * then the wrapped {@link RuntimeException} is rethrown.
	 * @throws Error If {@code exp} is an {@link Error}, then it is rethrown
	 * directly.
	 */
	public static ExecutionResult translate(final Throwable exp) {
		Objects.requireNonNull(exp);

		if(exp instanceof ShutdownException) {
			// This isn’t an error, it’s just a normal shutdown request.
			switch(((ShutdownException) exp).mode) {
				case POWER_OFF: return new ExecutionResult.Shutdown(false);
				case REBOOT: return new ExecutionResult.Shutdown(true);
				case ERROR: return new ExecutionResult.Error(exp.getMessage());
				default: throw new RuntimeException("Unhandled value of ShutdownException.Mode: " + ((ShutdownException) exp).mode);
			}
		} else if(exp instanceof WrappedException) {
			// This is an error in an OpenComputers or OC-Wasm API that passed
			// through Wasm code. Log it and rethrow it, as it represents a bug
			// in OpenComputers or OC-Wasm, not in the user code.
			final RuntimeException cause = (RuntimeException) exp.getCause();
			OCWasm.getLogger().error("Exception thrown in OpenComputers API", cause);
			throw cause;
		} else if(exp instanceof Exception) {
			// This is an error in user code. Don’t log it or rethrow it, just
			// shut down with a clean error. If the exception comes with a
			// message, use that; otherwise, use the exception’s class name.
			if(exp.getMessage() != null) {
				return new ExecutionResult.Error(exp.getMessage());
			} else {
				return new ExecutionResult.Error(exp.getClass().getName());
			}
		} else if(exp instanceof Error) {
			// This is an internal JVM error. Rethrow it.
			throw (Error) exp;
		} else {
			// This should be impossible.
			OCWasm.getLogger().error("Throwable {} is neither Exception nor Error", exp);
			throw new RuntimeException("Throwable is neither Exception nor Error", exp);
		}
	}

	private ExceptionTranslator() {
	}
}
