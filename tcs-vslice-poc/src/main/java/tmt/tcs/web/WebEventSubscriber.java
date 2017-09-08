package tmt.tcs.web;

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
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.TcsConfig;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;

/**
 * This Class provides Event Subscription functionality for External System.
 * This extends BaseEventSubscriber and subscribes to Events being pubslished by
 * TCS Position Events
 */
@SuppressWarnings("unused")
public class WebEventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> eventPublisher;
	private final EventService.EventMonitor subscribeMonitor;

	private WebEventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> eventPublisher,
			IEventService eventService) {

		log.debug("Inside WebEventSubscriber");

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
					log.debug("Inside WebEventSubscriber subscribeReceive received an SystemEvent: Config Key is: "
							+ event.info().source());

					if (TcsConfig.mcsPositionCK.equals(event.info().source())) {
						log.debug("Inside WebEventSubscriber subscribeReceive received Mcs Current Position");

						TcsDataHandler.mcsAzimuth = jvalue(jitem(event, McsConfig.azPosKey));
						TcsDataHandler.mcsElevation = jvalue(jitem(event, McsConfig.elPosKey));

					} else if (TcsConfig.ecsPositionCK.equals(event.info().source())) {
						log.debug("Inside WebEventSubscriber subscribeReceive received Ecs Current Position");

						TcsDataHandler.ecsAzimuth = jvalue(jitem(event, EcsConfig.azPosKey));
						TcsDataHandler.ecsElevation = jvalue(jitem(event, EcsConfig.elPosKey));

					} else if (TcsConfig.m3PositionCK.equals(event.info().source())) {
						log.debug("Inside WebEventSubscriber subscribeReceive received M3 Current Position");

						TcsDataHandler.m3Rotation = jvalue(jitem(event, M3Config.rotationPosKey));
						TcsDataHandler.m3Tilt = jvalue(jitem(event, M3Config.tiltPosKey));

					}

					log.debug("Inside WebEventSubscriber subscribeReceive: " + TcsDataHandler.mcsAzimuth + " : "
							+ TcsDataHandler.mcsElevation + " : " + TcsDataHandler.ecsAzimuth + " : "
							+ TcsDataHandler.ecsElevation + " : " + TcsDataHandler.m3Rotation + " : "
							+ TcsDataHandler.m3Tilt);

				}).

				matchAny(t -> log.error("Inside WebEventSubscriber Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This helps in subscribing to specific events based on config key
	 * 
	 * @param eventService
	 * @return
	 */
	private EventMonitor startupSubscriptions(IEventService eventService) {
		// Subscribe to Mcs Current Position Event
		EventMonitor subscribeMonitor = subscribeKeys(eventService, TcsConfig.mcsPositionCK);

		log.debug("Inside WebEventSubscriber actor: " + subscribeMonitor.actorRef());

		// Subscribe to Ecs Current Position Event
		subscribeKeys(subscribeMonitor, TcsConfig.ecsPositionCK);

		// Subscribe to M3 Current Position Event
		subscribeKeys(subscribeMonitor, TcsConfig.m3PositionCK);

		return subscribeMonitor;
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, IEventService eventService) {
		return Props.create(new Creator<WebEventSubscriber>() {
			private static final long serialVersionUID = 1L;

			@Override
			public WebEventSubscriber create() throws Exception {
				return new WebEventSubscriber(ac, refActor, eventService);
			}
		});
	}

}
