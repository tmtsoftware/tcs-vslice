package tmt.tcs.tpk;

import static javacsw.util.config.JItems.jadd;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.loc.LocationService;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.DoubleItem;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.pkg.ILocationSubscriberClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is an actor class that helps in publishing Demands for MCS, ECS and M3.
 */
public class TpkEventPublisher extends AbstractActor implements ILocationSubscriberClient {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	public TpkEventPublisher(Optional<IEventService> eventServiceIn, Optional<ITelemetryService> telemetryServiceIn) {

		log.debug("Inside TpkEventPublisher");

		subscribeToLocationUpdates();

		log.debug("Inside TpkEventPublisher Event Service in: " + eventServiceIn);
		log.debug("Inside TpkEventPublisher Telemetry Service in: " + telemetryServiceIn);

		receive(publishingEnabled(eventServiceIn, telemetryServiceIn));
	}

	/**
	 * This method helps in publishing events based on type being received
	 */
	public PartialFunction<Object, BoxedUnit> publishingEnabled(Optional<IEventService> eventService,
			Optional<ITelemetryService> telemetryService) {
		return ReceiveBuilder
				.match(McsPosDemand.class, t -> publishMcsPositionDemand(eventService, t.configKey, t.azItem, t.elItem))
				.match(EcsPosDemand.class, t -> publishEcsPositionDemand(eventService, t.configKey, t.azItem, t.elItem))
				.match(M3PosDemand.class,
						t -> publishM3PositionDemand(eventService, t.configKey, t.rotationItem, t.tiltItem))
				.match(LocationService.Location.class,
						location -> handleLocations(location, eventService, telemetryService))
				.matchAny(t -> log.warning("Inside TpkEventPublisher Unexpected message in publishingEnabled: " + t)).

				build();
	}

	/**
	 * This method handles locations
	 */
	public void handleLocations(LocationService.Location location, Optional<IEventService> currentEventService,
			Optional<ITelemetryService> currentTelemetryService) {
		if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside TpkEventPublisher Received TCP Location: " + t.connection());
			System.out.println("*********TpkEventPublisher Received TCP Location: " + t.connection());
			// Verify that it is the event service
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside TpkEventPublisher received connection: " + t);
				Optional<IEventService> newEventService = Optional
						.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside TpkEventPublisherEvent Service at: " + newEventService);
				context().become(publishingEnabled(newEventService, currentTelemetryService));
			}

			if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
				log.debug("Inside TpkEventPublisher received connection: " + t);
				Optional<ITelemetryService> newTelemetryService = Optional
						.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
				log.debug("Inside TpkEventPublisher Telemetry Service at: " + newTelemetryService);
				context().become(publishingEnabled(currentEventService, newTelemetryService));
			}

		} else if (location instanceof LocationService.Unresolved) {
			log.debug("Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				context().become(publishingEnabled(Optional.empty(), currentTelemetryService));
			else if (location.connection().equals(ITelemetryService.telemetryServiceConnection()))
				context().become(publishingEnabled(currentEventService, Optional.empty()));

		} else {
			log.debug("Inside TpkEventPublisher received some other location: " + location);
		}
	}

	/**
	 * This method helps publishing Position Demands for MCS using Event Service
	 */
	private void publishMcsPositionDemand(Optional<IEventService> eventService, ConfigKey configKey, DoubleItem azItem,
			DoubleItem elItem) {
		SystemEvent se = jadd(new SystemEvent(configKey.prefix()), azItem, elItem);
		log.debug("Inside TpkEventPublisher publishMcsPositionDemand " + configKey + ": " + se + ": eventService is: "
				+ eventService);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error(
					"Inside TpkEventPublisher publishMcsPositionDemand : failed to publish mcs position demand: " + se,
					ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing Position Demands for ECS using Event Service
	 */
	private void publishEcsPositionDemand(Optional<IEventService> eventService, ConfigKey configKey, DoubleItem azItem,
			DoubleItem elItem) {
		SystemEvent se = jadd(new SystemEvent(configKey.prefix()), azItem, elItem);
		log.debug("Inside TpkEventPublisher publishEcsPositionDemand " + configKey + ": " + se + ": eventService is: "
				+ eventService);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error(
					"Inside TpkEventPublisher publishEcsPositionDemand : failed to publish ecs position demand: " + se,
					ex);
			return null;
		}));
	}

	/**
	 * This method helps publishing Position Demands for M3 using Event Service
	 */
	private void publishM3PositionDemand(Optional<IEventService> eventService, ConfigKey configKey,
			DoubleItem rotationItem, DoubleItem tiltItem) {
		SystemEvent se = jadd(new SystemEvent(configKey.prefix()), rotationItem, tiltItem);
		log.debug("Inside TpkEventPublisher publishM3PositionDemand " + configKey + ": " + se + ": eventService is: "
				+ eventService);
		eventService.ifPresent(e -> e.publish(se).handle((x, ex) -> {
			log.error("Inside TpkEventPublisher publishM3PositionDemand : failed to publish m3 position demand: " + se,
					ex);
			return null;
		}));
	}

	public static Props props(Optional<IEventService> eventService, Optional<ITelemetryService> telemetryService) {
		return Props.create(new Creator<TpkEventPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TpkEventPublisher create() throws Exception {
				return new TpkEventPublisher(eventService, telemetryService);
			}
		});
	}

	/**
	 * This is the class specific for MCS Position Demand Generation
	 */
	public static class McsPosDemand {
		public final ConfigKey configKey;
		public final DoubleItem azItem;
		public final DoubleItem elItem;

		public McsPosDemand(ConfigKey configKey, DoubleItem azItem, DoubleItem elItem) {
			this.configKey = configKey;
			this.azItem = azItem;
			this.elItem = elItem;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((azItem == null) ? 0 : azItem.hashCode());
			result = prime * result + ((configKey == null) ? 0 : configKey.hashCode());
			result = prime * result + ((elItem == null) ? 0 : elItem.hashCode());
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
			McsPosDemand other = (McsPosDemand) obj;
			if (azItem == null) {
				if (other.azItem != null)
					return false;
			} else if (!azItem.equals(other.azItem))
				return false;
			if (configKey == null) {
				if (other.configKey != null)
					return false;
			} else if (!configKey.equals(other.configKey))
				return false;
			if (elItem == null) {
				if (other.elItem != null)
					return false;
			} else if (!elItem.equals(other.elItem))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "McsPosDemand [configKey=" + configKey + ", azItem=" + azItem + ", elItem=" + elItem + "]";
		}

	}

	/**
	 * This is the class specific for ECS Position Demand Generation
	 */
	public static class EcsPosDemand {
		public final ConfigKey configKey;
		public final DoubleItem azItem;
		public final DoubleItem elItem;

		public EcsPosDemand(ConfigKey configKey, DoubleItem azItem, DoubleItem elItem) {
			this.configKey = configKey;
			this.azItem = azItem;
			this.elItem = elItem;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((azItem == null) ? 0 : azItem.hashCode());
			result = prime * result + ((configKey == null) ? 0 : configKey.hashCode());
			result = prime * result + ((elItem == null) ? 0 : elItem.hashCode());
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
			EcsPosDemand other = (EcsPosDemand) obj;
			if (azItem == null) {
				if (other.azItem != null)
					return false;
			} else if (!azItem.equals(other.azItem))
				return false;
			if (configKey == null) {
				if (other.configKey != null)
					return false;
			} else if (!configKey.equals(other.configKey))
				return false;
			if (elItem == null) {
				if (other.elItem != null)
					return false;
			} else if (!elItem.equals(other.elItem))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "EcsPosDemand [configKey=" + configKey + ", azItem=" + azItem + ", elItem=" + elItem + "]";
		}

	}

	/**
	 * This is the class specific for M3 Position Demand Generation
	 */
	public static class M3PosDemand {
		public final ConfigKey configKey;
		public final DoubleItem rotationItem;
		public final DoubleItem tiltItem;

		public M3PosDemand(ConfigKey configKey, DoubleItem rotationItem, DoubleItem tiltItem) {
			this.configKey = configKey;
			this.rotationItem = rotationItem;
			this.tiltItem = tiltItem;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((configKey == null) ? 0 : configKey.hashCode());
			result = prime * result + ((rotationItem == null) ? 0 : rotationItem.hashCode());
			result = prime * result + ((tiltItem == null) ? 0 : tiltItem.hashCode());
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
			M3PosDemand other = (M3PosDemand) obj;
			if (configKey == null) {
				if (other.configKey != null)
					return false;
			} else if (!configKey.equals(other.configKey))
				return false;
			if (rotationItem == null) {
				if (other.rotationItem != null)
					return false;
			} else if (!rotationItem.equals(other.rotationItem))
				return false;
			if (tiltItem == null) {
				if (other.tiltItem != null)
					return false;
			} else if (!tiltItem.equals(other.tiltItem))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "M3PosDemand [configKey=" + configKey + ", rotationItem=" + rotationItem + ", tiltItem=" + tiltItem
					+ "]";
		}

	}

}
