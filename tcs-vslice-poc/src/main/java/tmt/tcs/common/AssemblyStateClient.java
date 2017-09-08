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
	 * This sets the current Assembly state, Implementation for same will be
	 * specific to implementation class
	 */
	void setCurrentState(AssemblyStateActor.AssemblyState assemblyState);

	/**
	 * This receives AssemblyState being published at time of State Change and
	 * helps setting the same
	 * 
	 * @return
	 */
	default PartialFunction<Object, BoxedUnit> stateReceive() {
		return ReceiveBuilder.match(AssemblyStateActor.AssemblyState.class, assemblyState -> {
			// System.out.println("Inside AssemblyStateClient stateReceive Got
			// state: " + assemblyState);
			setCurrentState(assemblyState);
		}).build();
	}
}
