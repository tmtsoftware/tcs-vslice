package tmt.tcs.m3;

import java.time.Instant;
import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.util.config.DoubleItem;
import csw.util.config.Events.EventTime;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseCommand;
import tmt.tcs.m3.M3FollowActor.SetRotation;
import tmt.tcs.m3.M3FollowActor.SetTilt;
import tmt.tcs.m3.M3FollowActor.UpdatedEventData;

/*
 * This is an actor class which receives command specific to Move Operation
 * And after any modifications if required, redirect the same to M3 HCD
 */
@SuppressWarnings("unused")
public class M3FollowCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private AssemblyContext assemblyContext;
	private final DoubleItem initialRotation;
	private final DoubleItem initialTilt;
	private final Optional<ActorRef> eventPublisher;
	private final IEventService eventService;

	private final ActorRef m3Control;

	final Optional<ActorRef> m3Hcd;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creates hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param assemblyContext
	 * @param initialRotation
	 * @param initialTilt
	 * @param m3Hcd
	 * @param eventPublisher
	 * @param eventService
	 */
	public M3FollowCommand(AssemblyContext assemblyContext, DoubleItem initialRotation, DoubleItem initialTilt,
			Optional<ActorRef> m3Hcd, Optional<ActorRef> eventPublisher, IEventService eventService) {

		this.assemblyContext = assemblyContext;
		this.initialRotation = initialRotation;
		this.initialTilt = initialTilt;
		this.m3Hcd = m3Hcd;
		this.eventPublisher = eventPublisher;
		this.eventService = eventService;

		m3Control = context().actorOf(M3Control.props(assemblyContext, m3Hcd), "m3control");
		ActorRef initialFollowActor = createFollower(initialTilt, initialTilt, m3Control, eventPublisher,
				eventPublisher);
		ActorRef initialEventSubscriber = createEventSubscriber(initialFollowActor, eventService);

		receive(followReceive(initialFollowActor, initialEventSubscriber, m3Hcd));
	}

	public PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor, ActorRef eventSubscriber,
			Optional<ActorRef> m3Hcd) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			log.info("Inside M3FollowCommand followReceive: Receive stop following in Follow Command");
			context().stop(eventSubscriber);
			context().stop(followActor);
			context().stop(self());
		}).match(SetRotation.class, t -> {
			log.debug("Inside M3FollowCommand followReceive: Got Rotation: " + t.rotation);
			followActor.tell(t, sender());

		}).match(SetTilt.class, t -> {
			log.debug("Inside M3FollowCommand followReceive: Got Tilt: " + t.tilt);
			followActor.tell(t, sender());

		}).match(M3Assembly.UpdateHcd.class, upd -> {
			log.debug("Inside M3FollowCommand followReceive: Got UpdateHcd ");
			context().become(followReceive(followActor, eventSubscriber, upd.hcdActorRef));
			m3Control.tell(new M3Assembly.UpdateHcd(upd.hcdActorRef), self());
		}).match(UpdateRotationAndTilt.class, t -> {
			log.debug("Inside M3FollowCommand followReceive: Got UpdateRotationAndTilt ");
			followActor.tell(new UpdatedEventData(t.rotation, t.tilt, new EventTime(Instant.now())), self());
		}).matchAny(t -> log.debug("Inside M3FollowCommand: Unknown message received: " + t)).build();
	}

	private ActorRef createFollower(DoubleItem initialRotation, DoubleItem initialTilt, ActorRef m3Control,
			Optional<ActorRef> eventPublisher, Optional<ActorRef> telemetryPublisher) {
		log.debug("Inside M3FollowCommand createFollower: Creating Follower ");
		return context().actorOf(M3FollowActor.props(assemblyContext, initialRotation, initialTilt,
				Optional.of(m3Control), eventPublisher, eventPublisher), "follower");
	}

	private ActorRef createEventSubscriber(ActorRef followActor, IEventService eventService) {
		log.debug("Inside M3FollowCommand createEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(M3EventSubscriber.props(assemblyContext, Optional.of(followActor), eventService),
				"eventsubscriber");
	}

	public static Props props(AssemblyContext ac, DoubleItem initialRotation, DoubleItem initialTilt,
			Optional<ActorRef> m3Hcd, Optional<ActorRef> eventPublisher, IEventService eventService) {
		return Props.create(new Creator<M3FollowCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3FollowCommand create() throws Exception {
				return new M3FollowCommand(ac, initialRotation, initialTilt, m3Hcd, eventPublisher, eventService);
			}
		});
	}

	interface FollowCommandMessages {
	}

	public static class StopFollowing implements FollowCommandMessages {
	}

	public static class UpdateRotationAndTilt {
		public final DoubleItem rotation;
		public final DoubleItem tilt;

		public UpdateRotationAndTilt(DoubleItem rotation, DoubleItem tilt) {
			this.rotation = rotation;
			this.tilt = tilt;
		}
	}
}
