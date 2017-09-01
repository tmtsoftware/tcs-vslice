package tmt.tcs;

import static javacsw.util.config.JItems.jitem;

import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.events.EventService;
import csw.services.events.EventService.EventMonitor;
import csw.util.config.ChoiceItem;
import csw.util.config.DoubleItem;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.ecs.EcsEventPublisher.EcsStateUpdate;
import tmt.tcs.m3.M3Config;
import tmt.tcs.m3.M3EventPublisher.M3StateUpdate;
import tmt.tcs.mcs.McsConfig;
import tmt.tcs.mcs.McsEventPublisher.McsStateUpdate;

/**
 * This Class provides Event Subcription functionality for TCS It extends
 * BaseEventSubscriber
 */
@SuppressWarnings("unused")
public class TcsEventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> eventPublisher;
	private final EventService.EventMonitor subscribeMonitor;

	private TcsEventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> eventPublisher,
			IEventService eventService) {

		log.debug("Inside TcsEventSubscriber");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;
		this.eventPublisher = eventPublisher;
		subscribeMonitor = startupSubscriptions(eventService);

		receive(subscribeReceive());
	}

	/**
	 * This method handles events being received by Event Subscriber Based upon
	 * type of events operations can be decided
	 */
	public PartialFunction<Object, BoxedUnit> subscribeReceive() {
		return ReceiveBuilder.

				match(SystemEvent.class, event -> {
					log.debug("Inside TcsEventSubscriber subscribeReceive received an SystemEvent: Config Key is: "
							+ event.info().source());

					DoubleItem mcsAzItem;
					DoubleItem mcsElItem;
					DoubleItem ecsAzItem;
					DoubleItem ecsElItem;
					DoubleItem m3RotationItem;
					DoubleItem m3TiltItem;
					ChoiceItem stateItem;

					if (McsConfig.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received Mcs Current Position");
						log.debug(
								"############################## CURRENT MCS POSITION ##########################################");

						mcsAzItem = jitem(event, McsConfig.azPosKey);
						mcsElItem = jitem(event, McsConfig.elPosKey);
						stateItem = jitem(event, McsConfig.mcsStateKey);

						log.debug("Azimuth is: " + mcsAzItem + ": Elevation is: " + mcsElItem);
						log.debug(
								"##############################################################################################");

						updateMcsPublisher(stateItem, mcsAzItem, mcsElItem);
					} else if (EcsConfig.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received Ecs Current Position");
						log.debug(
								"############################## CURRENT ECS POSITION ##########################################");

						ecsAzItem = jitem(event, EcsConfig.azPosKey);
						ecsElItem = jitem(event, EcsConfig.elPosKey);
						stateItem = jitem(event, EcsConfig.ecsStateKey);

						log.debug("Azimuth is: " + ecsAzItem + ": Elevation is: " + ecsElItem);
						log.debug(
								"##############################################################################################");

						updateEcsPublisher(stateItem, ecsAzItem, ecsElItem);
					} else if (M3Config.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received M3 Current Position");
						log.debug(
								"############################## CURRENT M3 POSITION ###########################################");

						m3RotationItem = jitem(event, M3Config.rotationPosKey);
						m3TiltItem = jitem(event, M3Config.tiltPosKey);
						stateItem = jitem(event, M3Config.m3StateKey);

						log.debug("Rotation is: " + m3RotationItem + ": Tilt is: " + m3TiltItem);
						log.debug(
								"##############################################################################################");

						updateM3Publisher(stateItem, m3RotationItem, m3TiltItem);
					}

				}).

				matchAny(t -> log.error("Inside TcsEventSubscriber Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This propagates MCS Position to publisher for further publishing for
	 * outside systems
	 */
	private void updateMcsPublisher(ChoiceItem mcsState, DoubleItem mcsAzItem, DoubleItem mcsElItem) {
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new McsStateUpdate(mcsState, mcsAzItem, mcsElItem), self()));
	}

	/**
	 * This propagates ECS Position to publisher for further publishing for
	 * outside systems
	 */
	private void updateEcsPublisher(ChoiceItem ecsState, DoubleItem ecsAzItem, DoubleItem ecsElItem) {
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new EcsStateUpdate(ecsState, ecsAzItem, ecsElItem), self()));
	}

	/**
	 * This propagates M3 Position to publisher for further publishing for
	 * outside systems
	 */
	private void updateM3Publisher(ChoiceItem m3State, DoubleItem m3RotationItem, DoubleItem m3TiltItem) {
		eventPublisher
				.ifPresent(actorRef -> actorRef.tell(new M3StateUpdate(m3State, m3RotationItem, m3TiltItem), self()));
	}

	/**
	 * This helps in subscribing to specific events based on config key
	 * 
	 * @param eventService
	 * @return
	 */
	private EventMonitor startupSubscriptions(IEventService eventService) {
		// Subscribe to Mcs Current Position Event
		EventMonitor subscribeMonitor = subscribeKeys(eventService, McsConfig.currentPosCK);

		log.debug("Inside TcsEventSubscriber actor: " + subscribeMonitor.actorRef());

		// Subscribe to Ecs Current Position Event
		subscribeKeys(subscribeMonitor, EcsConfig.currentPosCK);

		// Subscribe to M3 Current Position Event
		subscribeKeys(subscribeMonitor, M3Config.currentPosCK);

		return subscribeMonitor;
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, IEventService eventService) {
		return Props.create(new Creator<TcsEventSubscriber>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsEventSubscriber create() throws Exception {
				return new TcsEventSubscriber(ac, refActor, eventService);
			}
		});
	}

}
