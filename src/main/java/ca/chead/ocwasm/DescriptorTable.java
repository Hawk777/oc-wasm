package ca.chead.ocwasm;

import java.util.ArrayList;
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
	 * An allocator that can incrementally allocate new descriptors, then
	 * either commit or abort the allocation.
	 *
	 * An instance of this class stores a collection of descriptors that have
	 * been provisionally allocated. These are not visible in the main table
	 * yet. To abort the allocation, the allocator can simply be closed. If
	 * the containing operation succeeds, however, the allocator should be
	 * committed before closing, which updates the main table and cannot fail.
	 *
	 * Only one allocator may exist at a time for a given descriptor table. The
	 * table must not be modified by other means while an allocator exists,
	 * either (specifically, descriptors must not be closed).
	 */
	public final class Allocator implements AutoCloseable {
		/**
		 * The descriptors added by this allocator.
		 */
		private final ArrayList<Integer> allocations;

		/**
		 * Constructs a new {@code Allocator}.
		 */
		public Allocator() {
			super();
			allocations = new ArrayList<Integer>();
		}

		/**
		 * Allocates a descriptor for an opaque value.
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
		 * Commits all allocations to the main table.
		 *
		 * Once this is called, closing the allocator will no longer close the
		 * allocated descriptors; instead, they will be permanently added to
		 * the descriptor table.
		 */
		public void commit() {
			// Forget the allocations, so that close() won’t do anything.
			allocations.clear();
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
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 */
	public DescriptorTable(final Context context, final NBTTagCompound root) {
		super();
		this.context = Objects.requireNonNull(context);

		// Load the values and create the entries.
		final ReferencedValue[] entries;
		{
			final NBTTagList valuesNBT = root.getTagList(NBT_VALUES_KEY, NBT.TAG_COMPOUND);
			final int count = valuesNBT.tagCount();
			entries = new ReferencedValue[count];
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
				entries[i] = new ReferencedValue(value);
			}
		}

		// Load the descriptors.
		{
			final int[] descriptorIndices = root.getIntArray(NBT_DESCRIPTORS_KEY);
			objects = new ArrayList<ReferencedValue>(descriptorIndices.length);
			for(final int i : descriptorIndices) {
				if(i == -1) {
					objects.add(null);
				} else {
					entries[i].ref();
					objects.add(entries[i]);
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
	 * @return The corresponding value.
	 * @throws BadDescriptorException If the descriptor does not have an
	 * associated value.
	 */
	public Value get(final int descriptor) throws BadDescriptorException {
		return getEntry(descriptor).value;
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
			entry = new ReferencedValue(value);
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
		entry.unref(context);
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
		for(final ReferencedValue i : objects) {
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
		firstEmpty = 0;
	}
}
