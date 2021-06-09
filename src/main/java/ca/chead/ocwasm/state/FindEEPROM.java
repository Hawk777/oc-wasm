package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.SnapshotOrGeneration;
import java.util.Optional;
import java.util.OptionalLong;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The state the CPU is in when it is trying to find an EEPROM containing an
 * initial Wasm binary to execute.
 */
public final class FindEEPROM extends State {
	/**
	 * Constructs a new {@code FindEEPROM} state.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 */
	public FindEEPROM(final CPU cpu, final Machine machine) {
		super(cpu, machine);
	}

	@Override
	public boolean isInitialized() {
		return false;
	}

	@Override
	public Transition runThreaded() {
		final String eepromUUID = machine.components().entrySet().stream().filter(i -> i.getValue().equals("eeprom")).map(i -> i.getKey()).findAny().orElse(null);
		if(eepromUUID == null) {
			return new Transition(null, new ExecutionResult.Error("No EEPROM found"));
		}
		final Object[] result;
		try {
			result = machine.invoke(eepromUUID, "get", OCWasm.ZERO_OBJECTS);
		} catch(final Exception exp) {
			return new Transition(null, new ExecutionResult.Error("I/O error reading EEPROM"));
		}
		if(result.length != 1 || !(result[0] instanceof byte[])) {
			return new Transition(null, new ExecutionResult.Error("I/O error reading EEPROM"));
		}
		final byte[] binary = (byte[]) result[0];
		return new Transition(new Compile(cpu, machine, binary, Optional.empty(), Optional.empty()), SLEEP_ZERO);
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound root) {
		return new SnapshotOrGeneration(new Snapshot(machine.node().address(), OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
	}

	@Override
	public void close() {
	}
}
