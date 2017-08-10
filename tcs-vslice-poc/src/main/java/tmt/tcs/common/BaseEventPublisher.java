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
public abstract class BaseEventPublisher extends AbstractActor implements ILocationSubscriberClient {

	public abstract PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService);

	public abstract void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService);

}
