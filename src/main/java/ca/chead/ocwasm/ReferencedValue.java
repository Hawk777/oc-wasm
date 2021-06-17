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
	 * The OpenComputers context, used for disposing the value.
	 */
	private final Context context;

	/**
	 * The number of descriptors that point at this value.
	 */
	private int references;

	/**
	 * Constructs a new Entry with zero references.
	 *
	 * @param value The opaque value.
	 * @param context The OpenComputers context, used for disposing the value.
	 */
	ReferencedValue(final Value value, final Context context) {
		super();
		this.value = Objects.requireNonNull(value);
		this.context = Objects.requireNonNull(context);
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
	 */
	public void unref() {
		--references;
		if(references == 0) {
			value.dispose(context);
		}
	}
}
