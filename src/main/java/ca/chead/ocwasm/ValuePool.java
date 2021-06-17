package ca.chead.ocwasm;

import java.util.IdentityHashMap;
import java.util.Map;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.Value;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * A pool of {@link Value} objects that can be saved to NBT form without
 * creating duplicates if the same object is referred to more than once.
 */
public final class ValuePool {
	/**
	 * The NBT compound key within a value pool entry where the class name is
	 * kept.
	 *
	 * This tag is a string containing the Java class name.
	 */
	private static final String NBT_VALUE_CLASS_KEY = "class";

	/**
	 * The NBT compound key within a value pool entry where the value’s data
	 * compound is kept.
	 *
	 * This tag is a compound containing the encoded form of the value.
	 */
	private static final String NBT_VALUE_DATA_KEY = "data";

	/**
	 * A map from value to index.
	 */
	private final IdentityHashMap<Value, Integer> values;

	/**
	 * Constructs an empty value pool.
	 */
	public ValuePool() {
		super();
		values = new IdentityHashMap<Value, Integer>();
	}

	/**
	 * Retrieves the index for a value, adding the value to the pool if it is
	 * not already present.
	 *
	 * @param value The value to add.
	 * @return The pool index.
	 */
	public int store(final Value value) {
		return values.computeIfAbsent(value, (k) -> values.size());
	}

	/**
	 * Saves the value pool to NBT.
	 *
	 * @return The created NBT tag.
	 */
	public NBTTagList save() {
		// Organize the values into an array by their index.
		final Value[] flat = new Value[values.size()];
		for(final Map.Entry<Value, Integer> i : values.entrySet()) {
			flat[i.getValue()] = i.getKey();
		}

		// Serialize each value into a compound and collect the compounds into
		// a list.
		final NBTTagList rootNBT = new NBTTagList();
		for(final Value i : flat) {
			final NBTTagCompound valueNBT = new NBTTagCompound();
			valueNBT.setString(NBT_VALUE_CLASS_KEY, i.getClass().getName());
			final NBTTagCompound dataNBT = new NBTTagCompound();
			i.save(dataNBT);
			valueNBT.setTag(NBT_VALUE_DATA_KEY, dataNBT);
			rootNBT.appendTag(valueNBT);
		}

		return rootNBT;
	}

	/**
	 * Loads a value pool from NBT.
	 *
	 * @param context The OpenComputers context.
	 * @param root The list that was previously returned from {@link #save}.
	 * @return The values in the pool, organized as an array.
	 */
	public static ReferencedValue[] load(final Context context, final NBTTagList root) {
		final ReferencedValue[] values = new ReferencedValue[root.tagCount()];
		for(int i = 0; i != values.length; ++i) {
			final NBTTagCompound valueNBT = root.getCompoundTagAt(i);
			final String className = valueNBT.getString(NBT_VALUE_CLASS_KEY);
			final NBTTagCompound dataNBT = valueNBT.getCompoundTag(NBT_VALUE_DATA_KEY);
			final Value value;
			try {
				final Class<? extends Value> clazz = Class.forName(className).asSubclass(Value.class);
				value = clazz.newInstance();
			} catch(final ReflectiveOperationException exp) {
				throw new RuntimeException("Error restoring OpenComputers opaque value of class " + className + " from NBT", exp);
			}
			value.load(dataNBT);
			values[i] = new ReferencedValue(value, context);
		}
		return values;
	}
}
