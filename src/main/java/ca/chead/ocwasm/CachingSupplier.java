package ca.chead.ocwasm;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A wrapper around an underlying {@link Supplier} that calls the underlying
 * supplier once, on the first request, then repeatedly returns the same value.
 *
 * @param <T> The type of object being supplied.
 */
public final class CachingSupplier<T> implements Supplier<T> {
	/**
	 * The underlying supplier.
	 *
	 * This is {@code null} once the value has been computed.
	 */
	private Supplier<T> underlying;

	/**
	 * The value.
	 *
	 * This is {@code null} until the value has been computed, and may continue
	 * to be {@code null} if the actual computed value is {@code null}.
	 */
	private T value;

	/**
	 * Constructs a new {@code CachingSupplier}.
	 *
	 * @param underlying The underlying supplier.
	 */
	public CachingSupplier(final Supplier<T> underlying) {
		super();
		this.underlying = Objects.requireNonNull(underlying);
		value = null;
	}

	@Override
	public T get() {
		if(underlying != null) {
			value = underlying.get();
			underlying = null;
		}
		return value;
	}
}
