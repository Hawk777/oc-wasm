package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.Compiler;
import ca.chead.ocwasm.ExceptionTranslator;
import ca.chead.ocwasm.ExecuteBinaryException;
import ca.chead.ocwasm.Instantiator;
import ca.chead.ocwasm.ModuleBase;
import ca.chead.ocwasm.ModuleConstructionListener;
import ca.chead.ocwasm.OCWasm;
import ca.chead.ocwasm.Snapshot;
import ca.chead.ocwasm.SnapshotOrGeneration;
import ca.chead.ocwasm.syscall.Syscalls;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import li.cil.oc.api.machine.ExecutionResult;
import li.cil.oc.api.machine.Machine;
import net.minecraft.nbt.NBTTagCompound;

/**
 * The state the CPU is in when it needs to instantiate a compiled Wasm module
 * for a fresh boot or launching of a new binary.
 */
public final class InstantiateClean extends State implements ModuleConstructionListener {
	/**
	 * The compiler output.
	 */
	private final Compiler.Result compileResult;

	/**
	 * The linear memory to provide to the module.
	 *
	 * This is {@code null} until early in {@link #runThreaded}, prior to
	 * module construction starting.
	 */
	private ByteBuffer memory;

	/**
	 * The scheduled task to set the timeout field in the running instance.
	 *
	 * This is {@code null} until part way through module construction. {@link
	 * #instanceConstructed} creates the future and saves it here. After
	 * construction finishes, the future is cancelled in {@link #runThreaded}.
	 */
	private Future<?> timeoutFuture;

	/**
	 * Constructs a new {@code InstantiateClean}.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param compileResult The compiler output.
	 */
	public InstantiateClean(final CPU cpu, final Machine machine, final Compiler.Result compileResult) {
		super(cpu, machine);
		this.compileResult = Objects.requireNonNull(compileResult);
		memory = null;
		timeoutFuture = null;
	}

	@Override
	public int freeMemory() {
		return cpu.getInstalledRAM();
	}

	@Override
	public boolean isInitialized() {
		// Within this state, we may run the user-provided Wasm start function;
		// if that function makes any direct calls, they should be limited by
		// the direct call budget.
		return true;
	}

	@Override
	public Transition runThreaded() {
		// Create the memory. The (int) cast is safe because, while the second
		// parameter could be larger than Integer.MAX_VALUE, it will be clamped
		// by the first (cpu.getInstalledRAM()) which cannot be.
		final int maxMemSize = (int) Math.min(cpu.getInstalledRAM(), compileResult.maxLinearMemory.orElse(Integer.MAX_VALUE) * (long) OCWasm.PAGE_SIZE);
		final long initialMemSize = compileResult.initialLinearMemory * (long) OCWasm.PAGE_SIZE;
		if(maxMemSize < initialMemSize) {
			return new Transition(null, new ExecutionResult.Error("Insufficient memory: " + maxMemSize + " bytes available but Wasm module requires " + initialMemSize + " to boot"));
		}
		memory = ByteBuffer.allocate(maxMemSize);

		// Instantiate the syscall modules.
		final Syscalls syscalls = new Syscalls(machine, cpu, memory);

		// Prepare the syscalls.
		syscalls.preRunThreaded();

		// Instantiate the module.
		final ModuleBase instance;
		try {
			instance = Instantiator.instantiate(compileResult.clazz, memory, syscalls.modules, this, Optional.empty());
		} catch(final ExecuteBinaryException exp) {
			// Discard all state and compile the new binary.
			close();
			return new Transition(new Compile(cpu, machine, exp.binary, Optional.empty(), Optional.empty()), SLEEP_ZERO);
		} catch(final Throwable t) {
			syscalls.close();
			return new Transition(null, ExceptionTranslator.translate(t));
		} finally {
			// During instantiation, we might have scheduled a task to report
			// timeout. Check whether we did so. This is thread-safe because
			// instanceConstructed, where the timeoutFuture field is written,
			// is called in the computer thread, same as we are running in
			// right now (in fact it is called as a side effect of
			// Instantiator.instantiate above).
			if(timeoutFuture != null) {
				// Cancel the task. There are three possible orders of
				// operations:
				//
				// 1. The cancel call prevents the task from ever starting. If
				//    the task does not run, it obviously does not write to
				//    timedOut, so a spurious timeout cannot occur.
				//
				// 2. The task obtains the monitor first, while the computer
				//    thread (here) obtains the monitor second. In this case,
				//    the task writes true to timedOut, which happens-before
				//    the task releases the monitor, which happens-before the
				//    computer thread (here) obtains the monitor, which
				//    happens-before execution continues from here on the
				//    computer thread. Therefore the task writing true
				//    happens-before the subsequent write of false on the
				//    computer thread preparatory to another call into the
				//    module instance, and a spurious timeout cannot occur.
				//
				// 3. The computer thread (here) obtains the monitor first,
				//    while the task obtains the monitor second. In this case,
				//    the task is interrupted, which happens-before the
				//    computer thread releases the monitor, which
				//    happens-before the task obtains the monitor, which
				//    happens-before the task checks for interruption, so the
				//    task sees that it is interrupted and does not write to
				//    timedOut at all, and a spurious timeout cannot occur.
				synchronized(this) {
					timeoutFuture.cancel(true);
				}
			}
		}

		return new Transition(new Run(cpu, machine, compileResult, memory, instance, syscalls), SLEEP_ZERO);
	}

	@Override
	public void instanceConstructed(final ModuleBase instance) {
		// Set the free memory to let the user-provided start function use for
		// its stack.
		instance.freeMemory = (cpu.getInstalledRAM() - compileResult.moduleSize(memory)) / ModuleBase.FREE_MEMORY_UNIT;

		// The module could have a user-provided start function. That start
		// function could run for a long time. Therefore, we need to set up the
		// timeout mechanism to guard execution of the start function. It will
		// be cancelled in runThreaded once the constructor completes.
		timeoutFuture = OCWasm.scheduleBackground(() -> {
			// See the deregistration code in runThreaded for an explanation of
			// the synchronization going on here.
			synchronized(InstantiateClean.this) {
				if(!Thread.interrupted()) {
					instance.timedOut = true;
				}
			}
		}, OCWasm.getTimeout(), TimeUnit.MILLISECONDS);
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound root) {
		// We do not need to save instance or syscall data here. Instantiation
		// can only have visible in-world side effects if the start function
		// runs, the start function can only run if the
		// Instantiator.instantiate method is called, and if that method is
		// called then it means runThreaded was called, and *any* call to
		// runThreaded results in a transition to another state (typically
		// Run), so the snapshot function would be called on that new state
		// instead. Therefore, if we get here, then runThreaded has not been
		// called on this state, so there must not be an instance yet.
		return new SnapshotOrGeneration(new Snapshot(machine.node().address(), OptionalLong.empty(), Optional.of(compileResult.binary), Optional.empty(), Optional.empty(), Optional.empty()));
	}

	@Override
	public void close() {
		// It is possible that the user-provided start function could do
		// something that results in resources requiring cleanup being created
		// in the APIs. However, should execution get to that point,
		// runThreaded is guaranteed to either call close() on the APIs object
		// itself (if transitioning to an error state) or else generate a
		// transition to a new state (on success). Thus this state as such will
		// never actually have any resources requiring clean up in it; by the
		// time such resources exist, the CPU will transition to a new state.
	}
}
