package ca.chead.ocwasm.state;

import java.util.Objects;
import li.cil.oc.api.machine.ExecutionResult;

/**
 * The result of a call to {@link State#runThreaded}.
 *
 * This encodes a combination of a new choice of {@link State} to move to, plus
 * an OpenComputers {@link ExecutionResult} indicating what the computer should
 * do next.
 */
public final class Transition {
	/**
	 * The new state to move to.
	 *
	 * This is {@code null} if and only if {@link #executionResult} requests
	 * that the computer shut down.
	 */
	public final State nextState;

	/**
	 * The OpenComputers execution result.
	 */
	public final ExecutionResult executionResult;

	/**
	 * Constructs a new Transition.
	 *
	 * @param nextState The new state to move to, or {@code null} if {@code
	 * executionResult} requests that the computer shut down.
	 * @param executionResult The OpenComputers execution result.
	 */
	public Transition(final State nextState, final ExecutionResult executionResult) {
		Objects.requireNonNull(executionResult);
		if(executionResult instanceof ExecutionResult.Sleep || executionResult instanceof ExecutionResult.SynchronizedCall) {
			// For Sleep and SynchronizedCall, the computer keeps running, so
			// there must be a next state.
			Objects.requireNonNull(nextState);
		} else if(executionResult instanceof ExecutionResult.Shutdown || executionResult instanceof ExecutionResult.Error) {
			// For Shutdown and Error, the computer does not keep running, so
			// there must not be a next state.
			if(nextState != null) {
				throw new IllegalArgumentException();
			}
		} else {
			// executionResult should only be one of Sleep, SynchronizedCall,
			// Shutdown, or Error.
			throw new IllegalArgumentException();
		}
		this.nextState = nextState;
		this.executionResult = executionResult;
	}
}
