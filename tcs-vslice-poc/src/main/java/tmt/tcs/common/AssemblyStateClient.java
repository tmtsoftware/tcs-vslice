package tmt.tcs.common;

import akka.actor.Actor;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is Base State Client interface which helps in keeping track for State
 * for Assembly
 */
public interface AssemblyStateClient extends Actor {
	/**
	 * Sets the current Assembly state.
	 */
	void setCurrentState(AssemblyStateActor.AssemblyState assemblyState);

	default PartialFunction<Object, BoxedUnit> stateReceive() {
		return ReceiveBuilder.match(AssemblyStateActor.AssemblyState.class, assemblyState -> {
			System.out.println("Got state: " + assemblyState);
			setCurrentState(assemblyState);
		}).build();
	}
}
