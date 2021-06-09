package ca.chead.ocwasm;

import li.cil.oc.api.machine.Machine;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;

/**
 * Utilities for working with components.
 */
public final class ComponentUtils {
	/**
	 * Given a component UUID, finds the component.
	 *
	 * @param machine The machine to search from.
	 * @param address The UUID of a component.
	 * @return The identified component.
	 * @throws NoSuchComponentException If the component does not exist or is
	 * not visible from this computer.
	 */
	public static Component getComponent(final Machine machine, final String address) throws NoSuchComponentException {
		final Node selfNode = machine.node();
		final Node targetNode = selfNode.network().node(address);
		if(!(targetNode instanceof Component)) {
			throw new NoSuchComponentException();
		}
		final Component component = (Component) targetNode;
		if(component != selfNode && !component.canBeSeenFrom(selfNode)) {
			throw new NoSuchComponentException();
		}
		return component;
	}

	private ComponentUtils() {
	}
}
