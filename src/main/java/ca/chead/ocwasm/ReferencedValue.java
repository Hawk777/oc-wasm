package ca.chead.ocwasm;

import java.util.Objects;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.Value;

/**
 * An opaque value accompanied by a reference count.
 */
public final class ReferencedValue {
	/**
	 * The opaque value.
	 */
	public final Value value;

	/**
	 * The number of descriptors that point at this value.
	 */
	private int references;

	/**
	 * Constructs a new Entry with zero references.
	 *
	 * @param value The opaque value.
	 */
	ReferencedValue(final Value value) {
		super();
		this.value = Objects.requireNonNull(value);
		references = 0;
	}

	/**
	 * Increments the reference count of this entry.
	 */
	public void ref() {
		++references;
	}

	/**
	 * Decrements the reference count of this entry and, if it reaches zero,
	 * disposes of the value.
	 *
	 * @param context The OpenComputers context.
	 */
	public void unref(final Context context) {
		--references;
		if(references == 0) {
			value.dispose(context);
		}
	}
}
