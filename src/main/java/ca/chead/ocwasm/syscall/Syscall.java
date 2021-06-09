package ca.chead.ocwasm.syscall;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies methods that can be imported into Wasm modules as syscalls.
 *
 * This annotation must be attached to each importable method. The import
 * “module name” will be set to the name of the containing class converted to
 * lowercase, while the “entity name” will be equal to the name of the method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Syscall {
}
