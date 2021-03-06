package tmt.tcs.m3;

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
 * This is an actor class that provides the publishing interface specific to M3
 * to the Event Service and Telemetry Service.
 */
public class M3EventPublisher extends BaseEventPublisher {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	public M3EventPublisher(AssemblyContext assemblyContext, Optional<IEventService> eventServiceIn,
			Optional<ITelemetryService> telemetryServiceIn) {

		log.debug("Inside M3EventPublisher");

		subscribeToLocationUpdates();
		context().system().eventStream().subscribe(self(), AssemblyState.class);
		this.assemblyContext = assemblyContext;

		log.debug("Inside M3EventPublisher Event Service in: " + eventServiceIn);
		log.debug("Inside M3EventPublisher Telemetry Service in: " + telemetryServiceIn);

		receive(publishingEnabled(eventServiceIn, telemetryServiceIn));
	}

	/**
	 * This method helps in publishing events based on type being received
	 */
	public PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return ReceiveBuilder
				.match(TelemetryUpdate.class, t -> publishTelemetryUpdate(telemetryService, t.rotation, t.tilt))
				.match(M3StateUpdate.class,
						t -> publishM3PositionUpdate(eventService, t.state, t.rotationItem, t.tiltItem))
				.match(LocationService.Location.class,
						location -> handleLocations(location, eventService, telemetryService))
				.

				matchAny(t -> log.warning("Inside M3EventPublisher Unexpected message in publishingEnabled: " + t)).

				build();
	}

	/**
	 * This method handles locations
	 */
	public void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService) {
		if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside M3EventPublisher Received TCP Location: " + t.connection());
			// Verify that it is the event service
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside M3EventPublisher received connection: " + t);
				Optional<IEventService> newEventService = Optional
						.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside M3EventPublisherEvent Service at: " + newEventService);
				context().become(publishingEnabled(newEventService, currentTelemetryService));
			}

			if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
				log.debug("Inside M3EventPublisher received connection: " + t);
				Optional<ITelemetryService> newTelemetryService = Optional
						.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
				log.debug("Inside M3EventPublisher Telemetry Service at: " + newTelemetryService);
				context().become(publishingEnabled(currentEventService, newTelemetryService));
			}

		} else if (location instanceof LocationService.Unresolved) {
			log.debug("Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				context().become(publishingEnabled(Optional.empty(), currentTelemetryService));
			else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
				context().become(publishingEnabled(currentEventService, Optional.empty()));

		} else {
			log.debug("Inside M3EventPublisher received some other location: " + location);
		}
	}

	/**
	 * This method helps publishing M3 Telemetry Data as State Event using
	 * Telementry Service
	 */
	private void publishTelemetryUpdate(Optional<ITelemetryService> telemetryService, DoubleItem rotation,
			DoubleItem tilt) {
		StatusEvent ste = jadd(new StatusEvent(M3Config.telemetryEventPrefix), rotation, tilt);
		log.info("Inside M3EventPublisher publishTelemetryUpdate: Status publish of " + M3Config.telemetryEventPrefix
				+ ": " + ste);

		telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside M3EventPublisher publishTelemetryUpdate: Failed to publish telemetry: " + ste, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing M3 Position Update as State Event using
	 * Event Service
	 */
	private void publishM3PositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem rotation,
			DoubleItem tilt) {
		SystemEvent se = jadd(new SystemEvent(M3Config.currentPosPrefix), state, rotation, tilt);
		log.debug("Inside M3EventPublisher publishM3PositionUpdate " + M3Config.currentPosPrefix + ": " + se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside M3EventPublisher publishM3PositionUpdate failed to publish m3 position: " + se, ex);
			return null;
		}));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return Props.create(new Creator<M3EventPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3EventPublisher create() throws Exception {
				return new M3EventPublisher(assemblyContext, eventService, telemetryService);
			}
		});
	}

	/**
	 * Used by actors wishing to cause an telemetry event update
	 */
	public static class TelemetryUpdate {
		public final DoubleItem rotation;
		public final DoubleItem tilt;

		/**
		 * 
		 * @param rotation
		 * @param tilt
		 */
		public TelemetryUpdate(DoubleItem rotation, DoubleItem tilt) {
			this.rotation = rotation;
			this.tilt = tilt;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((rotation == null) ? 0 : rotation.hashCode());
			result = prime * result + ((tilt == null) ? 0 : tilt.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			TelemetryUpdate other = (TelemetryUpdate) obj;
			if (rotation == null) {
				if (other.rotation != null)
					return false;
			} else if (!rotation.equals(other.rotation))
				return false;
			if (tilt == null) {
				if (other.tilt != null)
					return false;
			} else if (!tilt.equals(other.tilt))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "TelemetryUpdate [rotation=" + rotation + ", tilt=" + tilt + "]";
		}

	}

	public static class M3StateUpdate {
		public final ChoiceItem state;
		public final DoubleItem rotationItem;
		public final DoubleItem tiltItem;

		public M3StateUpdate(ChoiceItem state, DoubleItem rotationItem, DoubleItem tiltItem) {
			this.state = state;
			this.rotationItem = rotationItem;
			this.tiltItem = tiltItem;
		}
	}
}
