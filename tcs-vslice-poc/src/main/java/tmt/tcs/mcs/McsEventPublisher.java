package tmt.tcs.mcs;

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
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventPublisher;

/**
 * This is an actor class that provides the publishing interface specific to MCS
 * to the Event Service and Telemetry Service.
 */
public class McsEventPublisher extends BaseEventPublisher {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	public McsEventPublisher(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn,
			Optional<ITelemetryService> telemetryServiceIn) {

		log.debug("Inside McsEventPublisher");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;

		log.debug("Inside McsEventPublisher Event Service in: " + eventServiceIn);
		log.debug("Inside McsEventPublisher Telemetry Service in: " + telemetryServiceIn);

		receive(publishingEnabled(eventServiceIn, telemetryServiceIn));
	}

	/**
	 * This method helps in publishing events based on type being received
	 */
	public PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return ReceiveBuilder.match(McsStateUpdate.class, t -> publishMcsState(eventService, t.state))
				.match(LocationService.Location.class,
						location -> handleLocations(location, eventService, telemetryService))
				.

				matchAny(t -> log.warning("Inside McsEventPublisher Unexpected message in publishingEnabled: " + t)).

				build();
	}

	/**
	 * This method handles locations
	 */
	public void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService) {
		if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside McsEventPublisher Received TCP Location: " + t.connection());
			// Verify that it is the event service
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside McsEventPublisher received connection: " + t);
				Optional<IEventService> newEventService = Optional
						.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside McsEventPublisherEvent Service at: " + newEventService);
				context().become(publishingEnabled(newEventService, currentTelemetryService));
			}

			if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
				log.debug("Inside McsEventPublisher received connection: " + t);
				Optional<ITelemetryService> newTelemetryService = Optional
						.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
				log.debug("Inside McsEventPublisher Telemetry Service at: " + newTelemetryService);
				context().become(publishingEnabled(currentEventService, newTelemetryService));
			}

		} else if (location instanceof LocationService.Unresolved) {
			log.debug("Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				context().become(publishingEnabled(Optional.empty(), currentTelemetryService));
			else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
				context().become(publishingEnabled(currentEventService, Optional.empty()));

		} else {
			log.debug("Inside McsEventPublisher received some other location: " + location);
		}
	}

	/**
	 * This method helps creating System Event object for Event publishing
	 */
	private void publishMcsState(Optional<IEventService> eventService, ChoiceItem state) {
		SystemEvent ste = jadd(new SystemEvent(McsConfig.mcsStateEventPrefix), state);
		log.debug("Inside McsEventPublisher " + McsConfig.mcsStateEventPrefix + ": " + ste);
		eventService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside McsEventPublisher failed to publish mcs state: " + ste, ex);
			return null;
		}));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return Props.create(new Creator<McsEventPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsEventPublisher create() throws Exception {
				return new McsEventPublisher(assemblyContext, eventService, telemetryService);
			}
		});
	}

	public static class McsStateUpdate {
		public final ChoiceItem state;

		public McsStateUpdate(ChoiceItem state) {
			this.state = state;
		}
	}

}
