package tmt.tcs.common;

import javacsw.services.ccs.JHcdController;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base class for all HCDs with some common functions being used by
 * all HCDs
 */
public abstract class BaseHcd extends JHcdController {

	/**
	 * HDC's initializing receive method receives HCD messages
	 * 
	 * @param supervisor
	 * @return
	 */
	public abstract PartialFunction<Object, BoxedUnit> initializingReceive();

	/**
	 * This method helps maintaining HCD's lifecycle
	 * 
	 * @param supervisor
	 * @return
	 */
	public abstract PartialFunction<Object, BoxedUnit> runningReceive();
}
