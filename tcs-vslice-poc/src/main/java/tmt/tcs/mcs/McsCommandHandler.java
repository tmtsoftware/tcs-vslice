package tmt.tcs.mcs;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static scala.compat.java8.OptionConverters.toJava;
import static tmt.tcs.common.AssemblyStateActor.azDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.elDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.mcs.McsConfig.MCS_IDLE;
import static tmt.tcs.mcs.McsConfig.mcsStateKey;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus.CommandStatus;
import csw.services.ccs.DemandMatcher;
import csw.services.ccs.SequentialExecutor.ExecuteOne;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.StateVariable.DemandState;
import javacsw.services.ccs.JSequentialExecutor;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.common.BaseCommandHandler;

/*
 * This is an actor class which receives commands forwarded by MCS Assembly
 * And based upon the command config key send to specific command actor class
 */
public class McsCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	@SuppressWarnings("unused")
	private final Optional<ActorRef> allEventPublisher;

	private final ActorRef mcsStateActor;

	private final ActorRef badHcdReference;

	private ActorRef mcsHcd;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public McsCommandHandler(AssemblyContext ac, Optional<ActorRef> mcsHcd, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside McsCommandHandler");

		this.assemblyContext = ac;
		badHcdReference = context().system().deadLetters();
		this.mcsHcd = mcsHcd.orElse(badHcdReference);
		this.allEventPublisher = allEventPublisher;
		mcsStateActor = context().actorOf(AssemblyStateActor.props());

		subscribeToLocationUpdates();
		context().system().eventStream().subscribe(self(), AssemblyState.class);

		receive(initReceive());
	}

	/**
	 * This method handles the locations
	 * 
	 * @param location
	 */
	private void handleLocations(Location location) {
		if (location instanceof ResolvedAkkaLocation) {
			ResolvedAkkaLocation l = (ResolvedAkkaLocation) location;
			log.debug("Inside McsCommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
			mcsHcd = l.getActorRef().orElse(badHcdReference);

		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside McsCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside McsCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside McsCommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside McsCommandHandler: Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				eventService = badEventService;
			if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
				mcsHcd = badHcdReference;

		} else {
			log.debug("Inside McsCommandHandler: CommandHandler received some other location: " + location);
		}
	}

	/**
	 * Based upon the request being received this helps in handling locations,
	 * command configs And based upon config key, command is forwarded to
	 * specific command actor
	 * 
	 * @return
	 */
	private PartialFunction<Object, BoxedUnit> initReceive() {
		return stateReceive()
				.orElse(ReceiveBuilder.match(Location.class, this::handleLocations).match(ExecuteOne.class, t -> {

					SetupConfig sc = t.sc();
					Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
					ConfigKey configKey = sc.configKey();

					log.debug("Inside McsCommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc
							+ ": configKey is: " + configKey);

					if (configKey.equals(McsConfig.initCK)) {
						log.info(
								"Inside McsCommandHandler initReceive: Init not fully implemented -- only sets state ready!");
						try {
							ask(mcsStateActor, new AssemblySetState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)),
									5000).toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside McsCommandHandler Error setting state");
						}
						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

					} else if (configKey.equals(McsConfig.positionDemandCK)) {
						log.debug("Inside McsCommandHandler initReceive: ExecuteOne: moveCK Command ");
						ActorRef moveActorRef = context().actorOf(McsFollowCommand.props(assemblyContext, sc, mcsHcd,
								currentState(), Optional.of(mcsStateActor)));
						context().become(actorExecutingReceive(moveActorRef, commandOriginator));
						self().tell(JSequentialExecutor.CommandStart(), self());
					} else if (configKey.equals(McsConfig.offsetDemandCK)) {
						log.debug("Inside McsCommandHandler initReceive: ExecuteOne: offsetCK Command ");
						ActorRef offsetActorRef = context().actorOf(McsOffsetCommand.props(assemblyContext, sc, mcsHcd,
								currentState(), Optional.of(mcsStateActor)));
						context().become(actorExecutingReceive(offsetActorRef, commandOriginator));
						self().tell(JSequentialExecutor.CommandStart(), self());
					}
				}).build());
	}

	/**
	 * This method helps in executing receive operation
	 * 
	 * @param currentCommand
	 * @param commandOriginator
	 * @return
	 */
	private PartialFunction<Object, BoxedUnit> actorExecutingReceive(ActorRef currentCommand,
			Optional<ActorRef> commandOriginator) {
		Timeout timeout = new Timeout(5, TimeUnit.SECONDS);

		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			log.debug("Inside McsCommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside McsCommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside McsCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> mcsHcd, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<McsCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsCommandHandler create() throws Exception {
				return new McsCommandHandler(ac, mcsHcd, allEventPublisher);
			}
		});
	}

	/**
	 * Based upon command parameters being passed to move command this helps in
	 * generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param az
	 * @param el
	 * @param time
	 * @return DemandMatcher
	 */
	public static DemandMatcher posMatcher(double az, double el, double time) {
		System.out.println("Inside McsCommandHandler posMatcher Move: Starts");

		DemandState ds = jadd(new DemandState(McsConfig.mcsStatePrefix), jset(mcsStateKey, MCS_IDLE));

		System.out.println("Inside McsCommandHandler posMatcher Move: DemandState is: " + ds);
		return new DemandMatcher(ds, false);
	}

	/**
	 * Based upon command parameters being passed to offset command this helps
	 * in generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param x
	 * @param y
	 * @return DemandMatcher
	 */
	public static DemandMatcher posMatcher(double az, double el) {
		System.out.println("Inside McsCommandHandler posMatcher Offset: Starts");

		DemandState ds = jadd(new DemandState(McsConfig.mcsStatsPrefix), jset(McsConfig.az, az),
				jset(McsConfig.el, el));
		return new DemandMatcher(ds, false);
	}

}
