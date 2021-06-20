package ca.chead.ocwasm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.Value;
import net.minecraft.nbt.NBTTagCompound;

/**
 * A table mapping between integer descriptors and opaque values, with the
 * ability to allocate new descriptors and close existing descriptors.
 */
public final class DescriptorTable {
	/**
	 * An allocator that can incrementally allocate new descriptors, then
	 * either commit or abort the allocation.
	 *
	 * An instance of this class stores a collection of descriptors that have
	 * been provisionally allocated. These are not visible in the main table
	 * yet. To abort the allocation, the allocator can simply be closed. If
	 * the containing operation succeeds, however, the allocator should be
	 * committed before closing, which updates the main table and cannot fail.
	 */
	public final class Allocator implements AutoCloseable {
		/**
		 * The parent allocator, or {@code null} if there isn’t one.
		 */
		private final Allocator parent;

		/**
		 * The descriptors added by this allocator.
		 */
		private final ArrayList<Integer> allocations;

		/**
		 * Constructs a new {@code Allocator}.
		 */
		public Allocator() {
			super();
			parent = null;
			allocations = new ArrayList<Integer>();
		}

		/**
		 * Constructs a new child {@code Allocator}.
		 *
		 * Unlike a normal allocator, a child allocator has a parent. When the
		 * child allocator’s {@link #commit} method is called, the allocated
		 * descriptors are pushed up into the parent allocator, which then gets
		 * to decide whether to commit or discard the allocations. If the child
		 * allocator is closed without being committed, the descriptors are
		 * unconditionally discarded.
		 *
		 * @param parent The parent allocator.
		 */
		public Allocator(final Allocator parent) {
			super();
			this.parent = Objects.requireNonNull(parent);
			allocations = new ArrayList<Integer>();
		}

		/**
		 * Allocates a descriptor for an opaque value.
		 *
		 * The caller must not close the descriptor returned by this method
		 * until after the allocator has been committed.
		 *
		 * @param value The value.
		 * @return The provisionally allocated descriptor value.
		 */
		public int add(final Value value) {
			final int descriptor = DescriptorTable.this.add(value);
			allocations.add(descriptor);
			return descriptor;
		}

		/**
		 * Returns the allocations that have been made using this allocator.
		 *
		 * Only the uncommitted allocations are returned.
		 *
		 * @return The uncommitted allocations.
		 */
		public List<Integer> getAllocatedDescriptors() {
			return Collections.unmodifiableList(allocations);
		}

		/**
		 * Commits all allocations to the main table.
		 *
		 * Once this is called, closing the allocator will no longer close the
		 * allocated descriptors; instead, they will be permanently added to
		 * the descriptor table (or, if this allocator has a parent, added to
		 * the parent’s provisional allocations).
		 */
		public void commit() {
			// If there’s a parent, push the allocations up to the parent.
			if(parent != null) {
				parent.allocations.addAll(allocations);
			}

			// Forget the allocations, so that close() won’t do anything.
			allocations.clear();
		}

		/**
		 * Creates a child allocator.
		 *
		 * @return The new child allocator.
		 */
		public Allocator createChild() {
			return new Allocator(this);
		}

		@Override
		public void close() {
			// Close all the descriptors we added.
			for(final int descriptor : allocations) {
				try {
					DescriptorTable.this.close(descriptor);
				} catch(final BadDescriptorException exp) {
					// This indicates that someone else closed the descriptor
					// while the Allocator still existed. This is a bug. If the
					// user of Allocator did it themselves, they shouldn’t
					// have, because every descriptor must only be closed once,
					// and an uncommitted Allocator is the one responsible for
					// closing its descriptors. If someone else did it, then
					// they were closing a descriptor they didn’t own (because
					// it was the Allocator and the Allocator’s user who own
					// the new descriptors the Allocator creates).
					//
					// This can’t be triggered by user code, because Allocator
					// objects shouldn’t exist across user code execution;
					// therefore, any occurrence of this is a bug in OC-Wasm.
					throw new RuntimeException(exp);
				}
			}
			allocations.clear();
		}
	}

	/**
	 * The maximum size of a descriptor table.
	 *
	 * This is not a hard size limit. There <em>can</em> be more descriptors in
	 * the table than this number. However, if the limit is exceeded, no new
	 * API calls can be started until the application closes some descriptors.
	 * Ideally what would happen is that an API call fails if it would create
	 * opaque values beyond this limit; however, as OpenComputers is not really
	 * set up to properly fail a call based on a quota of opaque values, nor is
	 * it possible to predict before making a call how many opaque values it
	 * might create, this is really the only practical option.
	 */
	private static final int MAX_SIZE = 4096;

	/**
	 * The NBT compound key where the descriptors are kept.
	 *
	 * This tag is an integer array. Each position in the array contains either
	 * −1 if the descriptor is closed, or value pool index of the value if the
	 * descriptor is open.
	 */
	private static final String NBT_DESCRIPTORS_KEY = "descriptors";

	/**
	 * The OpenComputers context.
	 */
	private final Context context;

	/**
	 * The objects.
	 */
	private final ArrayList<ReferencedValue> objects;

	/**
	 * The lowest descriptor number that is closed.
	 */
	private int firstEmpty;

	/**
	 * Constructs an empty {@code DescriptorTable}.
	 *
	 * @param context The OpenComputers context.
	 */
	public DescriptorTable(final Context context) {
		super();
		this.context = Objects.requireNonNull(context);
		objects = new ArrayList<ReferencedValue>();
		firstEmpty = 0;
	}

	/**
	 * Loads a {@code DescriptorTable} that was previously saved.
	 *
	 * @param context The OpenComputers context.
	 * @param valuePool The value pool.
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 */
	public DescriptorTable(final Context context, final ReferencedValue[] valuePool, final NBTTagCompound root) {
		super();
		this.context = Objects.requireNonNull(context);

		// Load the descriptors.
		{
			final int[] descriptorIndices = root.getIntArray(NBT_DESCRIPTORS_KEY);
			objects = new ArrayList<ReferencedValue>(descriptorIndices.length);
			for(final int i : descriptorIndices) {
				if(i == -1) {
					objects.add(null);
				} else {
					valuePool[i].ref();
					objects.add(valuePool[i]);
				}
			}
		}

		// Populate the next empty descriptor.
		firstEmpty = objects.indexOf(null);
		if(firstEmpty == -1) {
			firstEmpty = objects.size();
		}
	}

	/**
	 * Checks whether the descriptor table is larger than its maximum size.
	 *
	 * @return {@code true} if the table is overfull, or {@code false} if it is
	 * within its limit.
	 */
	public boolean overfull() {
		return objects.stream().filter(i -> i != null).count() > MAX_SIZE;
	}

	/**
	 * Gets a value.
	 *
	 * @param descriptor The descriptor to look up.
	 * @return A reference to the corresponding value.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated value.
	 */
	public ValueReference get(final int descriptor) throws BadDescriptorException {
		return new ValueReference(getEntry(descriptor));
	}

	/**
	 * Adds a new value to the descriptor table.
	 *
	 * @param value The value to add.
	 * @return The new descriptor.
	 */
	public int add(final Value value) {
		// Sanity check.
		Objects.requireNonNull(value);

		// Select the descriptor to use.
		final int descriptor = firstEmpty;

		// Get hold of the proper ReferencedValue object.
		final ReferencedValue entry;
		final ReferencedValue existingEntry = objects.stream().filter(i -> i != null && i.value == value).findAny().orElse(null);
		if(existingEntry != null) {
			// There’s already a ReferencedValue for this Value in the table.
			// Reuse it.
			entry = existingEntry;
		} else {
			// There’s no ReferencedValue for this Value in the table. Create a
			// new one.
			entry = new ReferencedValue(value, context);
		}

		// Ref the obtained value and add it to the table.
		entry.ref();
		if(objects.size() <= descriptor) {
			objects.add(entry);
		} else {
			objects.set(descriptor, entry);
		}

		// Update firstEmpty. The current descriptor was previously the first
		// empty. It is no longer empty. Therefore the new first empty
		// descriptor must be at a higher index, so there is no need to search
		// over the whole list, only those positions above the new descriptor.
		final int subListEmptyPos = objects.subList(descriptor + 1, objects.size()).indexOf(null);
		if(subListEmptyPos == -1) {
			firstEmpty = objects.size();
		} else {
			firstEmpty = descriptor + 1 + subListEmptyPos;
		}

		return descriptor;
	}

	/**
	 * Closes a descriptor.
	 *
	 * @param descriptor The descriptor to close.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated value.
	 */
	public void close(final int descriptor) throws BadDescriptorException {
		final ReferencedValue entry = getEntry(descriptor);
		objects.set(descriptor, null);
		entry.unref();
		firstEmpty = Math.min(firstEmpty, descriptor);
	}

	/**
	 * Gets an entry.
	 *
	 * @param descriptor The descriptor to look up.
	 * @return The corresponding entry.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated entry.
	 */
	private ReferencedValue getEntry(final int descriptor) throws BadDescriptorException {
		final ReferencedValue ret;
		try {
			ret = objects.get(descriptor);
		} catch(final IndexOutOfBoundsException exp) {
			throw new BadDescriptorException();
		}
		if(ret == null) {
			throw new BadDescriptorException();
		}
		return ret;
	}

	/**
	 * Saves the {@code DescriptorTable} into an NBT structure.
	 *
	 * @param valuePool The pool that holds opaque values.
	 * @return The created NBT tag.
	 */
	public NBTTagCompound save(final ValuePool valuePool) {
		final NBTTagCompound root = new NBTTagCompound();

		// Shrink the descriptor table by removing trailing nulls.
		{
			int lastNonNull = -1;
			for(int i = 0; i != objects.size(); ++i) {
				if(objects.get(i) != null) {
					lastNonNull = i;
				}
			}
			if(lastNonNull == -1) {
				objects.clear();
			} else if(lastNonNull != objects.size() - 1) {
				objects.subList(lastNonNull + 1, objects.size()).clear();
			}
		}

		// Pack the descriptors into an integer array. Each element of the
		// integer array will be the position in the value pool of the value
		// referred to by that descriptor, or -1 if the descriptor is unused.
		root.setIntArray(NBT_DESCRIPTORS_KEY, objects.stream().mapToInt(i -> (i == null) ? -1 : valuePool.store(i.value)).toArray());

		return root;
	}

	/**
	 * Closes all descriptors.
	 */
	public void closeAll() {
		objects.stream().filter(i -> i != null).forEach(i -> i.unref());
		objects.clear();
		firstEmpty = 0;
	}
}
