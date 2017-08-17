package tmt.tcs.m3;

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
import csw.util.config.DoubleItem;
import csw.util.config.Events.EventTime;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;

/**
 * This Class provides Event Subcription functionality for M3 It extends
 * BaseEventSubscriber
 */
@SuppressWarnings("unused")
public class M3EventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> refActor;
	private final EventService.EventMonitor subscribeMonitor;

	private DoubleItem rotation;
	private DoubleItem tilt;

	private M3EventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> refActor,
			IEventService eventService) {

		log.debug("Inside M3EventSubscriber");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;
		this.refActor = refActor;
		this.rotation = M3Config.rotation(0.0);
		this.tilt = M3Config.tilt(0.0);
		subscribeMonitor = startupSubscriptions(eventService);

		receive(subscribeReceive(rotation, tilt));
	}

	/**
	 * This method handles events being received by Event Subscriber Based upon
	 * type of events operations can be decided
	 * 
	 * @param rotation
	 * @param tilt
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> subscribeReceive(DoubleItem rotation, DoubleItem tilt) {
		return ReceiveBuilder.

				match(SystemEvent.class, event -> {
					log.debug("Inside M3EventSubscriber subscribeReceive received SystemEvent: Config Key is: "
							+ event.info().source());

					DoubleItem rotationItem;
					DoubleItem tiltItem;

					if (event.info().source().equals(M3Config.positionDemandCK)) {
						rotationItem = jitem(event, M3Config.rotationDemandKey);
						tiltItem = jitem(event, M3Config.tiltDemandKey);
						log.debug(
								"Inside M3EventSubscriber subscribeReceive received positionDemandCK: rotationItem is: "
										+ rotationItem + ": tiltItem is: " + tiltItem);
						updateRefActor(rotationItem, tiltItem, event.info().eventTime());

						context().become(subscribeReceive(rotationItem, tiltItem));
					} else if (event.info().source().equals(M3Config.offsetDemandCK)) {
						rotationItem = jitem(event, M3Config.rotationDemandKey);
						tiltItem = jitem(event, M3Config.tiltDemandKey);
						log.debug("Inside M3EventSubscriber subscribeReceive received offsetDemandCK: rotationItem is: "
								+ rotationItem + ": tiltItem is: " + tiltItem);
						updateRefActor(rotationItem, tiltItem, event.info().eventTime());

						context().become(subscribeReceive(rotationItem, tiltItem));
					}
				}).

				match(M3FollowActor.StopFollowing.class, t -> {
					subscribeMonitor.stop();
					// Kill this subscriber
					context().stop(self());
				}).

				matchAny(t -> System.out
						.println("Inside M3EventSubscriber Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This message propagates event to Referenced Actor
	 */
	private void updateRefActor(DoubleItem rotation, DoubleItem tilt, EventTime eventTime) {
		log.debug("Inside M3EventSubscriber updateRefActor: Sending Message to Follow Actor");
		refActor.ifPresent(
				actoRef -> actoRef.tell(new M3FollowActor.UpdatedEventData(rotation, tilt, eventTime), self()));
	}

	/**
	 * This helps in subscribing to specific events based on config key
	 * 
	 * @param eventService
	 * @return
	 */
	private EventMonitor startupSubscriptions(IEventService eventService) {

		EventMonitor subscribeMonitor = subscribeKeys(eventService, M3Config.positionDemandCK);

		log.debug("Inside M3EventSubscriber actor: " + subscribeMonitor.actorRef());

		subscribeKeys(subscribeMonitor, M3Config.offsetDemandCK);

		return subscribeMonitor;
	}

	/**
	 * Props for M3EventSubscriber
	 * 
	 * @param ac
	 * @param refActor
	 * @param eventService
	 * @return
	 */
	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, IEventService eventService) {
		return Props.create(new Creator<M3EventSubscriber>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3EventSubscriber create() throws Exception {
				return new M3EventSubscriber(ac, refActor, eventService);
			}
		});
	}

}
