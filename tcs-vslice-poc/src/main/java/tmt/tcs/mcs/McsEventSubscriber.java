package tmt.tcs.mcs;

import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
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
import csw.util.config.DoubleItem;
import csw.util.config.Events.EventTime;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;

/**
 * This Class provides Event Subscription functionality for MCS It extends
 * BaseEventSubscriber
 */
@SuppressWarnings("unused")
public class McsEventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> followActor;
	private final EventService.EventMonitor subscribeMonitor;

	private DoubleItem initialAz;
	private DoubleItem initialEl;

	private McsEventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> followActor,
			IEventService eventService) {

		log.debug("Inside McsEventSubscriber");

		subscribeToLocationUpdates();
		this.assemblyContext = assemblyContext;
		this.followActor = followActor;
		this.initialAz = McsConfig.az(0.0);
		this.initialEl = McsConfig.el(0.0);
		subscribeMonitor = startupSubscriptions(eventService);

		receive(subscribeReceive(initialAz, initialEl));
	}

	/**
	 * This method handles events being received by Event Subscriber Based upon
	 * type of events operations can be decided
	 * 
	 * @param initialAz
	 * @param initialEl
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> subscribeReceive(DoubleItem initialAz, DoubleItem initialEl) {
		return ReceiveBuilder.

				match(SystemEvent.class, event -> {
					log.debug("Inside McsEventSubscriber subscribeReceive received SystemEvent: Config Key is: "
							+ event.info().source());

					if (event.info().source().equals(McsConfig.positionDemandCK)
							|| event.info().source().equals(McsConfig.offsetDemandCK)) {
						Double azValue = jvalue(jitem(event, McsConfig.azDemandKey));
						Double elValue = jvalue(jitem(event, McsConfig.elDemandKey));
						DoubleItem azItem = jset(McsConfig.az, azValue);
						DoubleItem elItem = jset(McsConfig.el, elValue);
						log.debug("Inside McsEventSubscriber subscribeReceive received : " + event.info().source()
								+ ": azItem is: " + azItem + ": eItem is: " + elItem);
						updateFollowActor(azItem, elItem, event.info().eventTime());

						context().become(subscribeReceive(azItem, elItem));
					}
				}).

				match(McsFollowActor.StopFollowing.class, t -> {
					subscribeMonitor.stop();
					// Kill this subscriber
					context().stop(self());
				}).

				matchAny(t -> System.out
						.println("Inside McsEventSubscriber Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This message propagates event to Follow Actor
	 */
	private void updateFollowActor(DoubleItem az, DoubleItem el, EventTime eventTime) {
		log.debug("Inside McsEventSubscriber updateRefActor: Sending Message to Follow Actor");
		followActor
				.ifPresent(actorRef -> actorRef.tell(new McsFollowActor.UpdatedEventData(az, el, eventTime), self()));
	}

	/**
	 * This helps in subscribing to specific events based on config key
	 * 
	 * @param eventService
	 * @return
	 */
	private EventMonitor startupSubscriptions(IEventService eventService) {

		EventMonitor subscribeMonitor = subscribeKeys(eventService, McsConfig.positionDemandCK);

		log.debug("Inside McsEventSubscriber actor: " + subscribeMonitor.actorRef());

		subscribeKeys(subscribeMonitor, McsConfig.offsetDemandCK);

		return subscribeMonitor;
	}

	/**
	 * Props for McsEventSubscriber
	 * 
	 * @param ac
	 * @param refActor
	 * @param eventService
	 * @return
	 */
	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, IEventService eventService) {
		return Props.create(new Creator<McsEventSubscriber>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsEventSubscriber create() throws Exception {
				return new McsEventSubscriber(ac, refActor, eventService);
			}
		});
	}

}
