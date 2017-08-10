package tmt.tcs.ecs;

import static javacsw.util.config.JItems.jadd;

import java.util.Optional;

import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.loc.LocationService;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.util.config.DoubleItem;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventPublisher;
import tmt.tcs.ecs.EcsDiagnosticPublisher.EcsStateUpdate;

/**
 * This is an actor class that provides the publishing interface specific to ECS
 * to the Event Service and Telemetry Service.
 */
public class EcsEventPublisher extends BaseEventPublisher {
	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	public EcsEventPublisher(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn,
			Optional<ITelemetryService> telemetryServiceIn) {

		log.debug("Inside EcsEventPublisher");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;

		log.debug("Inside EcsEventPublisher Event Service in: " + eventServiceIn);
		log.debug("Inside EcsEventPublisher Telemetry Service in: " + telemetryServiceIn);

		receive(publishingEnabled(eventServiceIn, telemetryServiceIn));
	}

	/**
	 * This method helps in publishing events based on type being received
	 */
	public PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return ReceiveBuilder.match(EcsStateUpdate.class, t -> publishEcsState(eventService, t.az, t.el, t.time))
				.match(LocationService.Location.class,
						location -> handleLocations(location, eventService, telemetryService))
				.

				matchAny(t -> log.warning("Inside EcsEventPublisher Unexpected message in publishingEnabled: " + t)).

				build();
	}

	/**
	 * This method handles locations
	 */
	public void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService) {
		if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside EcsEventPublisher Received TCP Location: " + t.connection());
			// Verify that it is the event service
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside EcsEventPublisher received connection: " + t);
				Optional<IEventService> newEventService = Optional
						.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside EcsEventPublisherEvent Service at: " + newEventService);
				context().become(publishingEnabled(newEventService, currentTelemetryService));
			}

			if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
				log.debug("Inside EcsEventPublisher received connection: " + t);
				Optional<ITelemetryService> newTelemetryService = Optional
						.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
				log.debug("Inside EcsEventPublisher Telemetry Service at: " + newTelemetryService);
				context().become(publishingEnabled(currentEventService, newTelemetryService));
			}

		} else if (location instanceof LocationService.Unresolved) {
			log.debug("Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				context().become(publishingEnabled(Optional.empty(), currentTelemetryService));
			else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
				context().become(publishingEnabled(currentEventService, Optional.empty()));

		} else {
			log.debug("Inside EcsEventPublisher received some other location: " + location);
		}
	}

	/**
	 * This method helps creating System Event object for Event publishing
	 */
	private void publishEcsState(Optional<IEventService> eventService, DoubleItem az, DoubleItem el, DoubleItem time) {
		SystemEvent ste = jadd(new SystemEvent(EcsConfig.ecsStateEventPrefix), az, el, time);
		log.debug("Inside EcsEventPublisher " + EcsConfig.ecsStateEventPrefix + ": " + ste);
		eventService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside EcsEventPublisher failed to publish mcs state: " + ste, ex);
			return null;
		}));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return Props.create(new Creator<EcsEventPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsEventPublisher create() throws Exception {
				return new EcsEventPublisher(assemblyContext, eventService, telemetryService);
			}
		});
	}

}
