package ca.chead.ocwasm;

import java.util.Arrays;
import java.util.Objects;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagInt;

/**
 * The result of a method call.
 */
public final class CallResult {
	/**
	 * The successful result, as either an array of {@code Object} or a
	 * CBOR-encoded array of {@code byte}; or {@code null} if the call failed.
	 */
	private Object result;

	/**
	 * The error code, or {@code null} if the call succeeded.
	 */
	public final ErrorCode errorCode;

	/**
	 * Constructs a {@code CallResult} for a successful method call.
	 *
	 * @param result The result of the method call.
	 */
	public CallResult(final Object[] result) {
		super();
		this.result = Objects.requireNonNull(result);
		errorCode = null;
	}

	/**
	 * Constructs a {@code CallResult} for a successful method call.
	 *
	 * @param result The CBOR-encoded result of the method call.
	 */
	public CallResult(final byte[] result) {
		super();
		this.result = Objects.requireNonNull(result);
		errorCode = null;
	}

	/**
	 * Constructs a {@code CallResult} for a failed method call.
	 *
	 * @param errorCode The error code.
	 */
	public CallResult(final ErrorCode errorCode) {
		super();
		result = null;
		this.errorCode = Objects.requireNonNull(errorCode);
	}

	/**
	 * Loads a {@code CallResult} that was previously saved.
	 *
	 * @param root The tag that was previously returned from {@link #save}.
	 */
	public CallResult(final NBTBase root) {
		super();
		if(root instanceof NBTTagByteArray) {
			result = ((NBTTagByteArray) root).getByteArray();
			errorCode = null;
		} else {
			result = null;
			final int ordinal = ((NBTTagInt) root).getInt();
			errorCode = Arrays.stream(ErrorCode.values()).filter(i -> i.ordinal() == ordinal).findAny().get();
		}
	}

	/**
	 * Encodes the {@code CallResult}.
	 *
	 * This method may be called more than once. The actual encoding is only
	 * done the first time; after that, the same value is returned repeatedly.
	 *
	 * @param descriptorAllocator An allocator to use to allocate descriptors
	 * for opaque values in the call result.
	 * @return The encoded form, or {@code null} if this object contains an
	 * error code instead of a result array.
	 */
	public byte[] encode(final DescriptorTable.Allocator descriptorAllocator) {
		if(result instanceof byte[]) {
			return (byte[]) result;
		} else if(result instanceof Object[]) {
			final byte[] cbor = CBOR.toCBORSequence(Arrays.stream((Object[]) result), descriptorAllocator);
			result = cbor;
			return cbor;
		} else {
			return null;
		}
	}

	/**
	 * Saves the {@code CallResult} into an NBT structure.
	 *
	 * The {@code CallResult} must have been encoded.
	 *
	 * @return The created NBT tag.
	 */
	public NBTBase save() {
		if(result != null) {
			if(result instanceof byte[]) {
				return new NBTTagByteArray((byte[]) result);
			} else {
				throw new IllegalStateException("Unencoded CallResult objects cannot be saved");
			}
		} else {
			return new NBTTagInt(errorCode.ordinal());
		}
	}
}
