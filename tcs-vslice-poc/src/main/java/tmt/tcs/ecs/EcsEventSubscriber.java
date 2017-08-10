package tmt.tcs.ecs;

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
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventSubscriber;

/**
 * This Class provides Event Subcription functionality for ECS It extends
 * BaseEventSubscriber
 */
@SuppressWarnings("unused")
public class EcsEventSubscriber extends BaseEventSubscriber {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> refActor;
	private final EventService.EventMonitor subscribeMonitor;

	private EcsEventSubscriber(AssemblyContext assemblyContext, Optional<ActorRef> refActor,
			IEventService eventService) {

		log.debug("Inside EcsEventPublisher");

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
					log.debug("Inside EcsEventPublisher subscribeReceive received an unknown SystemEvent: "
							+ event.info().source());
					updateRefActor();
				}).

				matchAny(t -> System.out
						.println("Inside EcsEventPublisher Unexpected message received:subscribeReceive: " + t))
				.build();
	}

	/**
	 * This message propagates event to Referenced Actor
	 */
	private void updateRefActor() {
		refActor.ifPresent(actoRef -> actoRef.tell(new String("Test"), self()));
	}

	/**
	 * This helps in subscribing to specific events based on config key
	 * 
	 * @param eventService
	 * @return
	 */
	private EventMonitor startupSubscriptions(IEventService eventService) {

		EventMonitor subscribeMonitor = subscribeKeys(eventService, EcsConfig.ecsStateCK);

		log.debug("Inside EcsEventPublisher actor: " + subscribeMonitor.actorRef());

		return subscribeMonitor;
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, IEventService eventService) {
		return Props.create(new Creator<EcsEventSubscriber>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsEventSubscriber create() throws Exception {
				return new EcsEventSubscriber(ac, refActor, eventService);
			}
		});
	}

}
