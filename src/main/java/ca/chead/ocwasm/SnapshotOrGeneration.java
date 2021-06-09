package ca.chead.ocwasm;

import java.util.Objects;
import java.util.Optional;

/**
 * A value that is either a snapshot or just a generation number without a
 * snapshot.
 *
 * This is the return type of {@link ca.chead.ocwasm.state.State#snapshot}.
 * Normally that method returns a snapshot to write out; however, in certain
 * cases, it may instead return the generation number of the snapshot that is
 * known to already be on disk without including the snapshot itself,
 * signalling that the caller should record the generation number but
 * <em>not</em> write out a snapshot, instead reusing the existing snapshot.
 */
public final class SnapshotOrGeneration {
	/**
	 * The contained object, which is either a {@ref Snapshot} or a {@ref
	 * Long}.
	 */
	private final Object object;

	/**
	 * Encapsulates a {@link Snapshot}.
	 *
	 * @param snapshot The snapshot to encapsulate.
	 */
	public SnapshotOrGeneration(final Snapshot snapshot) {
		super();
		object = Objects.requireNonNull(snapshot);
	}

	/**
	 * Encapsulates a generation number alone.
	 *
	 * @param generation The generation number.
	 */
	public SnapshotOrGeneration(final long generation) {
		super();
		object = generation;
	}

	/**
	 * Returns the {@link Snapshot}, if present.
	 *
	 * @return The snapshot, if present.
	 */
	public Optional<Snapshot> snapshot() {
		if(object instanceof Snapshot) {
			return Optional.of((Snapshot) object);
		} else {
			return Optional.empty();
		}
	}

	/**
	 * Returns the generation number.
	 *
	 * This always succeeds; it returns either the lone generation number or
	 * the generation number contained in the snapshot.
	 *
	 * @return The generation number.
	 */
	public long generation() {
		if(object instanceof Snapshot) {
			return ((Snapshot) object).generation;
		} else {
			return (Long) object;
		}
	}
}
