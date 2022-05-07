package ca.chead.ocwasm;

/**
 * The errors that a syscall can potentially return to the Wasm module
 * instance.
 *
 * Not all syscalls are able to return all possible error codes. Each call will
 * indicate which errors can be returned.
 *
 * Unless otherwise indicated on a specific API, error codes are returned as
 * small negative integers, where zero and/or positive values reflect
 * successful calls. An error code’s ordinal is converted to such a negative
 * integer by negating the ordinal and subtracting one; this is implemented by
 * {@link ErrorCode#asNegative}.
 *
 * The order of values in this enumeration is part of the user-visible ABI. New
 * values will only ever be added at the end of the enum, and existing values
 * will not be moved or deleted.
 *
 * Some error codes record their underlying Java exception class and message,
 * allowing the application to obtain more details about the error. Each error
 * code indicates whether this information is recorded or not.
 */
public enum ErrorCode {
	/**
	 * A memory area is invalid.
	 *
	 * Examples include:
	 * <ul>
	 * <li>a required pointer is null</li>
	 * <li>a pointer is negative</li>
	 * <li>a length is negative</li>
	 * <li>a pointed-to memory area runs past the current size of the module
	 * instance’s linear memory</li>
	 * </ul>
	 *
	 * No exception is recorded.
	 */
	MEMORY_FAULT,

	/**
	 * A CBOR data item is invalid CBOR or encodes an unsupported type or
	 * value.
	 *
	 * No exception is recorded.
	 */
	CBOR_DECODE,

	/**
	 * A string is invalid UTF-8.
	 *
	 * No exception is recorded.
	 */
	STRING_DECODE,

	/**
	 * A buffer provided for the syscall to write into is too short.
	 *
	 * No exception is recorded.
	 */
	BUFFER_TOO_SHORT,

	/**
	 * A component UUID refers to a component that does not exist or cannot be
	 * accessed.
	 *
	 * No exception is recorded.
	 */
	NO_SUCH_COMPONENT,

	/**
	 * A method invocation refers to a method that does not exist.
	 *
	 * No exception is recorded.
	 */
	NO_SUCH_METHOD,

	/**
	 * The parameters are incorrect in a way that does not have a more specific
	 * error code.
	 *
	 * An exception is recorded if this return value comes from a method call.
	 * If it comes from something other than a method call, no exception is
	 * recorded.
	 */
	BAD_PARAMETERS,

	/**
	 * A queue is full.
	 *
	 * No exception is recorded.
	 */
	QUEUE_FULL,

	/**
	 * A queue is empty.
	 *
	 * No exception is recorded.
	 */
	QUEUE_EMPTY,

	/**
	 * A descriptor is negative or not open.
	 *
	 * No exception is recorded.
	 */
	BAD_DESCRIPTOR,

	/**
	 * There are too many open descriptors.
	 *
	 * No exception is recorded.
	 */
	TOO_MANY_DESCRIPTORS,

	/**
	 * The operation failed for an otherwise unspecified reason.
	 *
	 * An exception is recorded if this return value comes from a method call.
	 * If it comes from something other than a method call, no exception is
	 * recorded.
	 */
	OTHER;

	/**
	 * Converts the error code into a small negative integer according to the
	 * ABI rules.
	 *
	 * @return The integer form of the error code.
	 */
	public int asNegative() {
		return -ordinal() - 1;
	}
}
