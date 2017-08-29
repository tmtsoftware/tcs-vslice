package tmt.tcs.mcs;

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
import tmt.tcs.mcs.McsFollowActor.SetAzimuth;
import tmt.tcs.mcs.McsFollowActor.SetElevation;
import tmt.tcs.mcs.McsFollowActor.UpdatedEventData;

/**
 * This is an actor class which receives command specific to Move Operation And
 * after any modifications if required, redirect the same to MCS HCD
 */
@SuppressWarnings("unused")
public class McsFollowCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private AssemblyContext assemblyContext;
	private final DoubleItem initialAzimuth;
	private final DoubleItem initialElevation;
	private final Optional<ActorRef> eventPublisher;
	private final IEventService eventService;

	private final ActorRef mcsControl;

	final Optional<ActorRef> mcsHcd;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creates hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param assemblyContext
	 * @param initialAzimuth
	 * @param initialElevation
	 * @param mcsHcd
	 * @param eventPublisher
	 * @param eventService
	 */
	public McsFollowCommand(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsHcd, Optional<ActorRef> eventPublisher, IEventService eventService) {

		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.mcsHcd = mcsHcd;
		this.eventPublisher = eventPublisher;
		this.eventService = eventService;

		mcsControl = context().actorOf(McsControl.props(assemblyContext, mcsHcd), "mcscontrol");
		ActorRef initialFollowActor = createFollower(initialAzimuth, initialElevation, mcsControl, eventPublisher,
				eventPublisher);
		ActorRef initialEventSubscriber = createEventSubscriber(initialFollowActor, eventService);

		receive(followReceive(initialFollowActor, initialEventSubscriber, mcsHcd));
	}

	public PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor, ActorRef eventSubscriber,
			Optional<ActorRef> mcsHcd) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			log.info("Inside McsFollowCommand followReceive: Receive stop following in Follow Command");
			context().stop(eventSubscriber);
			context().stop(followActor);
			context().stop(self());
		}).match(SetAzimuth.class, t -> {
			log.debug("Inside McsFollowCommand followReceive: Got Azimuth: " + t.azimuth);
			followActor.tell(t, sender());

		}).match(SetElevation.class, t -> {
			log.debug("Inside McsFollowCommand followReceive: Got Elevation: " + t.elevation);
			followActor.tell(t, sender());

		}).match(McsAssembly.UpdateHcd.class, upd -> {
			log.debug("Inside McsFollowCommand followReceive: Got UpdateHcd ");
			context().become(followReceive(followActor, eventSubscriber, upd.hcdActorRef));
			mcsControl.tell(new McsAssembly.UpdateHcd(upd.hcdActorRef), self());
		}).match(UpdateAZandEL.class, t -> {
			log.debug("Inside McsFollowCommand followReceive: Got UpdateAZandEL ");
			followActor.tell(new UpdatedEventData(t.az, t.el, new EventTime(Instant.now())), self());
		}).matchAny(t -> log.debug("Inside McsFollowCommand: Unknown message received: " + t)).build();
	}

	private ActorRef createFollower(DoubleItem initialAzimuth, DoubleItem initialElevation, ActorRef mcsControl,
			Optional<ActorRef> eventPublisher, Optional<ActorRef> telemetryPublisher) {
		log.debug("Inside McsFollowCommand createFollower: Creating Follower ");
		return context().actorOf(McsFollowActor.props(assemblyContext, initialAzimuth, initialElevation,
				Optional.of(mcsControl), eventPublisher), "follower");
	}

	private ActorRef createEventSubscriber(ActorRef followActor, IEventService eventService) {
		log.debug("Inside McsFollowCommand createEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(McsEventSubscriber.props(assemblyContext, Optional.of(followActor), eventService),
				"mcseventsubscriber");
	}

	public static Props props(AssemblyContext ac, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsHcd, Optional<ActorRef> eventPublisher, IEventService eventService) {
		return Props.create(new Creator<McsFollowCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsFollowCommand create() throws Exception {
				return new McsFollowCommand(ac, initialAzimuth, initialElevation, mcsHcd, eventPublisher, eventService);
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
