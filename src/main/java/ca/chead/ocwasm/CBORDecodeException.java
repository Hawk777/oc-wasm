package ca.chead.ocwasm;

/**
 * Thrown if a CBOR data item provided to a call is not valid CBOR or encodes
 * an unsupported type or value.
 */
public final class CBORDecodeException extends SyscallErrorException {
	private static final long serialVersionUID = 1;

	/**
	 * Constructs a new {@code CBORDecodeException}.
	 */
	public CBORDecodeException() {
		super(ErrorCode.CBOR_DECODE, null);
	}
}
