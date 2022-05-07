package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.SyscallErrorException;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Detailed information about an error that occurred during component call
 * execution.
 */
public final class ComponentCallExceptionRecord {
	/**
	 * The name of the NBT tag that holds the {@link #exceptionClass} field.
	 */
	private static final String NBT_EXCEPTION_CLASS = "class";

	/**
	 * The name of the NBT tag that holds the {@link #message} field.
	 */
	private static final String NBT_MESSAGE = "message";

	/**
	 * The Java exception class that caused the error.
	 */
	private final Class<? extends Throwable> exceptionClass;

	/**
	 * The error message from the Java exception object.
	 */
	private final String message;

	/**
	 * Constructs a {@code ComponentCallExceptionRecord} from a system call
	 * exception.
	 *
	 * @param exp The exception to examine.
	 * @return The {@code ComponentCallExceptionRecord} object, if detailed
	 * error information is available, or null if not.
	 */
	public static ComponentCallExceptionRecord fromException(final SyscallErrorException exp) {
		final Throwable cause = exp.getCause();
		if(cause == null) {
			return null;
		} else {
			return new ComponentCallExceptionRecord(cause.getClass(), cause.getMessage());
		}
	}

	/**
	 * Loads error information that was previously saved.
	 *
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 * @return The {@code ComponentCallExceptionRecord} object, or null if the
	 * NBT data is malformed.
	 */
	public static ComponentCallExceptionRecord load(final NBTTagCompound root) {
		// Fetch strings from compound.
		final String exceptionClassName = root.getString(NBT_EXCEPTION_CLASS);
		final String message = root.getString(NBT_MESSAGE);
		if(exceptionClassName == null || message == null) {
			OCWasm.getLogger().error("Loading ComponentCallExceptionRecord object but missing tag in NBT");
			return null;
		}

		// Obtain exception class.
		final Class<?> exceptionClassUnchecked;
		try {
			exceptionClassUnchecked = Class.forName(exceptionClassName);
		} catch(final ClassNotFoundException exp) {
			return null;
		}

		// Downcast it to a Throwable.
		final Class<? extends Throwable> exceptionClass;
		try {
			exceptionClass = exceptionClassUnchecked.asSubclass(Throwable.class);
		} catch(final ClassCastException exp) {
			return null;
		}

		// Create the object.
		return new ComponentCallExceptionRecord(exceptionClass, message);
	}

	/**
	 * Saves the {@code ComponentCallExceptionRecord} into an NBT structure.
	 *
	 * @return The created NBT tag.
	 */
	public NBTTagCompound save() {
		final NBTTagCompound root = new NBTTagCompound();
		root.setString(NBT_EXCEPTION_CLASS, exceptionClass.getName());
		root.setString(NBT_MESSAGE, message);
		return root;
	}

	/**
	 * Checks whether the underlying exception is an instance of a specified
	 * class or a subclass thereof.
	 *
	 * @param candidate The name of the class to consider.
	 * @return {@code true} if the exception class is {@code candidate} or a
	 * subclass thereof, or {@code false} if that is not the case, including if
	 * {@code candidate} does not exist.
	 */
	public boolean isSubclassOf(final String candidate) {
		for(Class<?> considering = exceptionClass; considering != null; considering = considering.getSuperclass()) {
			if(considering.getName().equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the message of the underlying exception.
	 *
	 * @return The message.
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Constructs a new {@code ComponentCallExceptionRecord}.
	 *
	 * @param exceptionClass The exception class.
	 * @param message The exception message.
	 */
	private ComponentCallExceptionRecord(final Class<? extends Throwable> exceptionClass, final String message) {
		this.exceptionClass = exceptionClass;
		this.message = message;
	}
}
