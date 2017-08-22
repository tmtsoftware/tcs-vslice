package tmt.tcs;

import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jvalue;

import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.events.EventService;
import csw.services.events.EventService.EventMonitor;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;

/**
 * This Class provides Event Subcription functionality for TCS It extends
 * BaseEventSubscriber
 */
@SuppressWarnings("unused")
public class TcsEventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> refActor;
	private final EventService.EventMonitor subscribeMonitor;

	private TcsEventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> refActor,
			IEventService eventService) {

		log.debug("Inside TcsEventSubscriber");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;
		this.refActor = refActor;
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

					Double azValue = 0.0;
					Double elValue = 0.0;
					Double rotationValue = 0.0;
					Double tiltValue = 0.0;

					if (McsConfig.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received Mcs Current Position");
						log.debug(
								"############################## CURRENT MCS POSITION ##########################################");

						azValue = jvalue(jitem(event, McsConfig.azPosKey));
						elValue = jvalue(jitem(event, McsConfig.elPosKey));

						log.debug("Azimuth is: " + azValue + ": Elevation is: " + elValue);
						log.debug(
								"##############################################################################################");
					} else if (EcsConfig.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received Ecs Current Position");
						log.debug(
								"############################## CURRENT ECS POSITION ##########################################");

						azValue = jvalue(jitem(event, EcsConfig.azPosKey));
						elValue = jvalue(jitem(event, EcsConfig.elPosKey));

						log.debug("Azimuth is: " + azValue + ": Elevation is: " + elValue);
						log.debug(
								"##############################################################################################");
					} else if (M3Config.currentPosCK.equals(event.info().source())) {
						log.debug("Inside TcsEventSubscriber subscribeReceive received M3 Current Position");
						log.debug(
								"############################## CURRENT M3 POSITION ###########################################");

						rotationValue = jvalue(jitem(event, M3Config.rotationPosKey));
						tiltValue = jvalue(jitem(event, M3Config.tiltPosKey));

						log.debug("Rotation is: " + azValue + ": Tilt is: " + elValue);
						log.debug(
								"##############################################################################################");
					}

					updateRefActor(event.info().source(), azValue, elValue, rotationValue, tiltValue);
				}).

				matchAny(t -> System.out
						.println("Inside TcsEventSubscriber Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This message propagates event to Referenced Actor
	 */
	private void updateRefActor(ConfigKey ck, Double az, Double el, Double rotation, Double tilt) {
		//TODO:: Current Position to be sent to subscribed actor
		refActor.ifPresent(actorRef -> actorRef.tell(new String("Test"), self()));
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
