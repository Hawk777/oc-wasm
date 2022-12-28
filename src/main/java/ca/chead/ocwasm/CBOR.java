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
import java.util.UUID;
import java.util.function.IntConsumer;
import li.cil.oc.api.machine.Value;

/**
 * Handles encoding and decoding CBOR data items.
 */
public final class CBOR {
	/**
	 * The tag for a CBOR binary-encoded UUID.
	 */
	private static final long UUID_TAG = 37;

	/**
	 * The tag for a CBOR Identifier.
	 */
	private static final long IDENTIFIER_TAG = 39;

	/**
	 * Converts a Java object into a CBOR data item.
	 *
	 * @param object The object to convert, which must be, and whose nested
	 * items must all be, of the understood types.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @return The CBOR encoding.
	 */
	public static byte[] toCBOR(final Object object, final DescriptorTable.Allocator descriptorAllocator) {
		try {
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			toCBOR(object, descriptorAllocator, baos);
			return baos.toByteArray();
		} catch(final IOException exp) {
			throw new RuntimeException("Impossible I/O error occurred", exp.getCause());
		}
	}

	/**
	 * Converts a Java object into a CBOR data item and writes it to a stream.
	 *
	 * @param object The object to convert, which must be, and whose nested
	 * items must all be, of the understood types.
	 * @param descriptorAllocator A descriptor allocator in which to allocate
	 * descriptors for any opaque values encountered.
	 * @param stream The stream to write the bytes to.
	 * @throws IOException If writing to the stream fails.
	 */
	public static void toCBOR(final Object object, final DescriptorTable.Allocator descriptorAllocator, final OutputStream stream) throws IOException {
		try {
			final CborEncoder enc = new CborEncoder(stream);
			enc.encode(toDataItem(object, descriptorAllocator));
		} catch(final CborException exp) {
			if(exp.getCause() instanceof IOException) {
				throw (IOException) exp.getCause();
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
		} else if(object instanceof Character) {
			return new UnicodeString(object.toString());
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
		} else if(object instanceof scala.collection.Map) {
			final Map ret = new Map();
			@SuppressWarnings("unchecked")
			final scala.collection.Map<Object, Object> map = (scala.collection.Map<Object, Object>) object;
			final scala.collection.Iterator<scala.Tuple2<Object, Object>> iter = map.iterator();
			while(iter.hasNext()) {
				final scala.Tuple2<Object, Object> entry = iter.next();
				ret.put(toDataItem(entry._1, descriptorAlloc), toDataItem(entry._2, descriptorAlloc));
			}
			return ret;
		} else {
			throw new RuntimeException("Unable to CBOR-encode object of type " + object.getClass() + " (this is an OC-Wasm bug or limitation)");
		}
	}

	/**
	 * Converts a CBOR data item to a Java array.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @return The object.
	 * @throws CBORDecodeException If the data in {@code source} is not a valid
	 * CBOR data item or the item or one of its nested items is of an
	 * unsupported type, or if it is valid but is not an array.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	public static Object[] toJavaArray(final ByteBuffer source, final DescriptorTable descriptorTable, final IntConsumer descriptorListener) throws CBORDecodeException, BadDescriptorException {
		final Object ret = toJavaObject(source, descriptorTable, descriptorListener);
		if(ret instanceof Object[]) {
			return (Object[]) ret;
		} else {
			throw new CBORDecodeException();
		}
	}

	/**
	 * Converts a CBOR data item to a Java object.
	 *
	 * @param source The bytes to read from.
	 * @param descriptorTable The descriptor table to use to resolve references
	 * to opaque values.
	 * @param descriptorListener A listener which is invoked and passed every
	 * descriptor encountered during conversion.
	 * @return The object.
	 * @throws CBORDecodeException If the data in {@code source} is not a valid
	 * CBOR data item or the item or one of its nested items is of an
	 * unsupported type.
	 * @throws BadDescriptorException If the data contains a reference to a
	 * descriptor, but the descriptor does not exist in the descriptor table.
	 */
	public static Object toJavaObject(final ByteBuffer source, final DescriptorTable descriptorTable, final IntConsumer descriptorListener) throws CBORDecodeException, BadDescriptorException {
		Objects.requireNonNull(source);
		Objects.requireNonNull(descriptorTable);
		Objects.requireNonNull(descriptorListener);

		if(source.remaining() == 0) {
			return null;
		}

		final DataItem item;
		try {
			item = new CborDecoder(ByteBufferInputStream.wrap(source)).decodeNext();
		} catch(final CborException exp) {
			throw new CBORDecodeException();
		}
		return toJavaObject(item, descriptorTable, descriptorListener);
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
				} else if(item instanceof ByteString) {
					return toJavaUUID((ByteString) item);
				} else {
					throw new CBORDecodeException();
				}
			} else if(tag.getValue() == UUID_TAG) {
				if(tag.hasTag()) {
					throw new CBORDecodeException();
				}
				if(item instanceof ByteString) {
					return toJavaUUID((ByteString) item);
				} else {
					throw new CBORDecodeException();
				}
			} else {
				throw new CBORDecodeException();
			}
		} else if(item instanceof Array) {
			final List<DataItem> items = ((Array) item).getDataItems();
			final Object[] objects = new Object[items.size()];
			for(int i = 0; i != objects.length; ++i) {
				objects[i] = toJavaObject(items.get(i), descriptorTable, descriptorListener);
			}
			return objects;
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
				final long l = ((co.nstant.in.cbor.model.Number) item).getValue().longValueExact();
				final int i = (int) l;
				if(i == l) {
					// The value is small enough to fit in an int, so return it
					// that way.
					return i;
				} else {
					// The value is too big for an int, so return it as a long.
					return l;
				}
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

	/**
	 * Converts a CBOR byte string into a UUID.
	 *
	 * @param item The item to convert.
	 * @return The decoded UUID, in string form.
	 * @throws CBORDecodeException If the item is the wrong length.
	 */
	private static String toJavaUUID(final ByteString item) throws CBORDecodeException {
		final ByteBuffer bb = ByteBuffer.wrap(item.getBytes());
		if(bb.remaining() == MemoryUtils.UUID_BYTES) {
			final long msw = bb.getLong();
			final long lsw = bb.getLong();
			return new UUID(msw, lsw).toString();
		} else {
			throw new CBORDecodeException();
		}
	}

	private CBOR() {
	}
}
