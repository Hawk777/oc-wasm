package ca.chead.ocwasm;

import li.cil.oc.api.machine.Value;

/**
 * A reference to a {@link ReferencedValue}.
 *
 * An instance of this class holds a reference (in the reference-counting
 * sense) to an opaque value. Closing a {@code ValueReference} releases that
 * reference. Cloning a {@code ValueReference} creates a new {@code
 * ValueReference} that refers to the same opaque value, with the original and
 * the clone each holding a reference (in the reference-counting sense).
 */
public final class ValueReference implements AutoCloseable, Cloneable {
	/**
	 * The referenced value.
	 */
	private final ReferencedValue value;

	/**
	 * Constructs a new {@code ValueReference}.
	 *
	 * The constructor increments the reference count on the referenced value.
	 *
	 * @param value The referenced value.
	 */
	public ValueReference(final ReferencedValue value) {
		super();
		this.value = value;
		value.ref();
	}

	/**
	 * Returns the referenced value.
	 *
	 * @return The value.
	 */
	public Value get() {
		return value.value;
	}

	@Override
	public boolean equals(final Object other) {
		return (other instanceof ValueReference) && (((ValueReference) other).get() == get());
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(get());
	}

	@Override
	public void close() {
		value.unref();
	}

	@Override
	public ValueReference clone() {
		final ValueReference ret;
		try {
			ret = (ValueReference) super.clone();
		} catch(final CloneNotSupportedException exp) {
			// Yes it is!
			throw new RuntimeException(exp);
		}
		ret.value.ref();
		return ret;
	}
}
