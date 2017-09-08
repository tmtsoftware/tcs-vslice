package tmt.tcs.common;

import java.util.Optional;

import akka.actor.AbstractActor;
import csw.services.loc.LocationService;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.pkg.ILocationSubscriberClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base Event Publisher class being extended by all Event Publisher
 */
public abstract class BaseEventPublisher extends AbstractActor
		implements AssemblyStateClient, ILocationSubscriberClient {

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

	/**
	 * This method will help in publishing events, based upon the type being
	 * received , Implementation will be specific to implementor
	 * 
	 * @param eventService
	 * @param telemetryService
	 * @return
	 */
	public abstract PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService);

	/**
	 * This method will help in receiving location updates, Implementation will
	 * be specific to implementor
	 * 
	 * @param location
	 * @param currentEventService
	 * @param currentTelemetryService
	 */
	public abstract void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService);

}
