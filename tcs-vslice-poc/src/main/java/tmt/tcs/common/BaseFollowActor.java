package tmt.tcs.common;

import akka.actor.AbstractActor;
import javacsw.services.pkg.ILocationSubscriberClient;

/**
 * This is base class for all Follow Actor classes
 * 
 */
public abstract class BaseFollowActor extends AbstractActor implements AssemblyStateClient, ILocationSubscriberClient {

	private AssemblyStateActor.AssemblyState internalState = AssemblyStateActor.defaultAssemblyState;

	public static class CommandDone {
	}

	@Override
	public void setCurrentState(AssemblyStateActor.AssemblyState state) {
		internalState = state;
	}

	/**
	 * @return AssemblyStateActor.AssemblyState
	 */
	public AssemblyStateActor.AssemblyState currentState() {
		return internalState;
	}

}
