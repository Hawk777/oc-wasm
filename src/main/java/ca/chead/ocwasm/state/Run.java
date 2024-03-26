package ca.chead.ocwasm.state;

import ca.chead.ocwasm.CPU;
import ca.chead.ocwasm.Compiler;
import ca.chead.ocwasm.ExceptionTranslator;
import ca.chead.ocwasm.ExecuteBinaryException;
import ca.chead.ocwasm.ModuleBase;
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

public final class Run extends State {
	/**
	 * An execution result that requests a synchronous (indirect) call to be
	 * performed.
	 */
	private static final ExecutionResult SYNCHRONOUS_CALL = new ExecutionResult.SynchronizedCall();

	/**
	 * The compiler output.
	 */
	private final Compiler.Result compileResult;

	/**
	 * The linear memory to provide to the module.
	 */
	private final ByteBuffer memory;

	/**
	 * The module instance.
	 */
	private final ModuleBase instance;

	/**
	 * The syscall modules.
	 */
	private final Syscalls syscalls;

	/**
	 * Constructs a new {@code Run} state.
	 *
	 * @param cpu The CPU.
	 * @param machine The machine.
	 * @param compileResult The compiler output.
	 * @param memory The linear memory.
	 * @param instance The module instance.
	 * @param syscalls The syscall modules.
	 */
	public Run(final CPU cpu, final Machine machine, final Compiler.Result compileResult, final ByteBuffer memory, final ModuleBase instance, final Syscalls syscalls) {
		super(cpu, machine);
		this.compileResult = Objects.requireNonNull(compileResult);
		this.memory = Objects.requireNonNull(memory);
		this.instance = Objects.requireNonNull(instance);
		this.syscalls = Objects.requireNonNull(syscalls);
	}

	@Override
	public int freeMemory() {
		return instance.freeMemory * ModuleBase.FREE_MEMORY_UNIT;
	}

	@Override
	public boolean isInitialized() {
		return true;
	}

	@Override
	public Transition runThreaded() {
		// If there is a pending call, we need to move over to the server
		// thread to make that call. Normally we wouldn’t get here in such a
		// case, because the previous invocation that pended the call would
		// have returned ExecutionResult.SynchronizedCall; however, if we just
		// finished loading a snapshot that contained a pending call, or if the
		// start function pended a call, this could happen and needs to work
		// properly.
		if(syscalls.component.needsRunSynchronized()) {
			return new Transition(this, SYNCHRONOUS_CALL);
		}

		// Prepare the syscalls.
		syscalls.preRunThreaded();

		// Initialize the free memory counter.
		instance.freeMemory = (cpu.getInstalledRAM() - compileResult.moduleSize(memory)) / ModuleBase.FREE_MEMORY_UNIT;

		// Register a timeout handler.
		instance.timedOut = false;
		final Future<?> timeoutFuture = OCWasm.scheduleBackground(() -> {
			// See the deregistration code below for an explanation of the
			// synchronization going on here.
			synchronized(Run.this) {
				if(!Thread.interrupted()) {
					instance.timedOut = true;
				}
			}
		}, OCWasm.getTimeout(), TimeUnit.MILLISECONDS);
		try {
			// Run the user code.
			final int requestedSleep;
			try {
				requestedSleep = instance.run(syscalls.component.hasCallResult() ? 1 : 0);
			} catch(final ExecuteBinaryException exp) {
				// Discard all state and compile the new binary.
				close();
				return new Transition(new Compile(cpu, machine, exp.binary, Optional.empty(), Optional.empty()), SLEEP_ZERO);
			} catch(final Throwable t) {
				// Determine how to handle and report the exception. It may
				// rethrow, or it may convert into an ExecutionResult. In the
				// latter case, it will always convert into a terminal
				// ExecutionResult (i.e. one that kills the computer), not one
				// that requires a new state.
				close();
				return new Transition(null, ExceptionTranslator.translate(t));
			}
			// The user code succeeded.
			final ExecutionResult er;
			if(syscalls.component.needsRunSynchronized()) {
				// A synchronous call is pending, so go do that right away.
				er = SYNCHRONOUS_CALL;
			} else {
				// No synchronous call needs to be made, so we just want to
				// sleep for the requested time period or until a signal
				// arrives. OpenComputers itself will squash the sleep time to
				// zero if a signal is in the OpenComputers queue; however,
				// there could also be a signal which is no longer in the
				// OpenComputers queue but which is in the component syscall’s
				// popped signal field because it has been popped from the
				// OpenComputers queue but not fully delivered to the Wasm
				// module instance yet, in which case OpenComputers doesn’t
				// know about that signal and we need to squash the sleep time
				// to zero ourselves.
				er = new ExecutionResult.Sleep(syscalls.computer.hasPoppedSignal() ? 0 : requestedSleep);
			}
			return new Transition(this, er);
		} finally {
			// Cancel the timeout task. There are three possible orders of
			// operations:
			//
			// 1. The cancel call prevents the task from ever starting. If the
			//    task does not run, it obviously does not write to timedOut,
			//    so a spurious timeout cannot occur.
			//
			// 2. The task obtains the monitor first, while the computer thread
			//    (here) obtains the monitor second. In this case, the task
			//    writes true to timedOut, which happens-before the task
			//    releases the monitor, which happens-before the computer
			//    thread (here) obtains the monitor, which happens-before
			//    execution continues from here on the computer thread.
			//    Therefore the task writing true happens-before the subsequent
			//    write of false on the computer thread preparatory to another
			//    call into the module instance, and a spurious timeout cannot
			//    occur.
			//
			// 3. The computer thread (here) obtains the monitor first, while
			//    the task obtains the monitor second. In this case, the task
			//    is interrupted, which happens-before the computer thread
			//    releases the monitor, which happens-before the task obtains
			//    the monitor, which happens-before the task checks for
			//    interruption, so the task sees that it is interrupted and
			//    does not write to timedOut at all, and a spurious timeout
			//    cannot occur.
			synchronized(this) {
				timeoutFuture.cancel(true);
			}
		}
	}

	@Override
	public void runSynchronized() {
		syscalls.component.runSynchronized();
	}

	@Override
	public SnapshotOrGeneration snapshot(final NBTTagCompound root) {
		final Optional<byte[]> executeBuffer = syscalls.save(root);
		final ByteBuffer globalsBuf = ByteBuffer.allocate(compileResult.mutableGlobalsSize);
		instance.saveMutableGlobals(globalsBuf);
		final ByteBuffer memoryDup = memory.asReadOnlyBuffer();
		memoryDup.rewind();
		final byte[] memoryBytes = new byte[memoryDup.remaining()];
		memoryDup.get(memoryBytes);
		final Snapshot snapshot = new Snapshot(machine.node().address(), OptionalLong.empty(), Optional.of(compileResult.binary), Optional.of(globalsBuf.array()), Optional.of(memoryBytes), executeBuffer);
		root.setLong(NBT_GENERATION, snapshot.generation);
		return new SnapshotOrGeneration(snapshot);
	}

	@Override
	public void close() {
		syscalls.close();
	}
}
