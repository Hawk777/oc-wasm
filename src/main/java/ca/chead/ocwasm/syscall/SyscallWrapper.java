package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.SyscallErrorException;
import ca.chead.ocwasm.WrappedException;

/**
 * Provides a wrapper around a syscall’s body that handles exceptions properly.
 */
public final class SyscallWrapper {
	/**
	 * A wrappable syscall.
	 */
	@FunctionalInterface
	public interface Wrappable {
		/**
		 * Does the work.
		 *
		 * @return The syscall return value.
		 * @throws SyscallErrorException If an error occurred which represents
		 * a failure of user code and should be reported via a return value.
		 */
		int run() throws SyscallErrorException;
	}

	/**
	 * Wraps a syscall’s body.
	 *
	 * @param f The code to run.
	 * @return The return value of {@code f}, or a negative error code if
	 * {@code f} threw {@link SyscallErrorException}.
	 * @throws WrappedException If {@code f} threw an exception other than
	 * {@link SyscallErrorException}.
	 */
	public static int wrap(final Wrappable f) throws WrappedException {
		try {
			return f.run();
		} catch(final SyscallErrorException exp) {
			return exp.errorCode().asNegative();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	private SyscallWrapper() {
	}
}
