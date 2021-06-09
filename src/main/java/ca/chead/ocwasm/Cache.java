package ca.chead.ocwasm;

import java.lang.ref.SoftReference;
import java.lang.ref.ReferenceQueue;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A memory-sensitive cache that holds its <em>values</em> (but not its keys)
 * by soft reference.
 *
 * This map does not permit {@code null} keys or values.
 *
 * @param <K> The type of the keys, which are held strongly.
 * @param <V> The type of the values, which are held softly.
 */
public final class Cache<K, V> extends AbstractMap<K, V> {
	/**
	 * A value in the underlying {@link #data} map.
	 *
	 * @param <K> The type of the key, which is held strongly.
	 * @param <V> The type of the value, which is held softly.
	 */
	private static final class Value<K, V> extends SoftReference<V> {
		/**
		 * The key.
		 */
		public final K key;

		/**
		 * Constructs a new {@code Entry}.
		 *
		 * @param key The key.
		 * @param value The value.
		 * @param referenceQueue The reference queue on which to register the
		 * value.
		 */
		Value(final K key, final V value, final ReferenceQueue<V> referenceQueue) {
			super(value, referenceQueue);
			this.key = key;
		}
	}

	/**
	 * An iterator over the map’s entries.
	 *
	 * @param <K> The type of the keys.
	 * @param <V> The type of the values.
	 */
	private static final class EntrySetIterator<K, V> implements Iterator<Map.Entry<K, V>> {
		/**
		 * The iterator over the underlying map.
		 */
		private final Iterator<Map.Entry<K, Value<K, V>>> underlying;

		/**
		 * The reference queue on which to register values, should new ones be
		 * loaded into the map.
		 */
		private final ReferenceQueue<V> referenceQueue;

		/**
		 * Whether it is legal to call {@link #remove} at this moment.
		 */
		private boolean removable;

		/**
		 * The underlying map entry to return next, or {@code null} if there is
		 * no next item.
		 */
		private Map.Entry<K, Value<K, V>> nextUnderlying;

		/**
		 * A strong reference to the next value to return, or {@code null} if
		 * there is no next item.
		 */
		private V nextValue;

		/**
		 * Constructs a new {@code EntrySetIterator}.
		 *
		 * @param underlying The underlying iterator.
		 * @param referenceQueue The reference queue on which to register
		 * values, should new ones be loaded into the map.
		 */
		EntrySetIterator(final Iterator<Map.Entry<K, Value<K, V>>> underlying, final ReferenceQueue<V> referenceQueue) {
			super();
			this.underlying = underlying;
			this.referenceQueue = referenceQueue;
			removable = false;
			nextUnderlying = null;
			nextValue = null;
		}

		/**
		 * Populates {@link #nextUnderlying} and {@link #nextValue} from {@link
		 * #underlying}.
		 */
		private void findNext() {
			for(;;) {
				if(!underlying.hasNext()) {
					nextUnderlying = null;
					nextValue = null;
					break;
				}
				final Map.Entry<K, Value<K, V>> e = underlying.next();
				final V v = e.getValue().get();
				if(v != null) {
					nextUnderlying = e;
					nextValue = v;
					break;
				}
			}
		}

		@Override
		public boolean hasNext() {
			// Strictly speaking, this is wrong: according to the specification
			// of Iterator, you ought to be able to call next(), then
			// hasNext(), then remove(), and that should remove the first
			// element (the one returned by next()), ignoring the intervening
			// call to remove(). Unfortunately it is impossible to implement a
			// filtering iterator over an underlying iterator while maintaining
			// that behaviour, as hasNext() needs to advance the underlying
			// iterator to check whether there are any more values that pass
			// the filter, at which point it is no longer possible to call
			// remove() on the underlying iterator to remove the old value.
			if(nextUnderlying == null) {
				findNext();
				removable = false;
			}
			return nextUnderlying != null;
		}

		@Override
		public Map.Entry<K, V> next() {
			if(nextUnderlying == null) {
				findNext();
				if(nextUnderlying == null) {
					throw new NoSuchElementException();
				}
			}
			final Map.Entry<K, Value<K, V>> thisEntry = nextUnderlying;
			final Map.Entry<K, V> ret = new AbstractMap.SimpleEntry<K, V>(thisEntry.getKey(), nextValue) {
				private static final long serialVersionUID = 1;

				@Override
				public V setValue(final V value) {
					final V old = super.setValue(value);
					thisEntry.setValue(new Value<K, V>(thisEntry.getKey(), value, referenceQueue));
					return old;
				}
			};
			removable = true;
			return ret;
		}

		@Override
		public void remove() {
			if(!removable) {
				throw new IllegalStateException();
			}
			underlying.remove();
			removable = false;
		}
	}

	/**
	 * An entry set that wraps the map.
	 */
	private final class EntrySet extends AbstractSet<Map.Entry<K, V>> {
		/**
		 * Constructs a new {@code EntrySet}.
		 */
		EntrySet() {
			super();
		}

		@Override
		public Iterator<Map.Entry<K, V>> iterator() {
			return new EntrySetIterator<K, V>(data.entrySet().iterator(), referenceQueue);
		}

		@Override
		public int size() {
			return data.size();
		}

		@Override
		public boolean contains(final Object o) {
			Objects.requireNonNull(o);
			if(!(o instanceof Map.Entry)) {
				return false;
			}
			final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
			final Value<K, V> candidate = data.get(e.getKey());
			if(candidate == null) {
				return false;
			}
			return e.getValue().equals(candidate.get());
		}

		@Override
		public boolean remove(final Object o) {
			Objects.requireNonNull(o);
			if(!(o instanceof Map.Entry)) {
				return false;
			}
			final Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
			return Cache.this.remove(e.getKey(), e.getValue());
		}

		@Override
		public void clear() {
			data.clear();
		}
	}

	/**
	 * The map holding the cache data.
	 */
	private final HashMap<K, Value<K, V>> data;

	/**
	 * The reference queue that receives {@link Entry} objects as they are
	 * garbage collected.
	 */
	private final ReferenceQueue<V> referenceQueue;

	/**
	 * The {@link EntrySet} view over this map.
	 */
	private final EntrySet entrySet;

	/**
	 * Constructs a new, empty {@code Cache}.
	 */
	public Cache() {
		super();
		data = new HashMap<K, Value<K, V>>();
		referenceQueue = new ReferenceQueue<V>();
		entrySet = new EntrySet();
	}

	/**
	 * Polls the reference queue.
	 *
	 * @return The next value on the reference queue, or {@code null} if there
	 * aren’t any.
	 */
	@SuppressWarnings("unchecked")
	private Value<K, V> pollReferenceQueue() {
		// referenceQueue only ever has values registered on it by being passed
		// into the Value constructor, and a given Cache only ever speaks of
		// Values with the same <K, V> as itself. Therefore all References in
		// referenceQueue are of type Value<K, V>.
		return (Value<K, V>) referenceQueue.poll();
	}

	/**
	 * Removes all entries that have been garbage collected.
	 */
	private void clean() {
		for(;;) {
			final Value<K, V> value = pollReferenceQueue();
			if(value == null) {
				break;
			}
			data.remove(value.key, value);
		}
	}

	@Override
	public boolean containsKey(final Object key) {
		return data.containsKey(Objects.requireNonNull(key));
	}

	@Override
	public V get(final Object key) {
		final Value<K, V> v = data.get(Objects.requireNonNull(key));
		return v == null ? null : v.get();
	}

	@Override
	public V put(final K key, final V value) {
		Objects.requireNonNull(key);
		Objects.requireNonNull(value);
		clean();
		final Value<K, V> old = data.put(key, new Value<K, V>(key, value, referenceQueue));
		return old == null ? null : old.get();
	}

	@Override
	public V remove(final Object key) {
		final Value<K, V> old = data.remove(Objects.requireNonNull(key));
		return old == null ? null : old.get();
	}

	@Override
	public boolean remove(final Object key, final Object value) {
		final Value<K, V> existingValue = data.get(Objects.requireNonNull(key));
		if(existingValue == null) {
			return false;
		}
		final V v = existingValue.get();
		if(v == null) {
			return false;
		}
		if(!v.equals(Objects.requireNonNull(value))) {
			return false;
		}
		remove(key);
		return true;
	}

	@Override
	public Set<Map.Entry<K, V>> entrySet() {
		return entrySet;
	}
}
