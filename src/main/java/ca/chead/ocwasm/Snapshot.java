package ca.chead.ocwasm;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.minecraftforge.common.DimensionManager;

/**
 * A snapshot of the larger parts of execution state, which can be saved to
 * disk.
 *
 * The smaller pieces of data are stored directly in NBT, but large items (like
 * memory images) are stored in a separate file managed by this class.
 */
public final class Snapshot {
	/**
	 * The random number generator used to generate generation numbers.
	 */
	private static final Random GENERATION_GENERATOR = new Random();

	/**
	 * The UUID of the computer.
	 */
	public final String computer;

	/**
	 * The generation number.
	 */
	public final long generation;

	/**
	 * The Wasm binary.
	 *
	 * This is absent if the computer is in a state where it doesn’t have a
	 * binary, such as when searching for an EEPROM.
	 */
	public final Optional<byte[]> binary;

	/**
	 * The values of the module’s mutable globals.
	 *
	 * This is absent if the computer is in a state where the module has not
	 * been instantiated yet.
	 */
	public final Optional<byte[]> globals;

	/**
	 * The contents of the module’s linear memory.
	 *
	 * This is absent if the computer is in a state where the module has not
	 * been instantiated yet.
	 */
	public final Optional<byte[]> memory;

	/**
	 * The contents of the execution buffer.
	 *
	 * This is absent if the execute buffer is empty.
	 */
	public final Optional<byte[]> executeBuffer;

	/**
	 * Constructs a {@code Snapshot}.
	 *
	 * @param computer The UUID of the computer.
	 * @param generation The generation number, or an empty optional to
	 * generate a random one.
	 * @param binary The Wasm binary, if one is loaded.
	 * @param globals The contents of the module’s mutable globals, if the
	 * module has been instantiated.
	 * @param memory The contents of the module’s linear memory, if the module
	 * has been instantiated.
	 * @param executeBuffer The contents of the execution buffer, if the
	 * execution buffer is in use.
	 */
	public Snapshot(final String computer, final OptionalLong generation, final Optional<byte[]> binary, final Optional<byte[]> globals, final Optional<byte[]> memory, final Optional<byte[]> executeBuffer) {
		super();
		this.computer = Objects.requireNonNull(computer);
		this.generation = generation.orElseGet(GENERATION_GENERATOR::nextLong);
		this.binary = Objects.requireNonNull(binary);
		this.globals = Objects.requireNonNull(globals);
		this.memory = Objects.requireNonNull(memory);
		this.executeBuffer = Objects.requireNonNull(executeBuffer);
	}

	/**
	 * Loads a {@code Snapshot} from disk.
	 *
	 * @param computer The UUID of the computer.
	 * @return The loaded snapshot.
	 * @throws IOException If the snapshot does not exist or cannot be loaded.
	 */
	public static Snapshot load(final String computer) throws IOException {
		final File snapshotFile = new File(snapshotDirectory(), computer);
		try(ZipFile zf = new ZipFile(snapshotFile)) {
			final ZipEntry generationEntry = zf.getEntry("generation.bin");
			if(generationEntry == null) {
				throw new IOException("generation.bin missing from snapshot");
			}
			final long generation;
			try(DataInputStream dis = new DataInputStream(zf.getInputStream(generationEntry))) {
				generation = dis.readLong();
			}

			final Optional<byte[]> binary = getEntry(zf, "binary.wasm");
			final Optional<byte[]> globals = getEntry(zf, "globals.bin");
			final Optional<byte[]> memory = getEntry(zf, "memory.bin");
			final Optional<byte[]> executeBuffer = getEntry(zf, "executeBuffer.bin");

			if(globals.isPresent() != memory.isPresent()) {
				throw new IOException("Snapshot has exactly one of globals and memory, expected both or neither");
			}
			if(!binary.isPresent() && globals.isPresent()) {
				throw new IOException("Snapshot has globals without binary");
			}
			if(!binary.isPresent() && executeBuffer.isPresent()) {
				throw new IOException("Snapshot has execution buffer without binary");
			}

			return new Snapshot(computer, OptionalLong.of(generation), binary, globals, memory, executeBuffer);
		}
	}

	/**
	 * Saves the {@code Snapshot} to disk.
	 *
	 * @throws IOException If the saving process fails.
	 */
	public void save() throws IOException {
		final File snapshotDir = snapshotDirectory();
		if(!snapshotDir.exists()) {
			if(!snapshotDir.mkdirs()) {
				throw new IOException("Failed to create snapshot directory " + snapshotDir);
			}
		}
		final File snapshotFile = new File(snapshotDir, computer);
		try(ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(snapshotFile.toPath())))) {
			zos.setMethod(ZipOutputStream.STORED);
			zos.setComment("Computer " + computer + " generation " + generation);

			final CRC32 crc = new CRC32();
			putEntry(zos, "generation.bin", Optional.of(longToBytes(generation)), crc);
			putEntry(zos, "binary.wasm", binary, crc);
			putEntry(zos, "globals.bin", globals, crc);
			putEntry(zos, "memory.bin", memory, crc);
			putEntry(zos, "executeBuffer.bin", executeBuffer, crc);
		}
	}

	/**
	 * Returns the directory that should be used to hold saved snapshots.
	 *
	 * @return The directory.
	 */
	private static File snapshotDirectory() {
		return new File(DimensionManager.getCurrentSaveRootDirectory(), "oc-wasm");
	}

	/**
	 * Reads an entry from a ZIP file.
	 *
	 * @param zf The ZIP file.
	 * @param name The filename.
	 * @return The contents of the entry, if present.
	 * @throws IOException If reading fails.
	 */
	private static Optional<byte[]> getEntry(final ZipFile zf, final String name) throws IOException {
		final ZipEntry entry = zf.getEntry(name);
		if(entry == null) {
			return Optional.empty();
		}
		if(entry.getSize() > Integer.MAX_VALUE) {
			throw new IOException("Snapshot entry too big");
		}
		try(DataInputStream ins = new DataInputStream(zf.getInputStream(entry))) {
			final byte[] result = new byte[(int) entry.getSize()];
			ins.readFully(result);
			return Optional.of(result);
		}
	}

	/**
	 * Adds an entry to a ZIP file.
	 *
	 * @param zos The ZIP file.
	 * @param name The filename.
	 * @param content The content to write, or an empty optional to omit the
	 * entry.
	 * @param crc A reusable CRC object to use to calculate the checksum of the
	 * entry.
	 * @throws IOException If writing fails.
	 */
	private static void putEntry(final ZipOutputStream zos, final String name, final Optional<byte[]> content, final CRC32 crc) throws IOException {
		final byte[] bytes = content.orElse(null);
		if(bytes != null) {
			crc.reset();
			crc.update(bytes);
			ZipEntry entry = new ZipEntry(name);
			entry.setSize(bytes.length);
			entry.setCompressedSize(bytes.length);
			entry.setCrc(crc.getValue());
			zos.putNextEntry(entry);
			zos.write(bytes);
			zos.closeEntry();
		}
	}

	/**
	 * Converts a {@code long} to a byte array.
	 *
	 * @param value The value to encode.
	 * @return The big-endian encoded value.
	 */
	private static byte[] longToBytes(final long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}
}
