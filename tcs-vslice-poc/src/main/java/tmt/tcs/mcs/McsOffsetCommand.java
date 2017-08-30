package tmt.tcs.mcs;

import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.util.config.DoubleItem;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseCommand;
import tmt.tcs.mcs.McsFollowCommand.StopFollowing;

/**
 * This is an actor class which receives command specific to Offset Operation
 * And after any modifications if required, redirect the same to MCS HCD
 */
@SuppressWarnings("unused")
public class McsOffsetCommand extends BaseCommand {
	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private AssemblyContext assemblyContext;
	private final DoubleItem initialAzimuth;
	private final DoubleItem initialElevation;
	private final Optional<ActorRef> eventPublisher;
	private final IEventService eventService;
	private final Optional<ActorRef> mcsStateActor;
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
	public McsOffsetCommand(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsHcd, Optional<ActorRef> eventPublisher, IEventService eventService,
			Optional<ActorRef> mcsStateActor) {

		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.mcsHcd = mcsHcd;
		this.eventPublisher = eventPublisher;
		this.eventService = eventService;
		this.mcsStateActor = mcsStateActor;

		mcsControl = context().actorOf(McsControl.props(assemblyContext, mcsHcd), "mcscontrol");
		ActorRef initialFollowActor = createFollower(initialAzimuth, initialElevation, mcsControl, eventPublisher,
				eventPublisher, mcsStateActor);
		ActorRef initialEventSubscriber = createEventSubscriber(initialFollowActor, eventService);

		receive(followReceive(initialFollowActor, initialEventSubscriber, mcsHcd));
	}

	public PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor, ActorRef eventSubscriber,
			Optional<ActorRef> mcsHcd) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			log.info("Inside McsOffsetCommand followReceive: Receive stop following in Follow Command");
			context().stop(eventSubscriber);
			context().stop(followActor);
			context().stop(self());
		}).matchAny(t -> log.debug("Inside McsOffsetCommand: Unknown message received: " + t)).build();
	}

	private ActorRef createFollower(DoubleItem initialAzimuth, DoubleItem initialElevation, ActorRef mcsControl,
			Optional<ActorRef> eventPublisher, Optional<ActorRef> telemetryPublisher,
			Optional<ActorRef> mcsStateActor) {
		log.debug("Inside McsOffsetCommand createFollower: Creating Follower ");
		return context().actorOf(McsFollowActor.props(assemblyContext, initialAzimuth, initialElevation,
				Optional.of(mcsControl), eventPublisher, mcsStateActor), "follower");
	}

	private ActorRef createEventSubscriber(ActorRef followActor, IEventService eventService) {
		log.debug("Inside McsOffsetCommand createEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(McsEventSubscriber.props(assemblyContext, Optional.of(followActor), eventService),
				"mcseventsubscriber");
	}

	public static Props props(AssemblyContext ac, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsHcd, Optional<ActorRef> eventPublisher, IEventService eventService,
			Optional<ActorRef> mcsStateActor) {
		return Props.create(new Creator<McsOffsetCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsOffsetCommand create() throws Exception {
				return new McsOffsetCommand(ac, initialAzimuth, initialElevation, mcsHcd, eventPublisher, eventService,
						mcsStateActor);
			}
		});
	}
}
