package ca.chead.ocwasm;

/**
 * A listener that is notified when a module instance is constructed.
 */
public interface ModuleConstructionListener {
	/**
	 * Invoked when a module instance is constructed.
	 *
	 * This method is invoked fairly early in the module construction process.
	 * The {@link ModuleBase} class exists at that point, but the module
	 * instance itself is not fully set up; in particular, its globals do not
	 * yet have their proper initial values, its linear memory does not exist,
	 * and its start function (if any) has not yet run.
	 *
	 * @param instance The module instance.
	 */
	void instanceConstructed(ModuleBase instance);
}
