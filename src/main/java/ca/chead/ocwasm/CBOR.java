package ca.chead.ocwasm;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.AbstractFloat;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.DoublePrecisionFloat;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SinglePrecisionFloat;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import li.cil.oc.api.machine.Value;

/**
 * Handles encoding and decoding CBOR data items.
 */
public final class CBOR {
	/**
	 * The tag for a CBOR Identifier.
	 */
	private static final long IDENTIFIER_TAG = 39;

	/**
	 * Converts a sequence of Java objects into a sequence of CBOR data items.
	 *
	 * @param objects The objects to convert, each of which must be one of the
	 * understood types.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @return The CBOR encoding.
	 */
	public static byte[] toCBORSequence(final Stream<Object> objects, final DescriptorTable.Allocator descriptorAllocator) {
		try {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			toCBORSequence(baos, objects, descriptorAllocator);
			return baos.toByteArray();
		} catch(final IOException exp) {
			throw new RuntimeException("Impossible I/O error occurred", exp);
		}
	}

	/**
	 * Converts a sequence of Java objects into a sequence of CBOR data items,
	 * writing the CBOR data items to an output stream.
	 *
	 * On failure, the descriptor table is not modified.
	 *
	 * @param target The stream to write to.
	 * @param objects The objects to write.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @throws IOException If a write to {@code target} fails.
	 */
	private static void toCBORSequence(final OutputStream target, final Stream<Object> objects, final DescriptorTable.Allocator descriptorAllocator) throws IOException {
		Objects.requireNonNull(target);
		Objects.requireNonNull(objects);
		Objects.requireNonNull(descriptorAllocator);

		final CborEncoder enc = new CborEncoder(target);
		final CborException[] exps = new CborException[]{null};
		objects.forEachOrdered(i -> {
			try {
				enc.encode(toDataItem(i, descriptorAllocator));
			} catch(final CborException e) {
				if(exps[0] == null) {
					exps[0] = e;
				}
			}
		});
		final CborException exp = exps[0];
		if(exp != null) {
			final Throwable cause = exp.getCause();
			if(cause instanceof IOException) {
				throw (IOException) cause;
			} else {
				throw new RuntimeException("CBOR encoding error (this is an OC-Wasm bug)", exp);
			}
		}
	}

	/**
	 * Converts a Java object to a CBOR data item.
	 *
	 * @param object The object to convert.
	 * @param descriptorAlloc A descriptor table allocator to use to allocate
	 * descriptors for any opaque values encountered.
	 * @return The CBOR data item.
	 */
	private static DataItem toDataItem(final Object object, final DescriptorTable.Allocator descriptorAlloc) {
		if(object == null) {
			return SimpleValue.NULL;
		} else if(object instanceof Boolean) {
			return ((Boolean) object) ? SimpleValue.TRUE : SimpleValue.FALSE;
		} else if(object instanceof Byte || object instanceof Short || object instanceof Integer || object instanceof Long) {
			final long value = ((Number) object).longValue();
			return (value >= 0) ? new UnsignedInteger(value) : new NegativeInteger(value);
		} else if(object instanceof Float) {
			return new SinglePrecisionFloat((Float) object);
		} else if(object instanceof Double) {
			return new DoublePrecisionFloat((Double) object);
		} else if(object instanceof Value) {
			final int descriptor = descriptorAlloc.add((Value) object);
			final DataItem ret = new UnsignedInteger(descriptor);
			ret.setTag(new Tag(IDENTIFIER_TAG));
			return ret;
		} else if(object instanceof String) {
			return new UnicodeString((String) object);
		} else if(object instanceof byte[]) {
			return new ByteString((byte[]) object);
		} else if(object.getClass().isArray()) {
			final int length = java.lang.reflect.Array.getLength(object);
			final Array ret = new Array();
			for(int i = 0; i != length; ++i) {
				ret.add(toDataItem(java.lang.reflect.Array.get(object, i), descriptorAlloc));
			}
			return ret;
		} else if(object instanceof Iterable) {
			final Array ret = new Array();
			for(final Object i : ((Iterable) object)) {
				ret.add(toDataItem(i, descriptorAlloc));
			}
			return ret;
		} else if(object instanceof java.util.Map) {
			final Map ret = new Map();
			for(final java.util.Map.Entry<?, ?> i : ((java.util.Map<?, ?>) object).entrySet()) {
				ret.put(toDataItem(i.getKey(), descriptorAlloc), toDataItem(i.getValue(), descriptorAlloc));
			}
			return ret;
		} else {
			throw new RuntimeException("Unable to CBOR-encode object of type " + object.getClass() + " (this is an OC-Wasm bug or limitation)");
		}
	}

	/**
	 * Converts a sequence of CBOR data items to an array of Java objects.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @return The objects.
	 * @throws CBORDecodeException If the data in {@code source} is not a
	 * sequence of valid CBOR data items or one of the items is of an
	 * unsupported type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	public static Object[] toJavaSequence(final ByteBuffer source, final DescriptorTable descriptorTable) throws CBORDecodeException, BadDescriptorException {
		return toJavaSequence(source, descriptorTable, descriptor -> { });
	}

	/**
	 * Converts a sequence of CBOR data items to an array of Java objects.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @return The objects.
	 * @throws CBORDecodeException If the data in {@code source} is not a
	 * sequence of valid CBOR data items or one of the items is of an
	 * unsupported type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	public static Object[] toJavaSequence(final ByteBuffer source, final DescriptorTable descriptorTable, final IntConsumer descriptorListener) throws CBORDecodeException, BadDescriptorException {
		Objects.requireNonNull(source);
		Objects.requireNonNull(descriptorTable);
		Objects.requireNonNull(descriptorListener);

		if(source.remaining() == 0) {
			return OCWasm.ZERO_OBJECTS;
		}

		final List<DataItem> items;
		try {
			items = new CborDecoder(ByteBufferInputStream.wrap(source)).decode();
		} catch(final CborException exp) {
			throw new CBORDecodeException();
		}
		return toJavaObjects(items, descriptorTable, descriptorListener);
	}

	/**
	 * Converts a list of CBOR data items into an array of Java objects.
	 *
	 * @param items The items to convert.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @return An array of Java objects corresponding to the given CBOR data
	 * items.
	 * @throws CBORDecodeException If one of the items is not of a convertible
	 * type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	private static Object[] toJavaObjects(final List<DataItem> items, final DescriptorTable descriptorTable, final IntConsumer descriptorListener) throws CBORDecodeException, BadDescriptorException {
		final Object[] ret = new Object[items.size()];
		final Iterator<DataItem> iter = items.iterator();
		for(int index = 0; index != ret.length; ++index) {
			ret[index] = toJavaObject(iter.next(), descriptorTable, descriptorListener);
		}
		return ret;
	}

	/**
	 * Converts a CBOR data item into a Java object.
	 *
	 * @param item The item to convert.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @return A Java object corresponding to the given CBOR data item.
	 * @throws CBORDecodeException If the item is not of a convertible type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	private static Object toJavaObject(final DataItem item, final DescriptorTable descriptorTable, final IntConsumer descriptorListener) throws CBORDecodeException, BadDescriptorException {
		final Tag tag = item.getTag();
		if(tag != null) {
			if(tag.getValue() == IDENTIFIER_TAG) {
				if(tag.hasTag()) {
					throw new CBORDecodeException();
				}
				if(item instanceof co.nstant.in.cbor.model.Number) {
					final int descriptor;
					try {
						descriptor = ((co.nstant.in.cbor.model.Number) item).getValue().intValueExact();
					} catch(final ArithmeticException exp) {
						throw new CBORDecodeException();
					}
					descriptorListener.accept(descriptor);
					try(ValueReference ref = descriptorTable.get(descriptor)) {
						// TODO keep the ref for longer.
						return ref.get();
					}
				} else {
					throw new CBORDecodeException();
				}
			} else {
				throw new CBORDecodeException();
			}
		} else if(item instanceof Array) {
			return toJavaObjects(((Array) item).getDataItems(), descriptorTable, descriptorListener);
		} else if(item instanceof ByteString) {
			return ((ByteString) item).getBytes();
		} else if(item instanceof Map) {
			final Map src = (Map) item;
			final HashMap<Object, Object> dest = new HashMap<Object, Object>();
			final Iterator<DataItem> keyIter = src.getKeys().iterator();
			final Iterator<DataItem> valueIter = src.getValues().iterator();
			while(keyIter.hasNext()) {
				final Object key = toJavaObject(keyIter.next(), descriptorTable, descriptorListener);
				if(key instanceof String || key instanceof java.lang.Number || key instanceof Boolean) {
					final Object value = toJavaObject(valueIter.next(), descriptorTable, descriptorListener);
					dest.put(key, value);
				} else {
					// Map keys must only be strings, numbers, or booleans.
					throw new CBORDecodeException();
				}
			}
			return dest;
		} else if(item instanceof co.nstant.in.cbor.model.Number) {
			try {
				return ((co.nstant.in.cbor.model.Number) item).getValue().intValueExact();
			} catch(final ArithmeticException exp) {
				throw new CBORDecodeException();
			}
		} else if(item instanceof AbstractFloat) {
			return ((AbstractFloat) item).getValue();
		} else if(item instanceof DoublePrecisionFloat) {
			return ((DoublePrecisionFloat) item).getValue();
		} else if(item instanceof SimpleValue) {
			switch(((SimpleValue) item).getSimpleValueType()) {
				case FALSE: return false;
				case TRUE: return true;
				case NULL: return null;
				case UNDEFINED: return null;
				case RESERVED: return null;
				case UNALLOCATED: return null;
				default: throw new RuntimeException("Impossible simple CBOR value type " + ((SimpleValue) item).getSimpleValueType());
			}
		} else if(item instanceof UnicodeString) {
			return ((UnicodeString) item).getString();
		}
		throw new CBORDecodeException();
	}

	private CBOR() {
	}
}
