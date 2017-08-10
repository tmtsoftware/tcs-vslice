package tmt.tcs.m3;

import static akka.pattern.PatternsCS.ask;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static scala.compat.java8.OptionConverters.toJava;

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
import tmt.tcs.common.BaseCommandHandler;

/*
 * This is an actor class which receives commands forwarded by M3 Assembly
 * And based upon the command config key send to specific command actor class
 */
@SuppressWarnings("unused")
public class M3CommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> allEventPublisher;

	private final ActorRef m3StateActor;

	private final ActorRef badHcdReference;

	private ActorRef m3Hcd;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public M3CommandHandler(AssemblyContext ac, Optional<ActorRef> m3Hcd, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside M3CommandHandler");

		this.assemblyContext = ac;
		badHcdReference = context().system().deadLetters();
		this.m3Hcd = m3Hcd.orElse(badHcdReference);
		this.allEventPublisher = allEventPublisher;
		m3StateActor = context().actorOf(AssemblyStateActor.props());

		subscribeToLocationUpdates();

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
			log.debug("Inside M3CommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
			m3Hcd = l.getActorRef().orElse(badHcdReference);

		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside M3CommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside M3CommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside M3CommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside M3CommandHandler: Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				eventService = badEventService;
			if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
				m3Hcd = badHcdReference;

		} else {
			log.debug("Inside M3CommandHandler: CommandHandler received some other location: " + location);
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
		return ReceiveBuilder.match(Location.class, this::handleLocations).match(ExecuteOne.class, t -> {

			SetupConfig sc = t.sc();
			Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
			ConfigKey configKey = sc.configKey();

			log.debug("Inside M3CommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc + ": configKey is: "
					+ configKey);

			if (configKey.equals(M3Config.positionDemandCK)) {
				log.debug("Inside M3CommandHandler initReceive: ExecuteOne: moveCK Command ");
				ActorRef moveActorRef = context().actorOf(
						M3MoveCommand.props(assemblyContext, sc, m3Hcd, currentState(), Optional.of(m3StateActor)));
				context().become(actorExecutingReceive(moveActorRef, commandOriginator));
			} else if (configKey.equals(M3Config.offsetDemandCK)) {
				log.debug("Inside M3CommandHandler initReceive: ExecuteOne: offsetCK Command ");
				ActorRef offsetActorRef = context().actorOf(
						M3OffsetCommand.props(assemblyContext, sc, m3Hcd, currentState(), Optional.of(m3StateActor)));
				context().become(actorExecutingReceive(offsetActorRef, commandOriginator));
			}

			self().tell(JSequentialExecutor.CommandStart(), self());
		}).build();
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
			log.debug("Inside M3CommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside M3CommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside M3CommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside M3CommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside M3CommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside M3CommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> m3Hcd, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<M3CommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3CommandHandler create() throws Exception {
				return new M3CommandHandler(ac, m3Hcd, allEventPublisher);
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
		System.out.println("Inside M3CommandHandler posMatcher Move: Starts");

		DemandState ds = jadd(new DemandState(M3Config.m3StateCK.prefix()), jset(M3Config.rotation, az),
				jset(M3Config.tilt, el), jset(M3Config.time, time));
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
	public static DemandMatcher posMatcher(double x, double y) {
		System.out.println("Inside M3CommandHandler posMatcher Offset: Starts");

		DemandState ds = jadd(new DemandState(M3Config.m3StateCK.prefix()), jset(M3Config.rotation, x), jset(M3Config.tilt, y));
		return new DemandMatcher(ds, false);
	}

}
