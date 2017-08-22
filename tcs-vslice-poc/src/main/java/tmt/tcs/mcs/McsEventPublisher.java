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
import csw.util.config.DoubleItem;
import csw.util.config.Events.StatusEvent;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
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
		context().system().eventStream().subscribe(self(), AssemblyState.class);
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
		return ReceiveBuilder.match(SystemUpdate.class, t -> publishSystemEvent(eventService, t.az, t.el))
				.match(EngrUpdate.class, t -> publishEngr(telemetryService, t.az, t.el))
				.match(McsStateUpdate.class, t -> publishMcsPositionUpdate(eventService, t.state, t.azItem, t.elItem))
				.match(AssemblyState.class, t -> publishAssemblyState(telemetryService, t))
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

	private void publishSystemEvent(Optional<IEventService> eventService, DoubleItem az, DoubleItem el) {
		SystemEvent se = jadd(new SystemEvent(McsConfig.systemEventPrefix), az, el);
		log.info("Inside McsEventPublisher publishSystemEvent: Status publish of " + McsConfig.systemEventPrefix + ": "
				+ se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside McsEventPublisher publishSystemEvent: Failed to publish System event: " + se, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing MCS Engr Data as State Event using
	 * Telementry Service
	 */
	private void publishEngr(Optional<ITelemetryService> telemetryService, DoubleItem az, DoubleItem el) {
		StatusEvent ste = jadd(new StatusEvent(McsConfig.engineeringEventPrefix), az, el);
		log.info("Inside McsEventPublisher publishEngr: Status publish of " + McsConfig.engineeringEventPrefix + ": "
				+ ste);

		telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside McsEventPublisher publishEngr: Failed to publish engr: " + ste, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing MCS State as State Event using Telementry
	 * Service
	 */
	private void publishMcsPositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem az,
			DoubleItem el) {
		StatusEvent ste = jadd(new StatusEvent(McsConfig.currentPosPrefix), state, az, el);
		log.debug("Inside McsEventPublisher " + McsConfig.currentPosPrefix + ": " + ste);
		eventService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside McsEventPublisher failed to publish mcs state: " + ste, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing MCS Assembly State as State Event using
	 * Telementry Service
	 */
	private void publishAssemblyState(Optional<ITelemetryService> telemetryService, AssemblyState ts) {
		StatusEvent ste = jadd(new StatusEvent(McsConfig.mcsStateEventPrefix), ts.az, ts.el);
		log.debug("Inside publishAssemblyState publishState: " + McsConfig.mcsStateEventPrefix + ": " + ste);
		telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside publishAssemblyState publishState: failed to publish state: " + ste, ex);
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

	/**
	 * Used by actors wishing to cause an engineering event update
	 */
	public static class EngrUpdate {
		public final DoubleItem az;
		public final DoubleItem el;

		/**
		 * 
		 * @param az
		 * @param el
		 */
		public EngrUpdate(DoubleItem az, DoubleItem el) {
			this.az = az;
			this.el = el;
		}

	}

	/**
	 * Used by actors wishing to cause an system event update
	 */
	public static class SystemUpdate {
		public final DoubleItem az;
		public final DoubleItem el;

		/**
		 * 
		 * @param az
		 * @param el
		 */
		public SystemUpdate(DoubleItem az, DoubleItem el) {
			this.az = az;
			this.el = el;
		}

	}

	public static class McsStateUpdate {
		public final ChoiceItem state;
		public final DoubleItem azItem;
		public final DoubleItem elItem;

		public McsStateUpdate(ChoiceItem state, DoubleItem azItem, DoubleItem elItem) {
			this.state = state;
			this.azItem = azItem;
			this.elItem = elItem;
		}
	}

}
