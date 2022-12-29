/**
 * This package contains the syscall modules that a WebAssembly binary can
 * import, plus some support classes used by them.
 * <p>
 * The following common data types are referred to by multiple syscalls and are
 * defined here:
 * <ul>
 * <li>A <dfn>pointer</dfn> is an {@code i32} with nonnegative value. The value
 * zero is interpreted as a null pointer. Nonzero values are interpreted as
 * offsets within the module’s linear memory.</li>
 * <li>A <dfn>length</dfn> is an {@code i32} with nonnegative value. It
 * specifies the length of the data in bytes. For values passed from Wasm
 * module to host, if the length accompanies an optional pointer and the
 * pointer is null, then the length is ignored. For values passed from host to
 * Wasm module as return values, negative numbers typically encode errors.</li>
 * <li>A <dfn>string</dfn> is a contiguous region of memory containing a UTF-8
 * sequence. The length of the string is indicated out-of-band, typically as an
 * additional parameter or a return value; no NUL or other terminator is used.
 * For values passed from host to Wasm module, the API call will write the
 * string into a module-provided buffer, then indicate the number of bytes
 * written. For values passed from Wasm module to host, as a convenience to
 * languages where NUL terminators are commonly used, the module may instead
 * elect to pass a negative number as the length, in which case the host will
 * search for a NUL terminator to determine string length.</li>
 * <li>A <dfn>CBOR object</dfn> is a contiguous region of memory containing a
 * Concise Binary Object Representation encoding of a single Data Item.
 * Individual occurrences will generally further constrain the type of data
 * item (e.g. “CBOR map”, “CBOR array”, etc.). CBOR objects may or may not be
 * accompanied by length information, as CBOR is self-delimiting. The host will
 * never write a CBOR data item using indefinite-length encoding (using a break
 * marker), but it will accept indefinite-length-encoded values from the Wasm
 * module. Values are converted between CBOR data items and Java objects
 * according to the rules in the {@link ca.chead.ocwasm.CBOR} class.</li>
 * </ul>
 *
 * Unless otherwise specified, all syscalls that involve memory access that
 * memory only during the syscall; once the syscall returns, the host will not
 * access the memory and the module can reuse it or free it.
 */
package ca.chead.ocwasm.syscall;
