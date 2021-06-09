package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.SnapshotOrGeneration;
import java.util.Objects;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The base class of all execution states.
 */
public abstract class State {
	/**
	 * The name of the NBT tag that holds the generation number.
	 *
	 * This tag is a {@code long}.
	 */
	public static final String NBT_GENERATION = "ca.chead.ocwasm.generation";

	/**
	 * An {@link li.cil.oc.api.machine.ExecutionResult} that sleeps for zero
	 * ticks.
	 */
	protected static final ExecutionResult SLEEP_ZERO = new ExecutionResult.Sleep(0);

	/**
	 * The CPU.
	 */
	protected final CPU cpu;

	/**
	 * The machine.
	 */
	protected final Machine machine;

	/**
	 * Constructs a new execution state.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 */
	protected State(final CPU cpu, final Machine machine) {
		super();
		this.cpu = Objects.requireNonNull(cpu);
		this.machine = Objects.requireNonNull(machine);
	}

	/**
	 * Returns whether to deliver {@code component_added} events and whether to
	 * place a budget on direct component calls in this state.
	 *
	 * @return {@code true} if OpenComputers should queue {@code
	 * component_added} events for this computer and should maintain a budget
	 * on direct component calls, or {@code false} if it should drop the events
	 * on the floor and allow unlimited direct component calls.
	 */
	public abstract boolean isInitialized();

	/**
	 * Runs the CPU in the computer thread.
	 *
	 * @return The state transition indicating the new state to move to and the
	 * OpenComputers execution result.
	 */
	public abstract Transition runThreaded();

	/**
	 * Runs the CPU in the server thread.
	 *
	 * The default implementation does nothing, which passes control back to
	 * {@link #runThreaded} on the computer thread without moving to a
	 * different state.
	 */
	public void runSynchronized() {
	}

	/**
	 * Takes a snapshot of the current execution state.
	 *
	 * @param root The machine NBT compound to write the execution state to.
	 * The subclass does <em>not</em> need to write the generation number into
	 * this compound.
	 * @return The snapshot, or the generation number of the existing snapshot
	 * to reuse.
	 */
	public abstract SnapshotOrGeneration snapshot(NBTTagCompound root);

	/**
	 * Destroys the state.
	 *
	 * <p>This method is called if the state is being destroyed for a reason
	 * other than a {@link Transition}. For example, this happens if the player
	 * elects to exit the world, if the containing chunk is unloaded, or if the
	 * computer shuts down or crashes. This method is <em>not</em> called if
	 * the state is dropped in favour of another state, due to the return value
	 * of {@link #runSynchronized} (including a transition to the {@code null}
	 * state); in this case, it is expected that the old state has destroyed
	 * anything it needs to destroy before returning from that method, while
	 * potentially also migrating some items into the new state which should
	 * not be destroyed.</p>
	 *
	 * <p>It is not necessary to thoroughly clear fields or drop references to
	 * memory here; after this method is called, the {@code State} object will
	 * be dropped and become garbage collectible anyway. This method is meant
	 * for cleaning up non-memory resources, such as I/O handles, that should
	 * not be left to the garbage collector.</p>
	 */
	public abstract void close();

	/**
	 * Begins loading a state that was previously saved.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param root The compound that was previously passed to {@link
	 * #snapshot}.
	 * @return The next state that the CPU should be in, to proceed with
	 * loading the snapshot.
	 */
	public static State load(final CPU cpu, final Machine machine, final NBTTagCompound root) {
		if(root.hasKey(NBT_GENERATION)) {
			return new LoadFromSnapshot(cpu, machine, root);
		} else {
			OCWasm.getLogger().error("Loading CPU state but no snapshot generation in NBT");
			return new FindEEPROM(cpu, machine);
		}
	}
}
