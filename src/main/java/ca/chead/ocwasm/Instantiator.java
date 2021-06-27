package ca.chead.ocwasm;

import asmble.annotation.WasmImport;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Instantiates a compiled Wasm module class.
 */
public final class Instantiator {
	/**
	 * The minimum legal number of constructor parameters.
	 *
	 * This is the linear memory, the instantiation listener, and the globals
	 * buffer.
	 */
	private static final int MIN_CTOR_PARAMETERS = 3;

	/**
	 * Instantiates a module.
	 *
	 * @param clazz The module class to instantiate.
	 * @param memory The linear memory to provide to the module instance.
	 * @param syscallModules The syscall modules to make available for import.
	 * @param listener The construction listener to pass into the module to be
	 * notified shortly after the object has been created, before the start
	 * function runs.
	 * @param globals A snapshot of the values of the mutable globals to
	 * restore, which must be positioned at the beginning of the data to read
	 * from, or an empty optional to run the start function instead.
	 * @return The module instance.
	 * @throws LinkingException If the module imports a function that is not
	 * available in the syscall registry.
	 * @throws WrappedException If the module start function calls a syscall
	 * import which fails internally.
	 * @throws ExecutionException If the module start function exceeds its
	 * timeslice or memory limit, or requests to shut down the computer.
	 */
	public static ModuleBase instantiate(final Class<? extends ModuleBase> clazz, final ByteBuffer memory, final List<Object> syscallModules, final ModuleConstructionListener listener, final Optional<ByteBuffer> globals) throws LinkingException, WrappedException, ExecutionException {
		// Sanity check.
		Objects.requireNonNull(clazz);
		Objects.requireNonNull(memory);
		Objects.requireNonNull(syscallModules);
		Objects.requireNonNull(listener);
		Objects.requireNonNull(globals);

		// Find the constructor.
		final Constructor<? extends ModuleBase> constructor = getOnlyConstructor(clazz);

		// Prepare an array of constructor parameters.
		final Object[] params = new Object[constructor.getParameterCount()];
		if(params.length < MIN_CTOR_PARAMETERS) {
			throw new IllegalArgumentException("Module constructor must take at least three parameters (this is an OC-Wasm bug)");
		}

		// The first parameter is the linear memory instance.
		params[0] = memory;

		// The last two parameters are the listener and the buffer of globals
		// to restore from.
		params[params.length - 2] = listener;
		params[params.length - 1] = globals.orElse(null);

		// The other parameters should be imports annotated with WasmImport
		// annotations indicating where they come from. Each should be provided
		// as a MethodHandle pointing to the relevant syscall.
		final ImportResolver resolver = new ImportResolver(syscallModules);
		final Annotation[][] annotations = constructor.getParameterAnnotations();
		for(int i = 1; i < params.length - 2; ++i) {
			if(params[i] == null) {
				final WasmImport importAnnotation = (WasmImport) Arrays.stream(annotations[i]).filter(j -> j instanceof WasmImport).findAny().orElseThrow(() -> new IllegalArgumentException("Missing WasmImport annotation on constructor parameter (this is an OC-Wasm bug)"));
				params[i] = resolver.resolve(importAnnotation);
			}
		}

		// Go!
		try {
			return constructor.newInstance(params);
		} catch(final InvocationTargetException exp) {
			final Throwable cause = exp.getCause();
			if(cause instanceof WrappedException) {
				throw (WrappedException) cause;
			} else if(cause instanceof ExecutionException) {
				throw (ExecutionException) cause;
			} else if(cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			} else if(cause instanceof Error) {
				throw (Error) cause;
			} else {
				// This should be impossible; only WrappedException,
				// ExexecutionException, or an unchecked exception should be
				// thrown from a module’s constructor.
				throw new RuntimeException("Impossible exception (this is an OC-Wasm bug)", cause);
			}
		} catch(final ReflectiveOperationException exp) {
			throw new RuntimeException("Failed to instantiate Wasm module (this is an OC-Wasm bug)", exp);
		}
	}

	/**
	 * Returns the only constructor of a class that has one constructor.
	 *
	 * @param clazz The class.
	 * @return The constructor.
	 */
	@SuppressWarnings("unchecked")
	private static Constructor<? extends ModuleBase> getOnlyConstructor(final Class<? extends ModuleBase> clazz) {
		final Constructor<?>[] constructors = clazz.getDeclaredConstructors();
		if(constructors.length != 1) {
			throw new IllegalArgumentException("Modules must have only one constructor (this is an OC-Wasm bug)");
		}
		// Safe because all elements of the array are Constructor<? extends
		// ModuleBase>, just the array itself can’t be because someone could
		// put a Constructor<SomethingElse> into an array of type Constructor
		// (but we didn’t, so it’s fine).
		return (Constructor<? extends ModuleBase>) constructors[0];
	}

	private Instantiator() {
	}
}
