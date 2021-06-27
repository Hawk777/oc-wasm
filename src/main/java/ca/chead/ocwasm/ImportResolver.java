package ca.chead.ocwasm;

import asmble.annotation.WasmExternalKind;
import asmble.annotation.WasmImport;
import ca.chead.ocwasm.syscall.Syscall;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.Type;

/**
 * Resolves named syscalls to methods in {@link MethodHandle} form.
 */
public final class ImportResolver {
	/**
	 * The syscall modules.
	 */
	private final List<Object> modules;

	/**
	 * Constructs a new {@code ImportResolver}.
	 *
	 * @param modules The syscall modules.
	 */
	public ImportResolver(final List<Object> modules) {
		super();
		this.modules = Objects.requireNonNull(modules);
	}

	/**
	 * Resolves an import.
	 *
	 * @param imp The import annotation.
	 * @return The method handle for the import.
	 * @throws LinkingException If {@code module} does not equal the lowercased
	 * name of the class of any syscall provided at constructions, if {@code
	 * function} does not equal the name of any method in the syscall module,
	 * or if the found method is not marked with the {@link Syscall}
	 * annotation.
	 */
	public MethodHandle resolve(final WasmImport imp) throws LinkingException {
		// We can only resolve imported functions, not any other kind of
		// import.
		if(imp.kind() != WasmExternalKind.FUNCTION) {
			throw new LinkingException("Imports of kind " + imp.kind() + " are not supported");
		}

		// To resolve the module, look for an object whose class name,
		// converted to lowercase, is equal to the module name.
		final Object moduleInstance = modules.stream().filter(i -> i.getClass().getSimpleName().toLowerCase().equals(imp.module())).findAny().orElse(null);
		if(moduleInstance == null) {
			throw new LinkingException("No such importable module " + imp.module());
		}

		// We have the API module; now try to find the method.
		final Method method = Arrays.stream(moduleInstance.getClass().getMethods()).filter(j -> j.getAnnotation(Syscall.class) != null).filter(j -> j.getName().equals(imp.field())).findAny().orElse(null);
		if(method == null) {
			throw new LinkingException("No such importable function " + imp.field() + " in module " + imp.module());
		}

		// Check that the parameters and return type match.
		final String actualDescriptor = Type.getMethodDescriptor(method);
		if(!actualDescriptor.equals(imp.desc())) {
			throw new LinkingException("Imported function " + imp.field() + " in module " + imp.module() + " has wrong descriptor: expected " + actualDescriptor + " but got " + imp.desc());
		}

		// Make the method handle.
		try {
			return MethodHandles.lookup().unreflect(method).bindTo(moduleInstance);
		} catch(final IllegalAccessException exp) {
			throw new RuntimeException("API class " + moduleInstance.getClass() + " method " + method + " is @Syscall but not public (this is an OC-Wasm bug)");
		}
	}
}
