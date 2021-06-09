package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.DescriptorTable;
import ca.chead.ocwasm.ErrorCode;
import ca.chead.ocwasm.WrappedException;
import java.util.Objects;
import li.cil.oc.api.machine.Value;

/**
 * The syscalls available for import into a Wasm module in the {@code
 * descriptor} component.
 *
 * Certain operations may return, and others may accept, what OC-Wasm terms
 * “opaque values”. In technical terms, these are instances of {@link
 * li.cil.oc.api.machine.Value}. Examples of opaque values are file handles
 * returned by the filesystem component, craftables or crafting status objects
 * returned by an adapter connected to an Applied Energistics network, or
 * sockets returned by the Internet card. Opaque values cannot be directly
 * stored in linear memory in the Wasm module instance. Instead, OC-Wasm
 * maintains a table of opaque values, keyed by small nonnegative 32-bit
 * integers referred to as <em>descriptors</em>. When a descriptor is passed
 * via CBOR, its integer data item is tagged with tag 39 (“identifier”). The
 * Wasm module instance is responsible for managing the lifetime of its
 * descriptors, closing each one when it is no longer needed by calling {@link
 * #close}.
 */
public final class Descriptor {
	/**
	 * The descriptors and the values they refer to.
	 */
	private final DescriptorTable descriptors;

	/**
	 * The {@code component} syscall module.
	 */
	private final Component component;

	/**
	 * Constructs a new {@code Descriptor}.
	 *
	 * @param descriptors The descriptor table for opaque values.
	 * @param component The {@code component} syscall module.
	 */
	public Descriptor(final DescriptorTable descriptors, final Component component) {
		super();
		this.descriptors = Objects.requireNonNull(descriptors);
		this.component = Objects.requireNonNull(component);
	}

	/**
	 * Closes a descriptor.
	 *
	 * If this was the last descriptor referring to a particular opaque value,
	 * the opaque value is disposed.
	 *
	 * This must only be invoked when no method call is in progress nor is a
	 * call result waiting for retrieval.
	 *
	 * @param descriptor The descriptor number to close.
	 * @return Zero on success; {@link ErrorCode#BAD_DESCRIPTOR} if the
	 * descriptor does not exist; or {@link ErrorCode#QUEUE_FULL} if a method
	 * call is in progress or a result has not yet been fetched.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int close(final int descriptor) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(component.canCloseDescriptor()) {
				descriptors.close(descriptor);
				return 0;
			} else {
				return ErrorCode.QUEUE_FULL.asNegative();
			}
		});
	}

	/**
	 * Duplicates a descriptor.
	 *
	 * The new descriptor refers to the exact same opaque value, not a copy of
	 * the value. All references to either descriptor behave in exactly the
	 * same way, but each descriptor can be closed independently. Only once all
	 * descriptors referring to the opaque value are closed will the value
	 * itself be disposed.
	 *
	 * @param descriptor The descriptor number to duplicate.
	 * @return The new descriptor number on success, {@link
	 * ErrorCode#BAD_DESCRIPTOR} if the descriptor does not exist, or {@link
	 * ErrorCode#TOO_MANY_DESCRIPTORS} if the descriptor table is full.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int dup(final int descriptor) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final Value value = descriptors.get(descriptor);
			if(descriptors.overfull()) {
				return ErrorCode.TOO_MANY_DESCRIPTORS.asNegative();
			}
			final DescriptorTable.Allocator alloc = descriptors.new Allocator();
			final int newDescriptor = alloc.add(value);
			alloc.commit();
			return newDescriptor;
		});
	}
}
