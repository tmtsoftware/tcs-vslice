package tmt.tcs;

import static javacsw.util.config.JItems.jadd;

import java.util.Optional;

import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.loc.LocationService;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.util.config.ChoiceItem;
import csw.util.config.DoubleItem;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventPublisher;
import tmt.tcs.ecs.EcsEventPublisher.EcsStateUpdate;
import tmt.tcs.m3.M3EventPublisher.M3StateUpdate;
import tmt.tcs.mcs.McsEventPublisher.McsStateUpdate;

/**
 * This is an actor class that provides the publishing interface specific to TCS
 * to the Event Service and Telemetry Service.
 */
public class TcsEventPublisher extends BaseEventPublisher {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	public TcsEventPublisher(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn,
			Optional<ITelemetryService> telemetryServiceIn) {

		log.debug("Inside TcsEventPublisher");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;

		log.debug("Inside TcsEventPublisher Event Service in: " + eventServiceIn);
		log.debug("Inside TcsEventPublisher Telemetry Service in: " + telemetryServiceIn);

		receive(publishingEnabled(eventServiceIn, telemetryServiceIn));
	}

	/**
	 * This method helps in publishing events based on type being received
	 */
	public PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return ReceiveBuilder
				.match(McsStateUpdate.class, t -> publishMcsPositionUpdate(eventService, t.state, t.azItem, t.elItem))
				.match(EcsStateUpdate.class, t -> publishEcsPositionUpdate(eventService, t.state, t.azItem, t.elItem))
				.match(M3StateUpdate.class,
						t -> publishM3PositionUpdate(eventService, t.state, t.rotationItem, t.tiltItem))
				.match(LocationService.Location.class,
						location -> handleLocations(location, eventService, telemetryService))
				.

				matchAny(t -> log.warning("Inside TcsEventPublisher Unexpected message in publishingEnabled: " + t)).

				build();
	}

	/**
	 * This method handles locations
	 */
	public void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService) {
		if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside TcsEventPublisher Received TCP Location: " + t.connection());
			// Verify that it is the event service
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside TcsEventPublisher received connection: " + t);
				Optional<IEventService> newEventService = Optional
						.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside TcsEventPublisherEvent Service at: " + newEventService);
				context().become(publishingEnabled(newEventService, currentTelemetryService));
			}

			if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
				log.debug("Inside TcsEventPublisher received connection: " + t);
				Optional<ITelemetryService> newTelemetryService = Optional
						.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
				log.debug("Inside TcsEventPublisher Telemetry Service at: " + newTelemetryService);
				context().become(publishingEnabled(currentEventService, newTelemetryService));
			}

		} else if (location instanceof LocationService.Unresolved) {
			log.debug("Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				context().become(publishingEnabled(Optional.empty(), currentTelemetryService));
			else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
				context().become(publishingEnabled(currentEventService, Optional.empty()));

		} else {
			log.debug("Inside TcsEventPublisher received some other location: " + location);
		}
	}

	/**
	 * This method helps publishing MCS State as State Event using Event Service
	 */
	private void publishMcsPositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem az,
			DoubleItem el) {
		SystemEvent se = jadd(new SystemEvent(TcsConfig.mcsPositionPrefix), state, az, el);
		log.debug("Inside TcsEventPublisher publishMcsPositionUpdate " + TcsConfig.mcsPositionCK + ": " + se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside TcsEventPublisher publishMcsPositionUpdate : failed to publish mcs position: " + se, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing ECS Position Update as State Event using
	 * Event Service
	 */
	private void publishEcsPositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem az,
			DoubleItem el) {
		SystemEvent se = jadd(new SystemEvent(TcsConfig.ecsPositionPrefix), state, az, el);
		log.debug("Inside TcsEventPublisher publishEcsPositionUpdate " + TcsConfig.ecsPositionCK + ": " + se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside TcsEventPublisher publishEcsState failed to publish ecs state: " + se, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing M3 Position Update as State Event using
	 * Event Service
	 */
	private void publishM3PositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem rotation,
			DoubleItem tilt) {
		SystemEvent se = jadd(new SystemEvent(TcsConfig.m3PositionPrefix), state, rotation, tilt);
		log.debug("Inside TcsEventPublisher publishM3PositionUpdate " + TcsConfig.m3PositionCK + ": " + se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside TcsEventPublisher publishM3PositionUpdate failed to publish m3 position: " + se, ex);
			return null;
		}));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return Props.create(new Creator<TcsEventPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsEventPublisher create() throws Exception {
				return new TcsEventPublisher(assemblyContext, eventService, telemetryService);
			}
		});
	}

}
