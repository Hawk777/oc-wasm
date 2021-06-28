package ca.chead.ocwasm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The result of a method call.
 */
public final class CallResult {
	/**
	 * The name of the NBT tag that holds the {@link #result} field.
	 *
	 * This tag is a byte array. It holds the CBOR-encoded form of the result.
	 * If the result is an error, the tag is absent.
	 */
	private static final String NBT_RESULT = "result";

	/**
	 * The name of the NBT tag that holds the {@link #descriptorsCreated}
	 * field.
	 *
	 * This tag is an integer array. If the result is an error or there are no
	 * descriptors, the tag may be absent.
	 */
	private static final String NBT_DESCRIPTORS_CREATED = "descriptorsCreated";

	/**
	 * The name of the NBT tag that holds the {@link #errorCode} field.
	 *
	 * This tag is an integer holding the ordinal. If the result is successful,
	 * the tag is absent.
	 */
	private static final String NBT_ERROR_CODE = "errorCode";

	/**
	 * The successful result, as either an array of {@code Object} or a
	 * CBOR-encoded array of {@code byte}; or {@code null} if the call failed.
	 */
	private Object result;

	/**
	 * The descriptors that have been added to the descriptor table as a result
	 * of CBOR-encoding this call result.
	 *
	 * If the call result is an error or has not been encoded yet, this list is
	 * empty.
	 */
	private final List<Integer> descriptorsCreated;

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
		descriptorsCreated = new ArrayList<Integer>();
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
		descriptorsCreated = Collections.emptyList();
		this.errorCode = Objects.requireNonNull(errorCode);
	}

	/**
	 * Loads a {@code CallResult} that was previously saved.
	 *
	 * @param root The tag that was previously returned from {@link #save}.
	 */
	public CallResult(final NBTTagCompound root) {
		super();
		result = root.hasKey(NBT_RESULT) ? root.getByteArray(NBT_RESULT) : null;
		if(root.hasKey(NBT_DESCRIPTORS_CREATED)) {
			descriptorsCreated = Collections.unmodifiableList(Arrays.stream(root.getIntArray(NBT_DESCRIPTORS_CREATED)).boxed().collect(Collectors.toList()));
		} else {
			descriptorsCreated = null;
		}
		if(root.hasKey(NBT_ERROR_CODE)) {
			final int ordinal = root.getInteger(NBT_ERROR_CODE);
			errorCode = Arrays.stream(ErrorCode.values()).filter(i -> i.ordinal() == ordinal).findAny().get();
		} else {
			errorCode = null;
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
			try(DescriptorTable.Allocator childAllocator = descriptorAllocator.createChild()) {
				final byte[] cbor = CBOR.toCBOR((Object[]) result, childAllocator);
				result = cbor;
				descriptorsCreated.addAll(childAllocator.getAllocatedDescriptors());
				childAllocator.commit();
				return cbor;
			}
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
	public NBTTagCompound save() {
		final NBTTagCompound root = new NBTTagCompound();
		if(result != null) {
			if(result instanceof byte[]) {
				root.setByteArray(NBT_RESULT, (byte[]) result);
			} else {
				throw new IllegalStateException("Unencoded CallResult objects cannot be saved");
			}
		}
		if(!descriptorsCreated.isEmpty()) {
			root.setIntArray(NBT_DESCRIPTORS_CREATED, descriptorsCreated.stream().mapToInt(i -> i).toArray());
		}
		if(errorCode != null) {
			root.setInteger(NBT_ERROR_CODE, errorCode.ordinal());
		}
		return root;
	}

	/**
	 * Cancels the {@code CallResult}.
	 *
	 * This should not be called if the operation completes normally, only if
	 * it is cancelled and the user code wishes to throw away the result rather
	 * than retrieving it.
	 *
	 * The {@code CallResult} must have been encoded.
	 *
	 * @param descriptors The descriptor table.
	 */
	public void cancel(final DescriptorTable descriptors) {
		if(result != null && !(result instanceof byte[])) {
			throw new IllegalStateException("Unencoded CallResult objects cannot be cancelled");
		}
		for(final int i : descriptorsCreated) {
			try {
				descriptors.close(i);
			} catch(final BadDescriptorException exp) {
				// The descriptors were created by the method call and have not
				// yet been exposed to the user application. If they were
				// already closed, that’s a bug in the user application: it
				// went and picked a random integer and closed it, without it
				// being an actual, legal descriptor. However, user code *can*
				// do that—it’s stupid but technically possible—so we shouldn’t
				// actually blow up the world here, just ignore the error.
			}
		}
	}
}
