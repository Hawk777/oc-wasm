package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.CBOR;
import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.CachingSupplier;
import ca.chead.ocwasm.DescriptorTable;
import ca.chead.ocwasm.ErrorCode;
import ca.chead.ocwasm.MemoryUtils;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.ShutdownException;
import ca.chead.ocwasm.SyscallErrorException;
import ca.chead.ocwasm.WasmString;
import ca.chead.ocwasm.WrappedException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.machine.Signal;
import li.cil.oc.api.network.Connector;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants.NBT;

/**
 * The syscalls available for import into a Wasm module in the {@code computer}
 * module.
 */
public final class Computer {
	/**
	 * The name of the NBT tag that holds the {@link #acl} field.
	 *
	 * This tag is a list of strings. It holds only those usernames that remain
	 * to be returned (i.e. those at {@link #aclIndex} and beyond), so {@link
	 * #aclIndex} is not stored.
	 */
	private static final String NBT_ACL = "acl";

	/**
	 * The name of the NBT tag that holds the {@link #poppedSignal} field.
	 *
	 * This tag is a byte array. It holds the CBOR-encoded form of the signal.
	 */
	private static final String NBT_POPPED_SIGNAL = "poppedSignal";

	/**
	 * The machine on which the module operates.
	 */
	private final Machine machine;

	/**
	 * The CPU.
	 */
	private final CPU cpu;

	/**
	 * The linear memory.
	 */
	private final ByteBuffer memory;

	/**
	 * The descriptors and the values they refer to.
	 */
	private final DescriptorTable descriptors;

	/**
	 * The snapshot of the computer’s access control list created when {@link
	 * #aclStart} was called.
	 */
	private String[] acl;

	/**
	 * The position within {@link #acl} of the next item to return.
	 */
	private int aclIndex;

	/**
	 * The signal that has been popped from the OpenComputers signal queue but
	 * not yet consumed by the Wasm module instance.
	 *
	 * If there is no such signal, this is {@code null}. If there is such a
	 * signal, it is a supplier that returns the signal’s CBOR encoding.
	 */
	private Supplier<byte[]> poppedSignal;

	/**
	 * Constructs a new {@code Computer}.
	 *
	 * @param machine The machine on which the module operates.
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 * @param descriptors The descriptor table.
	 */
	public Computer(final Machine machine, final CPU cpu, final ByteBuffer memory, final DescriptorTable descriptors) {
		super();
		this.machine = Objects.requireNonNull(machine);
		this.cpu = Objects.requireNonNull(cpu);
		this.memory = Objects.requireNonNull(memory);
		this.descriptors = Objects.requireNonNull(descriptors);
		acl = null;
		aclIndex = 0;
		poppedSignal = null;
	}

	/**
	 * Loads a {@code Computer} that was previously saved.
	 *
	 * @param machine The machine on which the module operates.
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 * @param descriptors The descriptor table.
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 */
	public Computer(final Machine machine, final CPU cpu, final ByteBuffer memory, final DescriptorTable descriptors, final NBTTagCompound root) {
		super();
		this.machine = Objects.requireNonNull(machine);
		this.cpu = Objects.requireNonNull(cpu);
		this.memory = Objects.requireNonNull(memory);
		this.descriptors = Objects.requireNonNull(descriptors);

		// Load acl.
		if(root.hasKey(NBT_ACL)) {
			final NBTTagList aclNBT = root.getTagList(NBT_ACL, NBT.TAG_STRING);
			acl = new String[aclNBT.tagCount()];
			for(int i = 0; i != acl.length; ++i) {
				acl[i] = aclNBT.getStringTagAt(i);
			}
		} else {
			acl = null;
		}

		// aclIndex does not need loading. It is always set to zero, because
		// acl excludes elements that have already been consumed.
		aclIndex = 0;

		// Load poppedSignal.
		if(root.hasKey(NBT_POPPED_SIGNAL)) {
			final byte[] poppedSignalNBT = root.getByteArray(NBT_POPPED_SIGNAL);
			poppedSignal = () -> poppedSignalNBT;
		} else {
			poppedSignal = null;
		}
	}

	/**
	 * Returns the amount of world time the computer has been running.
	 *
	 * @return The uptime, in seconds.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public double uptime() throws WrappedException {
		try {
			return machine.upTime();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Returns the amount of CPU time that the computer has consumed.
	 *
	 * @return The CPU time, in seconds.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public double cpuTime() throws WrappedException {
		try {
			return machine.cpuTime();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Returns the current in-game time and date.
	 *
	 * @return The real time, in game ticks.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public long worldTime() throws WrappedException {
		try {
			return machine.worldTime();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Returns the computer’s own UUID component address.
	 *
	 * @param buffer The buffer to write the binary UUID address into, which
	 * must be 16 bytes long.
	 * @return 0 on success, or {@link ErrorCode#MEMORY_FAULT} if the buffer is
	 * invalid.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int address(final int buffer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			MemoryUtils.writeUUID(memory, buffer, UUID.fromString(machine.node().address()));
			return 0;
		});
	}

	/**
	 * Returns the UUID component address of a filesystem component that lives
	 * until the computer shuts down and can be used to hold temporary files.
	 *
	 * @param buffer The buffer to write the binary UUID address into, which
	 * must be 16 bytes long.
	 * @return 0 on success, or {@link ErrorCode#MEMORY_FAULT} if the buffer is
	 * invalid.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int tmpfsAddress(final int buffer) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			MemoryUtils.writeUUID(memory, buffer, UUID.fromString(machine.tmpAddress()));
			return 0;
		});
	}

	/**
	 * Returns the amount of RAM installed in the computer.
	 *
	 * @return The amount of installed RAM, in bytes.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int installedRAM() throws WrappedException {
		return SyscallWrapper.wrap(() -> cpu.getInstalledRAM());
	}

	/**
	 * Pushes a signal to the signal queue.
	 *
	 * The parameters in the {@code params} array may be a mix of numbers,
	 * strings, and maps containing the preceding types.
	 *
	 * @param namePointer A pointer to a string containing the name of the
	 * signal.
	 * @param nameLength The length of the name string.
	 * @param paramsPointer A pointer to a CBOR sequence containing the signal
	 * parameters, or null to not include any parameters.
	 * @param paramsLength The length of the params sequence.
	 * @return 0 on success; {@link ErrorCode#QUEUE_FULL} if the computer’s
	 * signal queue is full; or one of {@link ErrorCode#MEMORY_FAULT}, {@link
	 * ErrorCode#STRING_DECODE}, or {@link ErrorCode#CBOR_DECODE}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int pushSignal(final int namePointer, final int nameLength, final int paramsPointer, final int paramsLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			final String name = WasmString.toJava(memory, namePointer, nameLength);
			final ByteBuffer paramsBuffer = MemoryUtils.region(memory, paramsPointer, paramsLength);
			final Object[] params = CBOR.toJavaSequence(paramsBuffer, descriptors);
			return machine.signal(name, params) ? 0 : ErrorCode.QUEUE_FULL.asNegative();
		});
	}

	/**
	 * Pops a signal from the signal queue.
	 *
	 * On success with non-null {@code buffer}, the signal is popped from the
	 * queue and written to {@code buffer} as a CBOR sequence, the first
	 * element of the sequence being the signal name and the remaining elements
	 * (if any) being the additional parameters. On success with a null {@code
	 * buffer}, or on failure, the signal is not popped and will be returned on
	 * the next call.
	 *
	 * @param buffer The buffer to write the signal data into, or null to
	 * return the required buffer length.
	 * @param length The length of the buffer.
	 * @return The length of the CBOR sequence written to {@code buffer} on
	 * success; the required buffer length, if {@code buffer} is null; zero,
	 * if there are no signals pending; or one of {@link
	 * ErrorCode#MEMORY_FAULT} or {@link ErrorCode#BUFFER_TOO_SHORT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int pullSignal(final int buffer, final int length) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(poppedSignal == null) {
				final Signal signal = machine.popSignal();
				if(signal != null) {
					poppedSignal = new CachingSupplier<byte[]>(() -> {
						try(DescriptorTable.Allocator alloc = descriptors.new Allocator()) {
							final byte[] cbor = CBOR.toCBORSequence(Stream.concat(Stream.of(signal.name()), Arrays.stream(signal.args())), alloc);
							alloc.commit();
							return cbor;
						}
					});
				}
			}
			if(poppedSignal == null) {
				return 0;
			}
			final byte[] bytes = poppedSignal.get();
			MemoryUtils.writeOptionalBytes(memory, buffer, length, bytes);
			if(buffer != 0) {
				poppedSignal = null;
			}
			return bytes.length;
		});
	}

	/**
	 * Takes a snapshot of the computer’s access control list and positions
	 * iteration at the beginning of the snapshot.
	 *
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public void aclStart() throws WrappedException {
		SyscallWrapper.wrap(() -> {
			final String[] snapshot = machine.users();
			if(snapshot == null || snapshot.length == 0) {
				acl = null;
			} else {
				acl = snapshot;
			}
			aclIndex = 0;
			return 0;
		});
	}

	/**
	 * Reads the next entry in the snapshot of the computer’s access control
	 * list.
	 *
	 * The internal iterator is advanced to the next entry only on a successful
	 * call with {@code buffer} set to a non-null value.
	 *
	 * @param buffer The buffer to write the username into, or null to return
	 * the required buffer length.
	 * @param length The length of the buffer.
	 * @return The length of the string written to {@code buffer} on success;
	 * the required buffer length, if {@code buffer} is null; zero, if there
	 * are no more users in the ACL; or one of {@link ErrorCode#MEMORY_FAULT}
	 * or {@link ErrorCode#BUFFER_TOO_SHORT}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int aclNext(final int buffer, final int length) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(acl == null) {
				return 0;
			}
			final int written = WasmString.toWasm(memory, buffer, length, acl[aclIndex]);
			if(buffer != 0) {
				final int newIndex = aclIndex + 1;
				if(newIndex == acl.length) {
					acl = null;
				} else {
					aclIndex = newIndex;
				}
			}
			return written;
		});
	}

	/**
	 * Grants a user access to the computer.
	 *
	 * @param namePointer A pointer to a string containing the Minecraft
	 * username of the player to grant access to.
	 * @param nameLength The length of the name string.
	 * @return 0 on success; {@link ErrorCode#OTHER} if adding the user fails;
	 * or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#STRING_DECODE}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int addUser(final int namePointer, final int nameLength) throws WrappedException {
		try {
			machine.addUser(WasmString.toJava(memory, namePointer, nameLength));
			return 0;
		} catch(final SyscallErrorException exp) {
			return exp.errorCode().asNegative();
		} catch(final Exception exp) {
			return ErrorCode.OTHER.asNegative();
		}
	}

	/**
	 * Revokes a user’s access to the computer.
	 *
	 * @param namePointer A pointer to a string containing the Minecraft
	 * username of the player to revoke access from.
	 * @param nameLength The length of the name string.
	 * @return 0 on success; {@link ErrorCode#OTHER} if the user does not
	 * appear on the ACL; or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#STRING_DECODE}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int removeUser(final int namePointer, final int nameLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			if(machine.removeUser(WasmString.toJava(memory, namePointer, nameLength))) {
				return 0;
			} else {
				return ErrorCode.OTHER.asNegative();
			}
		});
	}

	/**
	 * Returns the amount of energy stored in the computer and its network.
	 *
	 * @return The energy available to use.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public double energy() throws WrappedException {
		try {
			return ((Connector) machine.node()).globalBuffer();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Returns the maximum amount of energy that can be stored in the computer
	 * and its network.
	 *
	 * @return The energy storage capacity.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public double maxEnergy() throws WrappedException {
		try {
			return ((Connector) machine.node()).globalBufferSize();
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Returns the width of a Unicode character.
	 *
	 * @param ch The character, as a Unicode code point.
	 * @return The width, as a count of terminal columns.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int charWidth(final int ch) throws WrappedException {
		return SyscallWrapper.wrap(() -> OCWasm.getCharacterWidth(ch));
	}

	/**
	 * Plays a single beep.
	 *
	 * @param frequency The frequency of the beep, which is clamped to lie
	 * between 0 and 32,767.
	 * @param duration The length of the beep, in milliseconds, which is
	 * clamped to lie between 0 and 32,767.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public void beep(final int frequency, final int duration) throws WrappedException {
		SyscallWrapper.wrap(() -> {
			final short frequencyClamped = (short) Math.max(0, Math.min(frequency, Short.MAX_VALUE));
			final short durationClamped = (short) Math.max(0, Math.min(duration, Short.MAX_VALUE));
			machine.beep(frequencyClamped, durationClamped);
			return 0;
		});
	}

	/**
	 * Plays a series of beeps.
	 *
	 * @param patternPointer A pointer to a string containing the Morse code
	 * beep pattern.
	 * @param patternLength The length of the pattern string.
	 * @return 0 on success, or one of {@link ErrorCode#MEMORY_FAULT} or {@link
	 * ErrorCode#STRING_DECODE}.
	 * @throws WrappedException If the implementation fails.
	 */
	@Syscall
	public int beepPattern(final int patternPointer, final int patternLength) throws WrappedException {
		return SyscallWrapper.wrap(() -> {
			machine.beep(WasmString.toJava(memory, patternPointer, patternLength));
			return 0;
		});
	}

	/**
	 * Shuts down the computer.
	 *
	 * This function does not return to the Wasm module instance.
	 *
	 * @throws ShutdownException Always.
	 */
	@Syscall
	public void shutdown() throws ShutdownException {
		throw new ShutdownException("Shutdown requested", ShutdownException.Mode.POWER_OFF);
	}

	/**
	 * Reboots the computer.
	 *
	 * This function does not return to the Wasm module instance.
	 *
	 * @throws ShutdownException Always.
	 */
	@Syscall
	public void reboot() throws ShutdownException {
		throw new ShutdownException("Reboot requested", ShutdownException.Mode.REBOOT);
	}

	/**
	 * Halts the computer with an error.
	 *
	 * This function does not return to the Wasm module instance.
	 *
	 * @param errorPointer A pointer to a string containing the error message.
	 * @param errorLength The length of the error message.
	 * @throws WrappedException If the implementation fails.
	 * @throws ShutdownException Always.
	 */
	@Syscall
	public void error(final int errorPointer, final int errorLength) throws ShutdownException, WrappedException {
		try {
			throw new ShutdownException(WasmString.toJava(memory, errorPointer, errorLength), ShutdownException.Mode.ERROR);
		} catch(final SyscallErrorException exp) {
			throw new ShutdownException(exp.getMessage(), ShutdownException.Mode.ERROR);
		} catch(final RuntimeException exp) {
			throw new WrappedException(exp);
		}
	}

	/**
	 * Checks whether the computer has a popped signal.
	 *
	 * @return {@code true} if there is a signal stashed that has been popped
	 * from the OpenComputers signal queue and not yet fully delivered to the
	 * Wasm module instance, or {@code false} if not.
	 */
	public boolean hasPoppedSignal() {
		return poppedSignal != null;
	}

	/**
	 * Saves the {@code Computer} into an NBT structure.
	 *
	 * @return The created NBT tag.
	 */
	public NBTTagCompound save() {
		final NBTTagCompound root = new NBTTagCompound();
		if(acl != null && aclIndex < acl.length) {
			final NBTTagList aclNBT = new NBTTagList();
			for(int i = aclIndex; i != acl.length; ++i) {
				aclNBT.appendTag(new NBTTagString(acl[i]));
			}
			root.setTag(NBT_ACL, aclNBT);
		}
		// Don’t save aclIndex; it is implicitly saved by skipping that many
		// leading elements of acl in the previous block.
		if(poppedSignal != null) {
			root.setByteArray(NBT_POPPED_SIGNAL, poppedSignal.get());
		}
		return root;
	}
}
