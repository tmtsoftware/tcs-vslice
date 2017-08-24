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
		context().system().eventStream().subscribe(self(), AssemblyState.class);
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
		return ReceiveBuilder.match(TelemetryUpdate.class, t -> publishTelemetryUpdate(telemetryService, t.az, t.el))
				.match(EcsStateUpdate.class, t -> publishEcsPositionUpdate(eventService, t.state, t.azItem, t.elItem))
				.match(AssemblyState.class, t -> publishAssemblyState(telemetryService, t))
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
	 * This method helps publishing ECS Telemetry Data as State Event using
	 * Telementry Service
	 */
	private void publishTelemetryUpdate(Optional<ITelemetryService> telemetryService, DoubleItem az, DoubleItem el) {
		StatusEvent ste = jadd(new StatusEvent(EcsConfig.telemetryEventPrefix), az, el);
		log.info("Inside EcsEventPublisher publishTelemetryUpdate: Status publish of " + EcsConfig.telemetryEventPrefix + ": "
				+ ste);

		telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside EcsEventPublisher publishTelemetryUpdate: Failed to publish telemetry: " + ste, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing ECS Position Update as State Event using
	 * Event Service
	 */
	private void publishEcsPositionUpdate(Optional<IEventService> eventService, ChoiceItem state, DoubleItem az,
			DoubleItem el) {
		SystemEvent se = jadd(new SystemEvent(EcsConfig.currentPosPrefix), state, az, el);
		log.debug("Inside EcsEventPublisher publishEcsPositionUpdate " + EcsConfig.currentPosPrefix + ": " + se);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside EcsEventPublisher publishEcsState failed to publish ecs state: " + se, ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing ECS Assembly State as State Event using
	 * Telementry Service
	 */
	private void publishAssemblyState(Optional<ITelemetryService> telemetryService, AssemblyState ts) {
		StatusEvent ste = jadd(new StatusEvent(EcsConfig.ecsStateEventPrefix), ts.az, ts.el);
		log.debug("Inside EcsEventPublisher publishAssemblyState: " + EcsConfig.ecsStateEventPrefix + ": " + ste);
		telemetryService.ifPresent(e -> e.publish(ste).handle((x, ex) -> {
			log.error("Inside EcsEventPublisher publishAssemblyState: failed to publish state: " + ste, ex);
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

	/**
	 * Used by actors wishing to cause an telemetry event update
	 */
	public static class TelemetryUpdate {
		public final DoubleItem az;
		public final DoubleItem el;

		/**
		 * 
		 * @param az
		 * @param el
		 */
		public TelemetryUpdate(DoubleItem az, DoubleItem el) {
			this.az = az;
			this.el = el;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((az == null) ? 0 : az.hashCode());
			result = prime * result + ((el == null) ? 0 : el.hashCode());
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
			if (az == null) {
				if (other.az != null)
					return false;
			} else if (!az.equals(other.az))
				return false;
			if (el == null) {
				if (other.el != null)
					return false;
			} else if (!el.equals(other.el))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "TelemetryUpdate [az=" + az + ", el=" + el + "]";
		}

	}

	public static class EcsStateUpdate {
		public final ChoiceItem state;
		public final DoubleItem azItem;
		public final DoubleItem elItem;

		public EcsStateUpdate(ChoiceItem state, DoubleItem azItem, DoubleItem elItem) {
			this.state = state;
			this.azItem = azItem;
			this.elItem = elItem;
		}
	}

}
