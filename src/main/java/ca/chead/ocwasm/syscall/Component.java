package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.BadDescriptorException;
import ca.chead.ocwasm.CBOR;
import ca.chead.ocwasm.CBORDecodeException;
import ca.chead.ocwasm.CallResult;
import ca.chead.ocwasm.ComponentUtils;
import ca.chead.ocwasm.DescriptorTable;
import ca.chead.ocwasm.ErrorCode;
import ca.chead.ocwasm.InProgressException;
import ca.chead.ocwasm.MemoryFaultException;
import ca.chead.ocwasm.MemoryUtils;
import ca.chead.ocwasm.MethodCall;
import ca.chead.ocwasm.NoSuchComponentException;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.ReferencedValue;
import ca.chead.ocwasm.StringDecodeException;
import ca.chead.ocwasm.SyscallErrorException;
import ca.chead.ocwasm.ValuePool;
import ca.chead.ocwasm.ValueReference;
import ca.chead.ocwasm.WasmString;
import ca.chead.ocwasm.WrappedException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants.NBT;

/**
 * The syscalls available for import into a Wasm module in the {@code
 * component} module.
 */
public final class Component {
	/**
	 * Information about a method.
	 */
	private static final class MethodInfo {
		/**
		 * The attribute bitmask value indicating that the method is direct.
		 */
		public static final int ATTRIBUTE_DIRECT = 1;

		/**
		 * The attribute bitmask value indicating that the method is a getter.
		 */
		public static final int ATTRIBUTE_GETTER = 2;

		/**
		 * The attribute bitmask value indicating that the method is a setter.
		 */
		public static final int ATTRIBUTE_SETTER = 4;

		/**
		 * The name of the NBT tag that holds the {@link #name} field.
		 *
		 * This tag is a string.
		 */
		private static final String NBT_NAME = "name";

		/**
		 * The name of the NBT tag that holds the {@link #attributes} field.
		 *
		 * This tag is an integer.
		 */
		private static final String NBT_ATTRIBUTES = "attr";

		/**
		 * The method’s name.
		 */
		public final String name;

		/**
		 * The method’s attribute bitmask.
		 */
		public final int attributes;

		/**
		 * Constructs a new {@code MethodInfo}.
		 *
		 * @param name The method name.
		 * @param callback The callback object.
		 */
		MethodInfo(final String name, final Callback callback) {
			super();
			this.name = Objects.requireNonNull(name);
			attributes = (callback.direct() ? ATTRIBUTE_DIRECT : 0)
				| (callback.getter() ? ATTRIBUTE_GETTER : 0)
				| (callback.setter() ? ATTRIBUTE_SETTER : 0);
		}

		/**
		 * Constructs a new {@code MethodInfo}.
		 *
		 * @param entry The map entry containing a name and callback.
		 */
		MethodInfo(final Map.Entry<String, Callback> entry) {
			this(entry.getKey(), entry.getValue());
		}

		/**
		 * Loads a {@code MethodInfo} that was previously saved.
		 *
		 * @param root The compound that was previously returned from {@link
		 * #save}.
		 */
		MethodInfo(final NBTTagCompound root) {
			super();
			this.name = Objects.requireNonNull(root.getString(NBT_NAME));
			this.attributes = root.getInteger(NBT_ATTRIBUTES);
		}

		/**
		 * Saves the {@code MethodInfo} into an NBT structure.
		 *
		 * @return The created NBT tag.
		 */
		NBTTagCompound save() {
			final NBTTagCompound root = new NBTTagCompound();
			root.setString(NBT_NAME, name);
			root.setInteger(NBT_ATTRIBUTES, attributes);
			return root;
		}
	}

	/**
	 * Information about the parameters to a method call.
	 */
	private final class MethodCallParameters {
		/**
		 * The parameters.
		 */
		public final Object[] params;

		/**
		 * The opaque values referred to by the parameters.
		 */
		public final List<ValueReference> values;

		/**
		 * Reads the parameters for a method call.
		 *
		 * @param pointer The pointer where the parameters are stored, which
		 * may be null.
		 * @throws MemoryFaultException If the pointer is invalid or the CBOR
		 * data runs off the end of memory.
		 * @throws CBORDecodeException If the CBOR encoding is invalid.
		 * @throws BadDescriptorException If the CBOR refers to a descriptor
		 * that is closed.
		 */
		MethodCallParameters(final int pointer) throws MemoryFaultException, CBORDecodeException, BadDescriptorException {
			if(pointer != 0) {
				final ArrayList<Integer> paramDescriptors = new ArrayList<Integer>();
				params = CBOR.toJavaArray(MemoryUtils.region(memory, pointer), descriptors, paramDescriptors::add);
				final ArrayList<ValueReference> v = new ArrayList<ValueReference>(paramDescriptors.size());
				for(final int paramDescriptor : paramDescriptors) {
					// This cannot throw BadDescriptorException because, if it
					// did, CBOR.toJavaArray would have failed; therefore, we
					// do not have to worry about cleaning up a half-done job.
					v.add(descriptors.get(paramDescriptor));
				}
				values = Collections.unmodifiableList(v);
			} else {
				params = OCWasm.ZERO_OBJECTS;
				values = Collections.emptyList();
			}
		}
	}

	/**
	 * A callable that can build a {@link MethodCall} object.
	 *
	 * This is similar to {@code Suppler<MethodCall>} except that it declares
	 * checked exceptions.
	 */
	@FunctionalInterface
	private interface MethodCallBuilder {
		/**
		 * Builds the MethodCall object.
		 *
		 * @return The constructed call.
		 * @throws SyscallErrorException If the system call cannot be executed
		 * for a reason that is the Wasm module instance’s fault.
		 */
		MethodCall build() throws SyscallErrorException;
	}

	/**
	 * The name of the NBT tag that holds the {@link #list} field.
	 *
	 * This tag is an even-length list of strings holding interleaved component
	 * names and types. It is absent if {@link #list} is {@code null}.
	 */
	private static final String NBT_LIST = "list";

	/**
	 * The name of the NBT tag that holds the {@link #listIndex} field.
	 *
	 * This tag is an integer. It may be absent if {@link #listIndex} is zero.
	 */
	private static final String NBT_LIST_INDEX = "listIndex";

	/**
	 * The name of the NBT tag that holds the {@link #methods} field.
	 *
	 * This tag is a list of compounds, each the encoded form of a {@link
	 * MethodInfo}. Only the items remaining in the list are saved.
	 */
	private static final String NBT_METHODS = "methods";

	/**
	 * The name of the NBT tag that holds the {@link #pendingCall} field.
	 *
	 * This tag is a compound, the encoded form of a {@link MethodCall}. It is
	 * absent if {@link #pendingCall} is {@code null}.
	 */
	private static final String NBT_PENDING_CALL = "pendingCall";

	/**
	 * The name of the NBT tag that holds the {@link #callResult} field.
	 *
	 * This tag is the encoded form of a {@link CallResult}. It is absent if
	 * {@link #callResult} is {@code null}.
	 */
	private static final String NBT_CALL_RESULT = "callResult";

	/**
	 * The machine on which the module operates.
	 */
	private final Machine machine;

	/**
	 * The linear memory.
	 */
	private final ByteBuffer memory;

	/**
	 * The descriptors and the values they refer to.
	 */
	private final DescriptorTable descriptors;

	/**
	 * A snapshot of the (UUID, type) pairs of the connected components
	 * (optionally filtered by a requested type) created when {@link
	 * #listStart} was called.
	 */
	private List<Map.Entry<UUID, String>> list;

	/**
	 * The position within {@link #list} of the next item to return.
	 */
	private int listIndex;

	/**
	 * A snapshot of the (name, attributes) pairs of the methods on a component
	 * created when {@link #methodsStart} was called.
	 */
	private List<MethodInfo> methods;

	/**
	 * The position within {@link #methods} of the next item to return.
	 */
	private int methodsIndex;

	/**
	 * The method call that has been started and not yet finished because it
	 * needs to be performed indirectly, or {@code null} if there isn’t one.
	 */
	private MethodCall pendingCall;

	/**
	 * The unfetched result of the last method call, or {@code null} if there
	 * is no unfetched result.
	 */
	private CallResult callResult;

	/**
	 * Constructs a new {@code Component}.
	 *
	 * @param machine The machine on which the module operates.
	 * @param memory The linear memory.
	 * @param descriptors The descriptor table.
	 */
	public Component(final Machine machine, final ByteBuffer memory, final DescriptorTable descriptors) {
		super();
		this.machine = Objects.requireNonNull(machine);
		this.memory = Objects.requireNonNull(memory);
		this.descriptors = Objects.requireNonNull(descriptors);
		list = null;
		listIndex = 0;
		methods = null;
		methodsIndex = 0;
		pendingCall = null;
		callResult = null;
	}

	/**
	 * Loads a {@code Component} that was previously saved.
	 *
	 * @param machine The machine on which the module operates.
	 * @param memory The linear memory.
	 * @param valuePool The value pool.
	 * @param descriptors The descriptor table.
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 */
	public Component(final Machine machine, final ByteBuffer memory, final ReferencedValue[] valuePool, final DescriptorTable descriptors, final NBTTagCompound root) {
		super();
		this.machine = Objects.requireNonNull(machine);
		this.memory = Objects.requireNonNull(memory);
		this.descriptors = Objects.requireNonNull(descriptors);

		// Load list.
		if(root.hasKey(NBT_LIST)) {
			final NBTTagList listNBT = root.getTagList(NBT_LIST, NBT.TAG_STRING);
			list = new ArrayList<Map.Entry<UUID, String>>(listNBT.tagCount() / 2);
			for(int i = 0; i < listNBT.tagCount(); i += 2) {
				list.add(new AbstractMap.SimpleImmutableEntry<UUID, String>(UUID.fromString(listNBT.getStringTagAt(i)), listNBT.getStringTagAt(i + 1)));
			}
		}

		// Load listIndex.
		if(root.hasKey(NBT_LIST_INDEX)) {
			listIndex = root.getInteger(NBT_LIST_INDEX);
		} else {
			listIndex = 0;
		}

		// Load methods.
		if(root.hasKey(NBT_METHODS)) {
			final NBTTagList methodsNBT = root.getTagList(NBT_METHODS, NBT.TAG_COMPOUND);
			methods = new ArrayList<MethodInfo>(methodsNBT.tagCount());
			for(int i = 0; i != methodsNBT.tagCount(); ++i) {
				methods.add(new MethodInfo(methodsNBT.getCompoundTagAt(i)));
			}
		} else {
			methods = null;
		}

		// methodsIndex does not need loading. It is always set to zero,
		// because methods excludes the elements that have already been
		// consumed.
		methodsIndex = 0;

		// Load pendingCall.
		if(root.hasKey(NBT_PENDING_CALL)) {
			pendingCall = MethodCall.load(root.getCompoundTag(NBT_PENDING_CALL), valuePool, descriptors);
		} else {
			pendingCall = null;
		}

		// Load callResult.
		if(root.hasKey(NBT_CALL_RESULT)) {
			callResult = new CallResult(root.getCompoundTag(NBT_CALL_RESULT));
		} else {
			callResult = null;
		}
	}

	/**
	 * Begins iterating over the components available in the computer and on
	 * its local network segment.
	 *
	 * @param typePointer A pointer to a string containing a component type, to
	 * return only components of that type, or null to return all components.
	 * @param typeLength The length of the type string.
	 * @return 0 on success, or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#STRING_DECODE}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int listStart(final int typePointer, final int typeLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final String type = (typePointer == 0) ? null : WasmString.toJava(memory, typePointer, typeLength);
			list = machine.components().entrySet().stream().filter(i -> type == null || i.getValue().equals(type)).map(i -> new AbstractMap.SimpleEntry<UUID, String>(UUID.fromString(i.getKey()), i.getValue())).collect(Collectors.toList());
			listIndex = 0;
			return 0;
		});
	}

	/**
	 * Reads the next entry in the list of components.
	 *
	 * The internal iterator is advanced to the next entry only on a successful
	 * call with {@code buffer} set to a non-null value.
	 *
	 * @param buffer The buffer to write the binary UUID address into, which
	 * must be 16 bytes long.
	 * @return 1 on success, 0 if there is no next entry, or {@link
	 * ErrorCode#MEMORY_FAULT} if the buffer is invalid.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int listNext(final int buffer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(list == null || listIndex >= list.size()) {
				return 0;
			}
			MemoryUtils.writeUUID(memory, buffer, list.get(listIndex).getKey());
			++listIndex;
			return 1;
		});
	}

	/**
	 * Reads the component type of the most recent component returned by {@link
	 * #listNext}.
	 *
	 * @param buffer The buffer to write the type into, or null to return the
	 * required buffer length.
	 * @param length The length of the buffer.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; {@link
	 * ErrorCode#QUEUE_EMPTY} if {@link #listNext} has not been called since
	 * the start of the program or since the last call to {@link #listStart};
	 * or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#BUFFER_TOO_SHORT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int listType(final int buffer, final int length) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(list == null || listIndex == 0) {
				return ErrorCode.QUEUE_EMPTY.asNegative();
			}
			return WasmString.toWasm(memory, buffer, length, list.get(listIndex - 1).getValue());
		});
	}

	/**
	 * Looks up the type of a component.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @param buffer The buffer to write the type into, or null to return the
	 * required buffer length.
	 * @param bufferLength The length of the buffer.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; or one of {@link
	 * ErrorCode#MEMORY_FAULT}, {@link ErrorCode#BUFFER_TOO_SHORT}, or {@link
	 * ErrorCode#NO_SUCH_COMPONENT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int type(final int addressPointer, final int buffer, final int bufferLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final UUID address = MemoryUtils.readUUID(memory, addressPointer);
			final String type = machine.components().get(address.toString());
			if(type == null) {
				return ErrorCode.NO_SUCH_COMPONENT.asNegative();
			}
			return WasmString.toWasm(memory, buffer, bufferLength, type);
		});
	}

	/**
	 * Determines which slot a component is installed into.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @return The slot number in the computer that contains the component;
	 * {@link ErrorCode#OTHER} if the component exists but is not installed in
	 * a slot; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#STRING_DECODE}, or {@link ErrorCode#NO_SUCH_COMPONENT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int slot(final int addressPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final String address = MemoryUtils.readUUID(memory, addressPointer).toString();
			if(machine.components().containsKey(address)) {
				final int slot = machine.host().componentSlot(address);
				return slot >= 0 ? slot : ErrorCode.OTHER.asNegative();
			} else {
				return ErrorCode.NO_SUCH_COMPONENT.asNegative();
			}
		});
	}

	/**
	 * Begins iterating over the methods available on a component.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @return 0 on success; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#STRING_DECODE}, or {@link ErrorCode#NO_SUCH_COMPONENT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int methodsStartComponent(final int addressPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final li.cil.oc.api.network.Component component = getComponent(addressPointer);
			final Map<String, Callback> snapshot = machine.methods(component.host());
			methods = snapshot.entrySet().stream().map(MethodInfo::new).collect(Collectors.toCollection(() -> new ArrayList<MethodInfo>(snapshot.size())));
			methodsIndex = 0;
			return 0;
		});
	}

	/**
	 * Begins iterating over the methods available on an opaque value.
	 *
	 * The special methods (indexed-read aka apply, indexed-write aka unapply,
	 * and call) are not included in the list.
	 *
	 * @param descriptor The descriptor of the opaque value to examine.
	 * @return 0 on success, or {@link ErrorCode#BAD_DESCRIPTOR}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int methodsStartValue(final int descriptor) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			try(ValueReference value = descriptors.get(descriptor)) {
				final Map<String, Callback> snapshot = machine.methods(value.get());
				methods = snapshot.entrySet().stream().map(MethodInfo::new).collect(Collectors.toCollection(() -> new ArrayList<MethodInfo>(snapshot.size())));
			}
			methodsIndex = 0;
			return 0;
		});
	}

	/**
	 * Returns the next method in the list of methods for a component or opaque
	 * value.
	 *
	 * The defined method attributes are:
	 * <ul>
	 * <li>0x1: the method is direct</li>
	 * <li>0x2: the method is a property getter</li>
	 * <li>0x4: the method is a property setter</li>
	 * </ul>
	 *
	 * @param buffer The buffer to write the method name into, or null to
	 * return the required buffer length.
	 * @param length The length of the buffer.
	 * @param attributesPointer A pointer to an {@code i32} into which to write
	 * the method attributes, or null to discard the method attributes.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; 0 if there are no
	 * more methods to list; or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#BUFFER_TOO_SHORT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int methodsNext(final int buffer, final int length, final int attributesPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(methods == null) {
				return 0;
			}
			final MethodInfo entry = methods.get(methodsIndex);
			final int written = WasmString.toWasm(memory, buffer, length, entry.name);
			MemoryUtils.writeOptionalI32(memory, attributesPointer, entry.attributes);
			if(buffer != 0) {
				final int newIndex = methodsIndex + 1;
				if(newIndex == methods.size()) {
					methods = null;
				} else {
					methodsIndex = newIndex;
				}
			}
			return written;
		});
	}

	/**
	 * Fetches the documentation for a method on a component.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @param methodPointer A pointer to a string which is the name of the
	 * method to query.
	 * @param methodLength The length of the method name string.
	 * @param buffer The buffer to write the documentation string into, or null
	 * to return the required buffer length.
	 * @param bufferLength The length of the buffer.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; or one of {@link
	 * ErrorCode#MEMORY_FAULT}, {@link ErrorCode#BUFFER_TOO_SHORT}, {@link
	 * ErrorCode#NO_SUCH_COMPONENT}, or {@link ErrorCode#NO_SUCH_METHOD}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int documentationComponent(final int addressPointer, final int methodPointer, final int methodLength, final int buffer, final int bufferLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final li.cil.oc.api.network.Component component = getComponent(addressPointer);
			final String method = WasmString.toJava(memory, methodPointer, methodLength);
			final Callback cb = machine.methods(component.host()).get(method);
			if(cb == null) {
				return ErrorCode.NO_SUCH_METHOD.asNegative();
			}
			return WasmString.toWasm(memory, buffer, bufferLength, cb.doc());
		});
	}

	/**
	 * Fetches the documentation for a method on an opaque value.
	 *
	 * @param descriptor The descriptor of the opaque value to examine.
	 * @param methodPointer A pointer to a string which is the name of the
	 * method to query.
	 * @param methodLength The length of the method name string.
	 * @param buffer The buffer to write the documentation string into, or null
	 * to return the required buffer length.
	 * @param bufferLength The length of the buffer.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; or one of {@link
	 * ErrorCode#MEMORY_FAULT}, {@link ErrorCode#BUFFER_TOO_SHORT}, {@link
	 * ErrorCode#NO_SUCH_METHOD}, or {@link ErrorCode#BAD_DESCRIPTOR}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int documentationValue(final int descriptor, final int methodPointer, final int methodLength, final int buffer, final int bufferLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			try(ValueReference value = descriptors.get(descriptor)) {
				final String method = WasmString.toJava(memory, methodPointer, methodLength);
				final Callback cb = machine.methods(value.get()).get(method);
				if(cb == null) {
					return ErrorCode.NO_SUCH_METHOD.asNegative();
				}
				return WasmString.toWasm(memory, buffer, bufferLength, cb.doc());
			}
		});
	}

	/**
	 * Starts invoking a method on a component.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @param methodPointer A pointer to a string which is the name of the
	 * method to invoke.
	 * @param methodLength The length of the method name string.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the method, or null if no parameters are needed.
	 * @return 1 if the call completed; 0 if the call has started but will not
	 * finish until the next timeslice; {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#CBOR_DECODE}, {@link ErrorCode#STRING_DECODE}, or {@link
	 * ErrorCode#TOO_MANY_DESCRIPTORS}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeComponentMethod(final int addressPointer, final int methodPointer, final int methodLength, final int paramsPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> invokeCommon(() -> {
			final UUID address = MemoryUtils.readUUID(memory, addressPointer);
			final String method = WasmString.toJava(memory, methodPointer, methodLength);
			final MethodCallParameters params = new MethodCallParameters(paramsPointer);
			return new MethodCall.Component(address, method, params.params, params.values);
		}));
	}

	/**
	 * Invokes the call special method on a callable opaque value.
	 *
	 * This syscall invokes the special method “call” on the opaque value. It
	 * is not used for invoking regular methods.
	 *
	 * @param descriptor The descriptor of the opaque value to call.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the special method, or null if no parameters are needed.
	 * @return 1 if the call completed; {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#CBOR_DECODE}, {@link ErrorCode#NO_SUCH_METHOD}, {@link
	 * ErrorCode#BAD_DESCRIPTOR}, or {@link ErrorCode#TOO_MANY_DESCRIPTORS}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeValue(final int descriptor, final int paramsPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> invokeValueSpecialCommon(descriptor, paramsPointer, MethodCall.ValueSpecial.Method.CALL));
	}

	/**
	 * Invokes the indexed-read special method on an opaque value.
	 *
	 * This syscall invokes the special method “indexed-read”, also known as
	 * “apply”, on the opaque value. It is not used for invoking regular
	 * methods.
	 *
	 * @param descriptor The descriptor of the opaque value to indexed-read.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the special method, or null if no parameters are needed.
	 * @return 1 if the call completed; {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#CBOR_DECODE}, {@link ErrorCode#BAD_DESCRIPTOR}, or {@link
	 * ErrorCode#TOO_MANY_DESCRIPTORS}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeValueIndexedRead(final int descriptor, final int paramsPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> invokeValueSpecialCommon(descriptor, paramsPointer, MethodCall.ValueSpecial.Method.APPLY));
	}

	/**
	 * Invokes the indexed-write special method on an opaque value.
	 *
	 * This syscall invokes the special method “indexed-write”, also known as
	 * “unapply”, on the opaque value. It is not used for invoking regular
	 * methods.
	 *
	 * @param descriptor The descriptor of the opaque value to indexed-write.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the special method, or null if no parameters are needed.
	 * @return 1 if the call completed; {@link ErrorCode#QUEUE_FULL} if a
	 * previous call’s results have not yet been read via a call to {@link
	 * #invokeEnd}; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#CBOR_DECODE}, {@link ErrorCode#BAD_DESCRIPTOR}, or {@link
	 * ErrorCode#TOO_MANY_DESCRIPTORS}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeValueIndexedWrite(final int descriptor, final int paramsPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> invokeValueSpecialCommon(descriptor, paramsPointer, MethodCall.ValueSpecial.Method.UNAPPLY));
	}

	/**
	 * Starts invoking a regular method on an opaque value.
	 *
	 * @param descriptor The descriptor of the opaque value on which to invoke
	 * the method.
	 * @param methodPointer A pointer to a string which is the name of the
	 * method to invoke.
	 * @param methodLength The length of the method name string.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the method, or null if no parameters are needed.
	 * @return 1 if the call completed; 0 if the call has started but will not
	 * finish until the next timeslice; {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#CBOR_DECODE}, {@link ErrorCode#STRING_DECODE}, {@link
	 * ErrorCode#BAD_DESCRIPTOR}, or {@link ErrorCode#TOO_MANY_DESCRIPTORS}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeValueMethod(final int descriptor, final int methodPointer, final int methodLength, final int paramsPointer) throws WrappedException {
		return SyscallWrapper.wrap(() -> invokeCommon(() -> {
			try(ValueReference target = descriptors.get(descriptor)) {
				final String method = WasmString.toJava(memory, methodPointer, methodLength);
				final MethodCallParameters params = new MethodCallParameters(paramsPointer);
				return new MethodCall.ValueRegular(target, method, params.params, params.values);
			}
		}));
	}

	/**
	 * Finishes invoking a method by fetching the result of the call.
	 *
	 * The call must have been started via {@link #invokeComponentMethod},
	 * {@link #invokeValue}, {@link #invokeValueIndexedRead}, {@link
	 * #invokeValueIndexedWrite}, or {@link #invokeValueMethod}. Once the call
	 * completes, its result can be fetched by exactly once. Return codes fetch
	 * (and therefore consume) or do not fetch the call result as follows,
	 * where the first applicable table entry determines the outcome:
	 * <table>
	 * <caption>Consumption of method call results</caption>
	 * <tr><th>Condition</th><th>Result fetched?</th></tr>
	 * <tr><td>{@link ErrorCode#MEMORY_FAULT}</td><td>No</td></tr>
	 * <tr><td>{@link ErrorCode#BUFFER_TOO_SHORT}</td><td>No</td></tr>
	 * <tr><td>{@link ErrorCode#NO_SUCH_COMPONENT}</td><td>Yes</td></tr>
	 * <tr><td>{@link ErrorCode#NO_SUCH_METHOD}</td><td>Yes</td></tr>
	 * <tr><td>{@link ErrorCode#BAD_PARAMETERS}</td><td>Yes</td></tr>
	 * <tr><td>{@link ErrorCode#QUEUE_EMPTY}</td><td>N/A (result does not exist)</td></tr>
	 * <tr><td>{@link ErrorCode#BAD_DESCRIPTOR}</td><td>Yes</td></tr>
	 * <tr><td>{@link ErrorCode#OTHER}</td><td>Yes</td></tr>
	 * <tr><td>{@code buffer} is null</td><td>No</td></tr>
	 * <tr><td>Nonnegative return value</td><td>Yes</td></tr>
	 * </table>
	 *
	 * @param buffer The buffer to write the CBOR-encoded call result array
	 * into, or null to return the required buffer length.
	 * @param length The length of the buffer.
	 * @return The length of the CBOR array written to {@code buffer} on
	 * success; the required buffer length, if {@code buffer} is null; {@link
	 * ErrorCode#MEMORY_FAULT} if the memory area referred to by {@code buffer}
	 * and {@code length} is invalid; {@link ErrorCode#QUEUE_EMPTY} if no call
	 * has been submitted and not yet fetched or if a call has been submitted
	 * but has not yet finished; {@link ErrorCode#BUFFER_TOO_SHORT} if the
	 * buffer is too short to hold the call result; or one of {@link
	 * ErrorCode#NO_SUCH_COMPONENT}, {@link ErrorCode#NO_SUCH_METHOD}, {@link
	 * ErrorCode#BAD_PARAMETERS}, {@link ErrorCode#BAD_DESCRIPTOR}, or {@link
	 * ErrorCode#OTHER} if the method call failed for the specified reason.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int invokeEnd(final int buffer, final int length) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			// If there is no call result, report the error.
			if(callResult == null) {
				return ErrorCode.QUEUE_EMPTY.asNegative();
			}

			// If the call failed, report the error code from the call and
			// consider that to have consumed the result.
			if(callResult.errorCode != null) {
				final ErrorCode e = callResult.errorCode;
				callResult = null;
				return e.asNegative();
			}

			// Copy the CBOR bytes to the buffer.
			final byte[] bytes = encodeCallResult();
			MemoryUtils.writeOptionalBytes(memory, buffer, length, bytes);
			if(buffer != 0) {
				callResult = null;
			}
			return bytes.length;
		});
	}

	/**
	 * Cancels an in-progress method call.
	 *
	 * If a method call has been started by {@link #invokeComponentMethod},
	 * {@link #invokeValue}, {@link #invokeValueIndexedRead}, {@link
	 * #invokeValueIndexedWrite}, or {@link #invokeValueMethod} and has not yet
	 * completed, the method call does not happen. If the method call has
	 * completed but its result has not yet been fetched via {@link
	 * #invokeEnd}, the result is discarded.
	 *
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public void invokeCancel() throws WrappedException {
		SyscallWrapper.wrap(() -> {
			if(pendingCall != null) {
				pendingCall.close();
				pendingCall = null;
			}
			if(callResult != null) {
				// CallResult objects can only be cancelled if they are
				// encoded, so make sure that is the case.
				encodeCallResult();
				callResult.cancel(descriptors);
				callResult = null;
			}
			return 0;
		});
	}

	/**
	 * Checks whether the component module requires an indirect call.
	 *
	 * @return {@code true} if an indirect call is needed, or {@code false} if
	 * not.
	 */
	public boolean needsRunSynchronized() {
		return pendingCall != null && callResult == null;
	}

	/**
	 * Checks whether the component module has a call result.
	 *
	 * @return {@code true} if a call result is waiting to be fetched, or
	 * {@code false} if not.
	 */
	public boolean hasCallResult() {
		return callResult != null;
	}

	/**
	 * Performs an indirect call, if needed.
	 */
	public void runSynchronized() {
		if(pendingCall != null && callResult == null) {
			// Invoke the method.
			final Object[] result;
			try {
				result = pendingCall.invokeIndirect(machine);
			} catch(final SyscallErrorException exp) {
				// The invocation failed. Save the error code.
				callResult = new CallResult(exp.errorCode());
				// In this case there are no results, and there is no need to
				// keep holding onto the call.
				pendingCall.close();
				pendingCall = null;
				return;
			}
			// The invocation succeeded. Save the result. Do not CBOR-encode it
			// right now, as that would take precious server-thread time;
			// instead, leave it in object-graph form and let the computer
			// thread CBOR-encode it later. *HOWEVER*, consider that it is
			// possible that the method call contained an opaque value
			// parameter, and that was the only refcounted reference to that
			// opaque value. Now consider that the method call could possibly
			// have returned that value in its result. If we close the call, we
			// will dispose that value, even though we are going to return it
			// in the results. We don’t want that. Instead, keep pendingCall
			// around for now, until the computer thread CBOR-encodes the
			// result (and thereby allocates descriptors for opaque values).
			callResult = new CallResult(result);
		}
	}

	/**
	 * Returns the CBOR encoding of {@link #callResult}.
	 *
	 * @return The CBOR encoding, or {@code null} if {@link #callResult} is
	 * {@code null} or contains an error code instead of a call result.
	 */
	private byte[] encodeCallResult() {
		if(callResult != null) {
			// Encode the call result.
			final byte[] cbor;
			try(DescriptorTable.Allocator alloc = descriptors.new Allocator()) {
				cbor = callResult.encode(alloc);
				alloc.commit();
			}

			// Once we are certain callResult has been encoded and is in CBOR
			// form, it is then safe to drop methodCall.
			if(pendingCall != null) {
				pendingCall.close();
				pendingCall = null;
			}

			return cbor;
		} else {
			return null;
		}
	}

	/**
	 * Prepares the syscall modules prior to running user code on the computer
	 * thread.
	 */
	public void preRunThreaded() {
		// Consider the following sequence of events:
		// 1. Opaque value V is held in exactly one location, descriptor 0.
		// 2. User code starts a method call to an indirect method.
		// 3. User code returns.
		// 4. The method call is executed on the server thread. It returns V,
		//    which is stashed in callResult. Because this is the server
		//    thread, callResult is not immediately CBOR-encoded.
		// 5. User code is invoked for its next timeslice.
		// 6. User code closes descriptor 0.
		// 7. Descriptor 0 was the only refcounted reference to V, so V is
		//    disposed.
		// 8. User code calls invokeEnd, which allocates a descriptor for V and
		//    returns it in the CBOR-encoded result.
		//
		// Now user code has a descriptor to V, which has been disposed! This
		// would be bad. To avoid it, we ensure that user code can never
		// execute while a non-CBOR-encoded callResult exists (if the
		// callResult has been CBOR-encoded everything is fine because that
		// would allocate a second descriptor for V). For direct calls this is
		// accomplished by eagerly CBOR-encoding right at the point of method
		// call. For indirect calls we don’t want to waste precious
		// server-thread time on CBOR encoding; however, we also know that user
		// code cannot be running at the same time, so we can just force
		// callResult to be CBOR-encoded right before starting the user code.
		encodeCallResult();
	}

	/**
	 * Given a component UUID, finds the component.
	 *
	 * @param addressPointer A pointer to a binary UUID address of a component.
	 * @return The identified component.
	 * @throws MemoryFaultException If the memory area is invalid.
	 * @throws StringDecodeException If the string is invalid.
	 * @throws NoSuchComponentException If the component does not exist or is not
	 * visible from this computer.
	 */
	private li.cil.oc.api.network.Component getComponent(final int addressPointer) throws MemoryFaultException, StringDecodeException, NoSuchComponentException {
		return ComponentUtils.getComponent(machine, MemoryUtils.readUUID(memory, addressPointer).toString());
	}

	/**
	 * Invokes a special method on an opaque value.
	 *
	 * @param descriptor The descriptor of the opaque value to call.
	 * @param paramsPointer A pointer to a CBOR array of parameters to pass to
	 * the call, or null if no parameters are needed.
	 * @param method The special method to invoke.
	 * @return 1 if the call completed, 0 if the call has started but will not
	 * finish until the next timeslice, {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}, or {@link ErrorCode#TOO_MANY_DESCRIPTORS} if
	 * there are too many descriptors.
	 * @throws SyscallErrorException If the system call failed in a way that is
	 * the Wasm module instance’s fault and should be reported during the start
	 * of invocation rather than the end of invocation.
	 */
	private int invokeValueSpecialCommon(final int descriptor, final int paramsPointer, final MethodCall.ValueSpecial.Method method) throws SyscallErrorException {
		return invokeCommon(() -> {
			try(ValueReference target = descriptors.get(descriptor)) {
				final MethodCallParameters params = new MethodCallParameters(paramsPointer);
				return new MethodCall.ValueSpecial(target, method, params.params, params.values);
			}
		});
	}

	/**
	 * Invokes a method.
	 *
	 * @param builder The builder that builds the {@link MethodCall} object to
	 * invoke.
	 * @return 1 if the call completed, 0 if the call has started but will not
	 * finish until the next timeslice, {@link ErrorCode#QUEUE_FULL} if a
	 * previous method invocation’s results have not yet been read via a call
	 * to {@link #invokeEnd}, or {@link ErrorCode#TOO_MANY_DESCRIPTORS} if
	 * there are too many descriptors.
	 * @throws SyscallErrorException If {@code builder} threw this exception.
	 */
	private int invokeCommon(final MethodCallBuilder builder) throws SyscallErrorException {
		// Check that there isn’t one pending already.
		if(pendingCall != null || callResult != null) {
			return ErrorCode.QUEUE_FULL.asNegative();
		}

		// Check that the descriptor table isn’t overfull.
		if(descriptors.overfull()) {
			return ErrorCode.TOO_MANY_DESCRIPTORS.asNegative();
		}

		// Build the MethodCall object.
		final MethodCall call = builder.build();

		// Try to invoke it directly, otherwise pend it.
		try {
			final Object[] result = call.invokeDirect(machine);
			// The call completed. If we left the result in object-graph form,
			// it’s possible it might contain opaque values in the form of
			// Value objects. If one of those Value objects was the same object
			// that was passed as a parameter, the caller could then close the
			// descriptor they used in the call. If they did that, the value
			// would be disposed, because they just closed the only
			// reference-counted reference to it. That would be bad, because
			// the value is actually in the method return. To avoid that,
			// CBOR-encode the result immediately, thus pushing such objects
			// into the descriptor table.
			callResult = new CallResult(result);
			try(DescriptorTable.Allocator alloc = descriptors.new Allocator()) {
				callResult.encode(alloc);
				alloc.commit();
			}
			// Dispose the completed call.
			call.close();
			return 1;
		} catch(final InProgressException exp) {
			// The call can’t be made now; it must be made indirectly. Hold
			// onto the call and don’t dispose it.
			pendingCall = call;
			return 0;
		} catch(final SyscallErrorException exp) {
			// Exceptions on *the call itself*, as opposed to on gathering the
			// parameters, should be reported via invokeEnd instead.
			callResult = new CallResult(exp.errorCode());
			// Dispose the failed call.
			call.close();
			return 1;
		}
	}

	/**
	 * Saves the {@code Component} into an NBT structure.
	 *
	 * @param valuePool The value pool to use to save opaque values.
	 * @param descriptorAlloc A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @return The created NBT tag.
	 */
	public NBTTagCompound save(final ValuePool valuePool, final DescriptorTable.Allocator descriptorAlloc) {
		final NBTTagCompound root = new NBTTagCompound();
		if(list != null) {
			final NBTTagList listNBT = new NBTTagList();
			list.stream().forEachOrdered(i -> {
				listNBT.appendTag(new NBTTagString(i.getKey().toString()));
				listNBT.appendTag(new NBTTagString(i.getValue()));
			});
			root.setTag(NBT_LIST, listNBT);
		}
		root.setInteger(NBT_LIST_INDEX, listIndex);
		if(methods != null) {
			final NBTTagList methodsNBT = new NBTTagList();
			methods.stream().skip(methodsIndex).forEachOrdered(i -> methodsNBT.appendTag(i.save()));
		}
		// Don’t save methodsIndex; it is implicitly saved by skipping that
		// many leading elements of methods in the previous block.

		// Save callResult before saving pendingCall. This is not strictly
		// necessary but it is an optimization: the Supplier<byte[]> in
		// callResult may destroy pendingCall (see runSynchronized for why). If
		// that happens, saving callResult first means we don’t have to save
		// pendingCall at all.
		if(callResult != null) {
			// callResult might not have been encoded yet. It can only be saved
			// if it’s encoded. Encode it now.
			try(DescriptorTable.Allocator alloc = descriptors.new Allocator()) {
				callResult.encode(alloc);
				alloc.commit();
			}
			root.setTag(NBT_CALL_RESULT, callResult.save());
		}
		if(pendingCall != null) {
			root.setTag(NBT_PENDING_CALL, pendingCall.save(valuePool, descriptorAlloc));
		}
		return root;
	}

	/**
	 * Destroys the state stored in this syscall module.
	 *
	 * This method may create descriptors in the descriptor table, which should
	 * themselves be closed afterwards.
	 */
	public void close() {
		// If we have a call result that is not yet CBOR-encoded, it may
		// contain opaque values for which we have no descriptor yet. To ensure
		// that every opaque value is disposed, but only once, the easiest
		// thing to do is simply CBOR-encode the call result. That pushes all
		// opaque values into the descriptor table, which already knows how to
		// dispose them properly.
		encodeCallResult();

		// If we have a pending call, close it.
		if(pendingCall != null) {
			pendingCall.close();
		}
	}
}
