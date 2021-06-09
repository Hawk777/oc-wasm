package ca.chead.ocwasm;

import java.util.Arrays;
import java.util.Objects;

/**
 * A wrapper around a {@code byte[]} that adds hashability and equality
 * checking.
 */
public final class HashableByteArray {
	/**
	 * The byte array.
	 *
	 * Clients must not modify the contents of the array.
	 */
	public final byte[] data;

	/**
	 * Constructs a new {@code HashableByteArray}.
	 *
	 * @param data The data.
	 */
	public HashableByteArray(final byte[] data) {
		super();
		this.data = Objects.requireNonNull(data);
	}

	@Override
	public boolean equals(final Object other) {
		if(other instanceof HashableByteArray) {
			return Arrays.equals(data, ((HashableByteArray) other).data);
		} else {
			return false;
		}
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(data);
	}
}
