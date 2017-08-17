package tmt.tcs.ecs;

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
import tmt.tcs.ecs.EcsFollowActor.SetAzimuth;
import tmt.tcs.ecs.EcsFollowActor.SetElevation;
import tmt.tcs.ecs.EcsFollowActor.UpdatedEventData;

/**
 * This is an actor class which receives command specific to Move Operation And
 * after any modifications if required, redirect the same to ECS HCD
 */
@SuppressWarnings("unused")
public class EcsFollowCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private AssemblyContext assemblyContext;
	private final DoubleItem initialAzimuth;
	private final DoubleItem initialElevation;
	private final Optional<ActorRef> eventPublisher;
	private final IEventService eventService;

	private final ActorRef ecsControl;

	final Optional<ActorRef> ecsHcd;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creates hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param assemblyContext
	 * @param initialAzimuth
	 * @param initialElevation
	 * @param ecsHcd
	 * @param eventPublisher
	 * @param eventService
	 */
	public EcsFollowCommand(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> ecsHcd, Optional<ActorRef> eventPublisher, IEventService eventService) {

		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.ecsHcd = ecsHcd;
		this.eventPublisher = eventPublisher;
		this.eventService = eventService;

		ecsControl = context().actorOf(EcsControl.props(assemblyContext, ecsHcd), "ecscontrol");
		ActorRef initialFollowActor = createFollower(initialElevation, initialElevation, ecsControl, eventPublisher,
				eventPublisher);
		ActorRef initialEventSubscriber = createEventSubscriber(initialFollowActor, eventService);

		receive(followReceive(initialFollowActor, initialEventSubscriber, ecsHcd));
	}

	public PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor, ActorRef eventSubscriber,
			Optional<ActorRef> ecsHcd) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			log.info("Inside EcsFollowCommand followReceive: Receive stop following in Follow Command");
			context().stop(eventSubscriber);
			context().stop(followActor);
			context().stop(self());
		}).match(SetAzimuth.class, t -> {
			log.debug("Inside EcsFollowCommand followReceive: Got Azimuth: " + t.azimuth);
			followActor.tell(t, sender());

		}).match(SetElevation.class, t -> {
			log.debug("Inside EcsFollowCommand followReceive: Got Elevation: " + t.elevation);
			followActor.tell(t, sender());

		}).match(EcsAssembly.UpdateHcd.class, upd -> {
			log.debug("Inside EcsFollowCommand followReceive: Got UpdateHcd ");
			context().become(followReceive(followActor, eventSubscriber, upd.hcdActorRef));
			ecsControl.tell(new EcsAssembly.UpdateHcd(upd.hcdActorRef), self());
		}).match(UpdateAZandEL.class, t -> {
			log.debug("Inside EcsFollowCommand followReceive: Got UpdateAZandEL ");
			followActor.tell(new UpdatedEventData(t.az, t.el, new EventTime(Instant.now())), self());
		}).matchAny(t -> log.debug("Inside EcsFollowCommand: Unknown message received: " + t)).build();
	}

	private ActorRef createFollower(DoubleItem initialAzimuth, DoubleItem initialElevation, ActorRef ecsControl,
			Optional<ActorRef> eventPublisher, Optional<ActorRef> telemetryPublisher) {
		log.debug("Inside EcsFollowCommand createFollower: Creating Follower ");
		return context().actorOf(EcsFollowActor.props(assemblyContext, initialAzimuth, initialElevation,
				Optional.of(ecsControl), eventPublisher, eventPublisher), "follower");
	}

	private ActorRef createEventSubscriber(ActorRef followActor, IEventService eventService) {
		log.debug("Inside EcsFollowCommand createEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(EcsEventSubscriber.props(assemblyContext, Optional.of(followActor), eventService),
				"eventsubscriber");
	}

	public static Props props(AssemblyContext ac, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> ecsHcd, Optional<ActorRef> eventPublisher, IEventService eventService) {
		return Props.create(new Creator<EcsFollowCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsFollowCommand create() throws Exception {
				return new EcsFollowCommand(ac, initialAzimuth, initialElevation, ecsHcd, eventPublisher, eventService);
			}
		});
	}

	interface FollowCommandMessages {
	}

	public static class StopFollowing implements FollowCommandMessages {
	}

	public static class UpdateAZandEL {
		public final DoubleItem az;
		public final DoubleItem el;

		public UpdateAZandEL(DoubleItem az, DoubleItem el) {
			this.az = az;
			this.el = el;
		}
	}
}
