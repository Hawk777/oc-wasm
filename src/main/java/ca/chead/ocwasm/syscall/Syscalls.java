package ca.chead.ocwasm.syscall;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.DescriptorTable;
import ca.chead.ocwasm.ReferencedValue;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.ValuePool;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants.NBT;

/**
 * All the syscall modules that are available for a Wasm module to import.
 */
public final class Syscalls {
	/**
	 * The name of the NBT tag that holds the opaque value pool.
	 *
	 * This tag is a list of compounds. Each compound contains keys {@link
	 * #NBT_VALUE_CLASS_KEY} and {@link #NBT_VALUE_DATA_KEY}.
	 */
	private static final String NBT_VALUES = "ca.chead.ocwasm.values";

	/**
	 * The name of the NBT tag that holds the {@link #descriptors} field.
	 */
	private static final String NBT_DESCRIPTORS = "ca.chead.ocwasm.descriptors";

	/**
	 * The name of the NBT tag that holds the {@link #component} field.
	 */
	private static final String NBT_COMPONENT = "ca.chead.ocwasm.syscall.component";

	/**
	 * The name of the NBT tag that holds the {@link #computer} field.
	 */
	private static final String NBT_COMPUTER = "ca.chead.ocwasm.syscall.computer";

	/**
	 * The descriptors.
	 */
	private final DescriptorTable descriptors;

	/**
	 * The {@code component} module.
	 */
	public final Component component;

	/**
	 * The {@code computer} module.
	 */
	public final Computer computer;

	/**
	 * The {@code descriptor} module.
	 */
	public final Descriptor descriptor;

	/**
	 * The {@code execute} module.
	 */
	public final Execute execute;

	/**
	 * A list of all the modules.
	 */
	public final List<Object> modules;

	/**
	 * Initializes the syscalls for a binary.
	 *
	 * @param machine The machine.
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 */
	public Syscalls(final Machine machine, final CPU cpu, final ByteBuffer memory) {
		super();
		Objects.requireNonNull(machine);
		Objects.requireNonNull(cpu);
		Objects.requireNonNull(memory);
		descriptors = new DescriptorTable(machine);
		component = new Component(machine, memory, descriptors);
		computer = new Computer(machine, cpu, memory, descriptors);
		descriptor = new Descriptor(descriptors, component);
		execute = new Execute(cpu, memory);
		modules = Collections.unmodifiableList(Arrays.asList(new Object[]{component, computer, descriptor, execute}));
	}

	/**
	 * Loads a {@code Syscalls} that was previously saved.
	 *
	 * @param machine The machine on which the module operates.
	 * @param cpu The CPU.
	 * @param memory The linear memory.
	 * @param root The compound that was previously returned from {@link
	 * #save}.
	 * @param snapshot The snapshot data that is being loaded.
	 */
	public Syscalls(final Machine machine, final CPU cpu, final ByteBuffer memory, final NBTTagCompound root, final Snapshot snapshot) {
		super();
		Objects.requireNonNull(machine);
		Objects.requireNonNull(cpu);
		Objects.requireNonNull(memory);
		final ReferencedValue[] valuePool = ValuePool.load(machine, root.getTagList(NBT_VALUES, NBT.TAG_COMPOUND));
		descriptors = new DescriptorTable(machine, valuePool, root.getCompoundTag(NBT_DESCRIPTORS));
		component = new Component(machine, memory, valuePool, descriptors, root.getCompoundTag(NBT_COMPONENT));
		computer = new Computer(machine, cpu, memory, descriptors, root.getCompoundTag(NBT_COMPUTER));
		descriptor = new Descriptor(descriptors, component);
		execute = new Execute(cpu, memory, snapshot.executeBuffer);
		modules = Collections.unmodifiableList(Arrays.asList(new Object[]{component, computer, descriptor, execute}));
	}

	/**
	 * Saves the {@code Syscalls} into an NBT structure.
	 *
	 * @param root The root NBT tag to add data to.
	 * @return The byte array holding the execution buffer from the {@link
	 * #execute} module, if the buffer contains any data.
	 */
	public Optional<byte[]> save(final NBTTagCompound root) {
		// Create a pool to hold opaque values. The pool can be referenced by
		// other parts of the system, but each value will appear only once.
		final ValuePool valuePool = new ValuePool();

		// Some of the syscall modules might allocate temporary descriptors in
		// order to persist references to opaque values that were held in their
		// internal state. We don’t want those descriptors to outlive the
		// save() invocation, so allocate them using a dedicated allocator
		// which we intentionally do *not* commit.
		try(DescriptorTable.Allocator descriptorAlloc = descriptors.new Allocator()) {
			// Save the syscall modules that use NBT first.
			root.setTag(NBT_COMPONENT, component.save(valuePool, descriptorAlloc));
			root.setTag(NBT_COMPUTER, computer.save());

			// Save the descriptor table last, now that the modules have had a
			// chance to add any temporary descriptors they need to preserve
			// their internal state.
			root.setTag(NBT_DESCRIPTORS, descriptors.save(valuePool));

			// Save the value pool, now that everyone has had a chance to put
			// values in it.
			root.setTag(NBT_VALUES, valuePool.save());
		}

		// Also save the state of the execute module, which only needs to
		// return a large byte array.
		return execute.save();
	}

	/**
	 * Destroys all state stored in the syscall modules and the descriptor
	 * table.
	 */
	public void close() {
		// Close the component module first, then the descriptors. This is
		// because the component module might create descriptors as a side
		// effect of closing itself, and those descriptors must be closed.
		component.close();
		descriptors.closeAll();
	}
}
