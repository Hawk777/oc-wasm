package ca.chead.ocwasm;

import asmble.compile.jvm.CompileErr;
import asmble.io.IoErr;

/**
 * Thrown during compilation if the Wasm binary is invalid.
 */
public final class BadWasmException extends Exception {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code BadWasmException}.
	 *
	 * @param cause The underlying cause.
	 */
	public BadWasmException(final IoErr cause) {
		super(cause.getMessage(), cause);
	}

	/**
	 * Constructs a new {@code BadWasmException}.
	 *
	 * @param cause The underlying cause.
	 */
	public BadWasmException(final CompileErr cause) {
		super(cause.getMessage(), cause);
	}
}
