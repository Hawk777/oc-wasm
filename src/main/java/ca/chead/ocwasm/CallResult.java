package ca.chead.ocwasm;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagInt;

/**
 * The result of a method call.
 */
public final class CallResult {
	/**
	 * The successful result, or {@code null} if the call failed.
	 */
	public final Supplier<byte[]> result;

	/**
	 * The error code, or {@code null} if the call succeeded.
	 */
	public final ErrorCode errorCode;

	/**
	 * Constructs a {@code CallResult} for a successful method call.
	 *
	 * @param result The result of the method call.
	 */
	public CallResult(final Supplier<byte[]> result) {
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
			final byte[] bytes = ((NBTTagByteArray) root).getByteArray();
			this.result = () -> bytes;
			this.errorCode = null;
		} else {
			this.result = null;
			final int ordinal = ((NBTTagInt) root).getInt();
			this.errorCode = Arrays.stream(ErrorCode.values()).filter(i -> i.ordinal() == ordinal).findAny().get();
		}
	}

	/**
	 * Saves the {@code CallResult} into an NBT structure.
	 *
	 * @return The created NBT tag.
	 */
	public NBTBase save() {
		if(result != null) {
			return new NBTTagByteArray(result.get());
		} else {
			return new NBTTagInt(errorCode.ordinal());
		}
	}
}
