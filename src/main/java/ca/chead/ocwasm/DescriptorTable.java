package ca.chead.ocwasm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.Value;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

/**
 * A table mapping between integer descriptors and opaque values, with the
 * ability to allocate new descriptors and close existing descriptors.
 */
public final class DescriptorTable {
	/**
	 * An opaque value entry.
	 *
	 * Each valid element in the descriptor table points at an instance of this
	 * class. However, there is only one instance of this class for each
	 * distinct opaque value. If the same opaque value is added to the
	 * descriptor table more than once, each addition yields a fresh descriptor
	 * number, but all those table positions point at the same {@code Entry}
	 * object. Once all descriptors referring to the same {@code Entry} are
	 * closed, the value itself is disposed.
	 */
	private static final class Entry {
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
		Entry(final Value value) {
			super();
			this.value = Objects.requireNonNull(value);
			references = 0;
		}

		/**
		 * Increments the reference count of this entry.
		 */
		void ref() {
			++references;
		}

		/**
		 * Decrements the reference count of this entry and, if it reaches
		 * zero, disposes of the value.
		 *
		 * @param context The OpenComputers context.
		 */
		void unref(final Context context) {
			--references;
			if(references == 0) {
				value.dispose(context);
			}
		}
	}

	/**
	 * An allocator that can incrementally allocate new descriptors, then
	 * either commit or abort the allocation.
	 *
	 * An instance of this class stores a collection of descriptors that have
	 * been provisionally allocated. These are not visible in the main table
	 * yet. To abort the allocation, the allocator can simply be dropped. If
	 * the containing operation succeeds, however, the allocator should be
	 * committed, which updates the main table and cannot fail.
	 *
	 * Only one allocator may exist at a time for a given descriptor table. The
	 * table must not be modified by other means while an allocator exists,
	 * either (specifically, descriptors must not be closed).
	 */
	public final class Allocator {
		/**
		 * An allocation record.
		 */
		private final class Allocation {
			/**
			 * The descriptor number that is allocated.
			 */
			public final int descriptor;

			/**
			 * The table entry to point the descriptor at.
			 */
			public final Entry entry;

			/**
			 * Constructs a new {@code Allocation}.
			 *
			 * @param descriptor The descriptor number that is allocated.
			 * @param entry The table entry to point the descriptor at.
			 */
			Allocation(final int descriptor, final Entry entry) {
				super();
				this.descriptor = descriptor;
				this.entry = Objects.requireNonNull(entry);
			}
		}

		/**
		 * The allocation records created so far.
		 */
		private final ArrayList<Allocation> allocations;

		/**
		 * The next descriptor index to try allocating.
		 */
		private int next;

		/**
		 * Constructs a new {@code Allocator}.
		 */
		public Allocator() {
			super();
			allocations = new ArrayList<Allocation>();
			next = 0;
		}

		/**
		 * Provisionally allocates a descriptor for an opaque value.
		 *
		 * @param value The value.
		 * @return The provisionally allocated descriptor value.
		 */
		public int add(final Value value) {
			// Sanity check.
			Objects.requireNonNull(value);

			// Locate the next free descriptor number.
			while(next < objects.size() && objects.get(next) != null) {
				++next;
			}

			// Grab the descriptor and advance the next counter past it.
			final int descriptor = next;
			++next;

			// Get hold of the proper Entry object.
			final Entry entry;
			final Entry existingEntry = objects.stream().filter(i -> i != null && i.value == value).findAny().orElse(null);
			if(existingEntry != null) {
				// There’s already an Entry for this Value in the main table.
				// Reuse that Entry. Don’t ref it now; on abort we don’t want
				// the refcount to change, and on commit we’ll update it there.
				entry = existingEntry;
			} else {
				// There’s no Entry for this Value in the main table.
				final Entry provisionalEntry = allocations.stream().filter(i -> i.entry.value == value).map(i -> i.entry).findAny().orElse(null);
				if(provisionalEntry != null) {
					// There is a provisional Entry for this Value already in
					// this Allocator. Reuse that Entry. Don’t ref it now; on
					// abort we don’t care (because it’s all provisional),
					// while on commit we’ll ref everything the proper number
					// of times.
					entry = provisionalEntry;
				} else {
					// There’s no Entry for this Value. Create a new one. Leave
					// its refcount at zero; on abort we don’t care (because
					// it’s all provisional), while on commit we’ll ref
					// everything the proper number of times.
					entry = new Entry(value);
				}
			}

			allocations.add(new Allocation(descriptor, entry));
			return descriptor;
		}

		/**
		 * Commits all allocations to the main table.
		 *
		 * This must only be called once, and the {@code Allocator} must be
		 * thrown away afterwards.
		 */
		public void commit() {
			// If there are no allocations, drop out early.
			if(allocations.isEmpty()) {
				return;
			}

			// Find the largest descriptor in the list of allocations. Since
			// the allocations are always constructed in ascending order,
			// that’s just the last one.
			final int maxDescriptor = allocations.get(allocations.size() - 1).descriptor;

			// If the main table is too small, grow it first.
			if(objects.size() <= maxDescriptor) {
				final int growBy = (maxDescriptor - objects.size()) + 1;
				objects.addAll(Collections.nCopies(growBy, null));
			}

			// Put the allocations into the table, reffing each one. For Entry
			// objects that are pointed to by only one descriptor, this changes
			// their refcount from zero to one. For those that are
			// shared—either with other new descriptors or with existing
			// ones—this increments it by the proper amount.
			allocations.stream().forEach(i -> {
				i.entry.ref();
				objects.set(i.descriptor, i.entry);
			});

			// Just in case.
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
	 * The NBT compound key where the opaque values are kept.
	 *
	 * This tag is a list of compounds, each of which contains {@link
	 * #NBT_VALUE_CLASS_KEY} and {@link #NBT_VALUE_DATA_KEY} tags.
	 */
	private static final String NBT_VALUES_KEY = "values";

	/**
	 * The NBT compound key within a value compound where the class name is
	 * kept.
	 *
	 * This tag is a string containing the Java class name.
	 */
	private static final String NBT_VALUE_CLASS_KEY = "class";

	/**
	 * The NBT compound key within a value compound where the value’s data
	 * compound is kept.
	 *
	 * This tag is a compound containing the encoded form of the value.
	 */
	private static final String NBT_VALUE_DATA_KEY = "data";

	/**
	 * The NBT compound key where the descriptors are kept.
	 *
	 * This tag is an integer array. Each position in the array corresponds to
	 * one descriptor value (i.e. position 0 is descriptor 0, and so on). Each
	 * element is either the index within {@link #NBT_VALUES_KEY} of the value
	 * to which the value refers, or −1 if the descriptor is closed.
	 */
	private static final String NBT_DESCRIPTORS_KEY = "descriptors";

	/**
	 * The OpenComputers context.
	 */
	private final Context context;

	/**
	 * The objects.
	 */
	private final ArrayList<Entry> objects;

	/**
	 * Constructs an empty {@code DescriptorTable}.
	 *
	 * @param context The OpenComputers context.
	 */
	public DescriptorTable(final Context context) {
		super();
		this.context = Objects.requireNonNull(context);
		objects = new ArrayList<Entry>();
	}

	/**
	 * Loads a {@code DescriptorTable} that was previously saved.
	 *
	 * @param context The OpenComputers context.
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 */
	public DescriptorTable(final Context context, final NBTTagCompound root) {
		super();
		this.context = Objects.requireNonNull(context);

		// Load the values and create the entries.
		final Entry[] entries;
		{
			final NBTTagList valuesNBT = root.getTagList(NBT_VALUES_KEY, NBT.TAG_COMPOUND);
			final int count = valuesNBT.tagCount();
			entries = new Entry[count];
			for(int i = 0; i != count; ++i) {
				final NBTTagCompound valueNBT = valuesNBT.getCompoundTagAt(i);
				final String className = valueNBT.getString(NBT_VALUE_CLASS_KEY);
				final NBTTagCompound dataNBT = valueNBT.getCompoundTag(NBT_VALUE_DATA_KEY);
				final Value value;
				try {
					final Class<? extends Value> clazz = Class.forName(className).asSubclass(Value.class);
					value = clazz.newInstance();
				} catch(final ReflectiveOperationException exp) {
					throw new RuntimeException("Error restoring OC-Wasm descriptor table opaque value of class " + className + " from NBT", exp);
				}
				value.load(dataNBT);
				entries[i] = new Entry(value);
			}
		}

		// Load the descriptors.
		{
			final int[] descriptorIndices = root.getIntArray(NBT_DESCRIPTORS_KEY);
			objects = new ArrayList<Entry>(descriptorIndices.length);
			for(final int i : descriptorIndices) {
				if(i == -1) {
					objects.add(null);
				} else {
					entries[i].ref();
					objects.add(entries[i]);
				}
			}
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
	 * @return The corresponding value.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated value.
	 */
	public Value get(final int descriptor) throws BadDescriptorException {
		return getEntry(descriptor).value;
	}

	/**
	 * Closes a descriptor.
	 *
	 * @param descriptor The descriptor to close.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated value.
	 */
	public void close(final int descriptor) throws BadDescriptorException {
		final Entry entry = getEntry(descriptor);
		objects.set(descriptor, null);
		entry.unref(context);
	}

	/**
	 * Gets an entry.
	 *
	 * @param descriptor The descriptor to look up.
	 * @return The corresponding entry.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated entry.
	 */
	private Entry getEntry(final int descriptor) throws BadDescriptorException {
		final Entry ret;
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
	 * @return The created NBT tag.
	 */
	public NBTTagCompound save() {
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

		// Put the values into a list. Save each one only once, not once per
		// descriptor that refers to it, and record its position in the list.
		// For each value, create a compound. In the compound, put its class
		// name and another compound containing its saved data.
		final NBTTagList values = new NBTTagList();
		final IdentityHashMap<Value, Integer> valuePositions = new IdentityHashMap<Value, Integer>();
		for(final Entry i : objects) {
			if(i != null) {
				if(!valuePositions.containsKey(i.value)) {
					final int position = values.tagCount();
					final NBTTagCompound valueNBT = new NBTTagCompound();
					valueNBT.setString(NBT_VALUE_CLASS_KEY, i.value.getClass().getName());
					final NBTTagCompound dataNBT = new NBTTagCompound();
					i.value.save(dataNBT);
					valueNBT.setTag(NBT_VALUE_DATA_KEY, dataNBT);
					values.appendTag(valueNBT);
					valuePositions.put(i.value, position);
				}
			}
		}
		root.setTag(NBT_VALUES_KEY, values);

		// Pack the descriptors into an integer array. Each element of the
		// integer array will be the position in the values list of the value
		// referred to by that descriptor, or -1 if the descriptor is unused.
		root.setIntArray(NBT_DESCRIPTORS_KEY, objects.stream().mapToInt(i -> (i == null) ? -1 : valuePositions.get(i.value)).toArray());

		return root;
	}

	/**
	 * Closes all descriptors.
	 */
	public void closeAll() {
		objects.stream().filter(i -> i != null).forEach(i -> i.unref(context));
		objects.clear();
	}
}
