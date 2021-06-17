package ca.chead.ocwasm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Value;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagString;

/**
 * All the information needed to call a method on a component or value.
 */
public final class MethodCall {
	/**
	 * The possible special methods that can be performed on an opaque value.
	 *
	 * The elements of this enumeration are stored by ordinal in NBT in world
	 * saves. They must not be reordered or deleted.
	 */
	public enum SpecialMethod {
		/**
		 * The “call” method, which calls a callable opaque value.
		 */
		CALL {
			@Override
			public Object[] invoke(final Value value, final Machine machine, final Arguments params) throws NoSuchComponentOrValueMethodException {
				try {
					return value.call(machine, params);
				} catch(final RuntimeException exp) {
					if(exp.getClass() == RuntimeException.class) {
						// It’s not certain, but this is *probably* thrown by
						// the AbstractValue.call() implementation and is
						// telling us that this Value is not callable.
						// Unfortunately OpenComputers doesn’t give a reliable
						// way to distinguish that from other
						// RuntimeExceptions.
						throw new NoSuchComponentOrValueMethodException();
					} else {
						throw exp;
					}
				}
			}
		},

		/**
		 * The “apply” method, which reads from an index of the opaque value.
		 */
		APPLY {
			@Override
			public Object[] invoke(final Value value, final Machine machine, final Arguments params) {
				return new Object[]{value.apply(machine, params)};
			}
		},

		/**
		 * The “unapply” method, which writes to an index of the opaque value.
		 */
		UNAPPLY {
			@Override
			public Object[] invoke(final Value value, final Machine machine, final Arguments params) {
				value.unapply(machine, params);
				return OCWasm.ZERO_OBJECTS;
			}
		};

		/**
		 * Invokes the special method.
		 *
		 * @param value The value to invoke on.
		 * @param machine The machine doing the invoking.
		 * @param params The parameters to pass.
		 * @return The method’s return value.
		 * @throws NoSuchComponentOrValueMethodException If the method does not
		 * exist.
		 */
		public abstract Object[] invoke(Value value, Machine machine, Arguments params) throws NoSuchComponentOrValueMethodException;
	}

	/**
	 * An implementation of {@code li.cil.oc.api.machine.Arguments} that wraps
	 * an array of {@code Object}.
	 */
	private static final class Arguments implements li.cil.oc.api.machine.Arguments {
		/**
		 * The parameter list.
		 */
		private final Object[] parameters;

		/**
		 * Constructs a new {@code Arguments}.
		 *
		 * @param parameters The parameter list to wrap.
		 */
		Arguments(final Object[] parameters) {
			super();
			this.parameters = Objects.requireNonNull(parameters);
		}

		/**
		 * Checks whether an index is in range.
		 *
		 * @param index The index.
		 * @return {@code true} if {@code index} is in range, or {@code false}
		 * if not.
		 */
		private boolean inRange(final int index) {
			return 0 <= index && index < parameters.length;
		}

		@Override
		public Iterator<Object> iterator() {
			return Arrays.stream(parameters).iterator();
		}

		@Override
		public int count() {
			return parameters.length;
		}

		@Override
		public Object checkAny(final int index) {
			if(inRange(index)) {
				return parameters[index];
			} else {
				throw new IllegalArgumentException("Index out of range");
			}
		}

		@Override
		public boolean checkBoolean(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Boolean) {
				return (Boolean) o;
			} else {
				throw new IllegalArgumentException("Expected a boolean");
			}
		}

		@Override
		public int checkInteger(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Integer) {
				return (Integer) o;
			} else {
				throw new IllegalArgumentException("Expected an integer");
			}
		}

		@Override
		public double checkDouble(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Double) {
				return (Double) o;
			} else {
				throw new IllegalArgumentException("Expected a double");
			}
		}

		@Override
		public String checkString(final int index) {
			final Object o = checkAny(index);
			if(o instanceof String) {
				return (String) o;
			} else {
				throw new IllegalArgumentException("Expected a string");
			}
		}

		@Override
		public byte[] checkByteArray(final int index) {
			final Object o = checkAny(index);
			if(o instanceof byte[]) {
				return (byte[]) o;
			} else {
				throw new IllegalArgumentException("Expected a byte array");
			}
		}

		@Override
		public Map<?, ?> checkTable(final int index) {
			final Object o = checkAny(index);
			if(o instanceof Map) {
				return (Map) o;
			} else {
				throw new IllegalArgumentException("Expected a map");
			}
		}

		@Override
		public ItemStack checkItemStack(final int index) {
			final Object o = checkAny(index);
			if(o instanceof ItemStack) {
				return (ItemStack) o;
			} else {
				throw new IllegalArgumentException("Expected an item stack");
			}
		}

		@Override
		public Object optAny(final int index, final Object def) {
			return inRange(index) ? parameters[index] : def;
		}

		@Override
		public boolean optBoolean(final int index, final boolean def) {
			return inRange(index) ? checkBoolean(index) : def;
		}

		@Override
		public int optInteger(final int index, final int def) {
			return inRange(index) ? checkInteger(index) : def;
		}

		@Override
		public double optDouble(final int index, final double def) {
			return inRange(index) ? checkDouble(index) : def;
		}

		@Override
		public String optString(final int index, final String def) {
			return inRange(index) ? checkString(index) : def;
		}

		@Override
		public byte[] optByteArray(final int index, final byte[] def) {
			return inRange(index) ? checkByteArray(index) : def;
		}

		@Override
		@SuppressWarnings("rawtypes")
		public Map<?, ?> optTable(final int index, final Map def) {
			return inRange(index) ? checkTable(index) : def;
		}

		@Override
		public ItemStack optItemStack(final int index, final ItemStack def) {
			return inRange(index) ? checkItemStack(index) : def;
		}

		@Override
		public boolean isBoolean(final int index) {
			return inRange(index) && parameters[index] instanceof Boolean;
		}

		@Override
		public boolean isInteger(final int index) {
			return inRange(index) && parameters[index] instanceof Integer;
		}

		@Override
		public boolean isDouble(final int index) {
			return inRange(index) && parameters[index] instanceof Double;
		}

		@Override
		public boolean isString(final int index) {
			return inRange(index) && parameters[index] instanceof String;
		}

		@Override
		public boolean isByteArray(final int index) {
			return inRange(index) && parameters[index] instanceof byte[];
		}

		@Override
		public boolean isTable(final int index) {
			return inRange(index) && parameters[index] instanceof Map;
		}

		@Override
		public boolean isItemStack(final int index) {
			return inRange(index) && parameters[index] instanceof ItemStack;
		}

		@Override
		public Object[] toArray() {
			return Arrays.stream(parameters).map(i -> (i instanceof byte[]) ? OCWasm.UTF8.decode(ByteBuffer.wrap((byte[]) i)) : i).toArray();
		}
	}

	/**
	 * The name of the NBT tag that holds the {@link #target} field.
	 */
	private static final String NBT_TARGET = "target";

	/**
	 * The name of the NBT tag that holds the {@link #method} field.
	 */
	private static final String NBT_METHOD = "method";

	/**
	 * The name of the NBT tag that holds the {@link #parameters} field.
	 */
	private static final String NBT_PARAMETERS = "parameters";

	/**
	 * The target on which to invoke a method.
	 *
	 * This is either a {@code String} holding the UUID of the component, or a
	 * {@code Value} holding an opaque value which has methods.
	 */
	public final Object target;

	/**
	 * The method.
	 *
	 * This is either a {@code String} to invoke a method, or a {@link
	 * SpecialMethod} to invoke a special method on an opaque value.
	 */
	public final Object method;

	/**
	 * The parameters to pass to the method.
	 */
	public final Object[] parameters;

	/**
	 * Constructs a new {@code MethodCall} to call a method on a component.
	 *
	 * @param address The UUID of the component.
	 * @param method The name of the method.
	 * @param parameters The parameters to pass to the method.
	 */
	public MethodCall(final String address, final String method, final Object[] parameters) {
		super();
		this.target = Objects.requireNonNull(address);
		this.method = Objects.requireNonNull(method);
		this.parameters = Objects.requireNonNull(parameters);
	}

	/**
	 * Constructs a new {@code MethodCall} to call a special method on an opaque value.
	 *
	 * @param value The opaque value.
	 * @param method The method.
	 * @param parameters The parameters to pass to the method.
	 */
	public MethodCall(final Value value, final SpecialMethod method, final Object[] parameters) {
		super();
		this.target = Objects.requireNonNull(value);
		this.method = Objects.requireNonNull(method);
		this.parameters = Objects.requireNonNull(parameters);
	}

	/**
	 * Constructs a new {@code MethodCall} to call a regular method on an opaque value.
	 *
	 * @param value The opaque value.
	 * @param method The name of the method.
	 * @param parameters The parameters to pass to the method.
	 */
	public MethodCall(final Value value, final String method, final Object[] parameters) {
		super();
		this.target = Objects.requireNonNull(value);
		this.method = Objects.requireNonNull(method);
		this.parameters = Objects.requireNonNull(parameters);
	}

	/**
	 * Loads a {@code MethodCall} that was previously saved.
	 *
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 * @param descriptors The descriptor table, which must already have been
	 * restored.
	 */
	public MethodCall(final NBTTagCompound root, final DescriptorTable descriptors) {
		super();

		// When save() persisted the MethodCall, if it encountered any opaque
		// values, it allocated new descriptors for them and represented them
		// in the NBT (either directly, in the case of target, or inside CBOR,
		// in the case of parameters) via those descriptors. Those descriptors
		// were not intentionally created by the Wasm module instance; rather,
		// they only exist because save() created them. We should therefore
		// keep track of them as we load, and close them at the end.
		final ArrayList<Integer> descriptorsToClose = new ArrayList<Integer>();

		// Load the target. It is either a string UUID for a component call or
		// an integer descriptor for an opaque value call.
		{
			final NBTBase targetNBT = root.getTag(NBT_TARGET);
			if(targetNBT instanceof NBTTagString) {
				this.target = ((NBTTagString) targetNBT).getString();
			} else {
				final int descriptor = ((NBTTagInt) targetNBT).getInt();
				try {
					this.target = descriptors.get(descriptor);
				} catch(final BadDescriptorException exp) {
					throw new RuntimeException("Save data is corrupt: MethodCall refers to closed descriptor", exp);
				}
				descriptorsToClose.add(descriptor);
			}
		}

		// Load the method. It is either a string method name or an integer
		// SpecialMethod ordinal.
		{
			final NBTBase methodNBT = root.getTag(NBT_METHOD);
			if(methodNBT instanceof NBTTagString) {
				this.method = ((NBTTagString) methodNBT).getString();
			} else {
				final int ordinal = ((NBTTagInt) methodNBT).getInt();
				this.method = Arrays.stream(SpecialMethod.values()).filter(i -> i.ordinal() == ordinal).findAny().get();
			}
		}

		// Load the parameters.
		{
			final byte[] bytes = root.getByteArray(NBT_PARAMETERS);
			try {
				this.parameters = CBOR.toJavaSequence(ByteBuffer.wrap(bytes), descriptors, descriptorsToClose::add);
			} catch(final CBORDecodeException exp) {
				throw new RuntimeException("Save data is corrupt: MethodCall contains invalid CBOR", exp);
			} catch(final BadDescriptorException exp) {
				throw new RuntimeException("Save data is corrupt: MethodCall refers to closed descriptor", exp);
			}
		}

		// Close all the descriptors that were referred to during loading of this object.
		descriptorsToClose.stream().forEach(descriptor -> {
			try {
				descriptors.close(descriptor);
			} catch(final BadDescriptorException exp) {
				// This should be impossible if we just used the descriptor
				// during reconstruction of the data!
				throw new RuntimeException(exp);
			}
		});

		// Sanity check.
		if(target instanceof String && method instanceof SpecialMethod) {
			throw new RuntimeException("Save data is corrupt: MethodCall cannot target SpecialMethod on component UUID");
		}
	}

	/**
	 * Tries to perform the call directly.
	 *
	 * @param machine The machine.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws InProgressException If the method must be invoked indirectly
	 * (the call is <em>not</em> pended in this case; the caller must do that).
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	public Object[] invokeDirect(final Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, InProgressException, BadParametersException, OtherException {
		return invoke(machine, true);
	}

	/**
	 * Tries to perform the call indirectly.
	 *
	 * @param machine The machine.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	public Object[] invokeIndirect(final Machine machine) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, BadParametersException, OtherException {
		try {
			return invoke(machine, false);
		} catch(final InProgressException exp) {
			throw new RuntimeException("Impossible exception thrown", exp);
		}
	}

	/**
	 * Tries to perform the call.
	 *
	 * @param machine The machine.
	 * @param direct {@code true} if the current call is being made from the
	 * computer thread, or {@code false} if it is being made from the server
	 * thread.
	 * @return The value returned by the call.
	 * @throws NoSuchComponentException If this is a component method call and
	 * the component does not exist or is not visible from this computer.
	 * @throws NoSuchComponentOrValueMethodException If the method does not
	 * exist.
	 * @throws InProgressException If the method must be invoked indirectly
	 * (the call is <em>not</em> pended in this case; the caller must do that).
	 * @throws BadParametersException If the parameters were unacceptable for
	 * the method.
	 * @throws OtherException If the method failed.
	 */
	private Object[] invoke(final Machine machine, final boolean direct) throws NoSuchComponentException, NoSuchComponentOrValueMethodException, InProgressException, BadParametersException, OtherException {
		// Sanity check.
		Objects.requireNonNull(machine);

		// If this is a regular method call, check that the component is
		// accessible (if targeting a component) and check that we are not
		// trying to directly invoke an indirect method. If this is a special
		// method call, then it must be on a value not a component (so the
		// accessibility check is irrelevant) and it is always direct (so the
		// indirect check is irrelevant).
		if(method instanceof String) {
			final Object methodMapValue;
			if(target instanceof String) {
				methodMapValue = ComponentUtils.getComponent(machine, (String) target).host();
			} else if(target instanceof Value) {
				methodMapValue = target;
			} else {
				throw new RuntimeException("Unknown target type " + target.getClass());
			}
			final Callback cb = machine.methods(methodMapValue).get(method);
			if(cb == null) {
				throw new NoSuchComponentOrValueMethodException();
			}
			if(direct && !cb.direct()) {
				throw new InProgressException();
			}
		}

		// Perform the call.
		final Object[] result;
		try {
			if(method instanceof String) {
				if(target instanceof String) {
					result = machine.invoke((String) target, (String) method, parameters);
				} else if(target instanceof Value) {
					result = machine.invoke((Value) target, (String) method, parameters);
				} else {
					throw new RuntimeException("Unknown target type " + target.getClass());
				}
			} else if(method instanceof SpecialMethod) {
				if(target instanceof Value) {
					final Object[] unconverted = ((SpecialMethod) method).invoke((Value) target, machine, new Arguments(parameters));
					result = OCWasm.convertValues(unconverted);
				} else {
					throw new RuntimeException("Unknown target type " + target.getClass());
				}
			} else {
				throw new RuntimeException("Unknown method type " + method);
			}
		} catch(final NoSuchMethodException exp) {
			throw new NoSuchComponentOrValueMethodException();
		} catch(final LimitReachedException exp) {
			if(direct) {
				throw new InProgressException();
			} else {
				throw new RuntimeException("Indirect method call returned LimitReachedException, which should be impossible");
			}
		} catch(final IllegalArgumentException exp) {
			throw new BadParametersException();
		} catch(final Throwable t) {
			throw new OtherException();
		}
		return result != null ? result : OCWasm.ZERO_OBJECTS;
	}

	/**
	 * Saves the {@code MethodCall} into an NBT structure.
	 *
	 * @param descriptorTable The descriptor table to use to save opaque
	 * values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor created during saving.
	 * @return The created NBT compound.
	 */
	public NBTTagCompound save(final DescriptorTable descriptorTable, final IntConsumer descriptorListener) {
		final NBTTagCompound root = new NBTTagCompound();
		if(target instanceof String) {
			root.setString(NBT_TARGET, (String) target);
		} else {
			try(DescriptorTable.Allocator alloc = descriptorTable.new Allocator()) {
				final int descriptor = alloc.add((Value) target);
				alloc.commit();
				descriptorListener.accept(descriptor);
				root.setInteger(NBT_TARGET, descriptor);
			}
		}
		if(method instanceof String) {
			root.setString(NBT_METHOD, (String) method);
		} else {
			root.setInteger(NBT_METHOD, ((SpecialMethod) method).ordinal());
		}
		root.setByteArray(NBT_PARAMETERS, CBOR.toCBORSequence(Arrays.stream(parameters), descriptorTable, descriptorListener));
		return root;
	}
}
