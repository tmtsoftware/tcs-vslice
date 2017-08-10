package tmt.tcs;

import static akka.pattern.PatternsCS.ask;
import static javacsw.util.config.JItems.jadd;
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
 * This is an actor class which receives commands forwarded by TCS Assembly
 * And based upon the command config key send to specific command actor class
 */
@SuppressWarnings("unused")
public class TcsCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> allEventPublisher;

	private final ActorRef tcsStateActor;

	private final ActorRef badActorReference;

	private ActorRef refActor;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public TcsCommandHandler(AssemblyContext ac, Optional<ActorRef> refActor, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside TcsCommandHandler");

		this.assemblyContext = ac;
		badActorReference = context().system().deadLetters();
		this.refActor = refActor.orElse(badActorReference);
		this.allEventPublisher = allEventPublisher;
		tcsStateActor = context().actorOf(AssemblyStateActor.props());

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
			log.debug("Inside TcsCommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
			refActor = l.getActorRef().orElse(badActorReference);
		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside TcsCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside TcsCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside TcsCommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside TcsCommandHandler: Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				eventService = badEventService;
		} else {
			log.debug("Inside TcsCommandHandler: CommandHandler received some other location: " + location);
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

			log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc + ": configKey is: "
					+ configKey);

			if (configKey.equals(TcsConfig.positionDemandCK)) {
				log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: moveCK Command ");
				ActorRef moveActorRef = context().actorOf(TcsMoveCommand.props(assemblyContext, sc, refActor,
						currentState(), Optional.of(tcsStateActor)));
				context().become(actorExecutingReceive(moveActorRef, commandOriginator));
			} else if (configKey.equals(TcsConfig.offsetDemandCK)) {
				log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: offsetCK Command ");
				ActorRef offsetActorRef = context().actorOf(TcsOffsetCommand.props(assemblyContext, sc, refActor,
						currentState(), Optional.of(tcsStateActor)));
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
			log.debug("Inside TcsCommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside TcsCommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside TcsCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> refActor, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<TcsCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsCommandHandler create() throws Exception {
				return new TcsCommandHandler(ac, refActor, allEventPublisher);
			}
		});
	}

	/**
	 * Based upon command parameters being passed to move command this helps in
	 * generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @return
	 */
	public static DemandMatcher posMatcher() {
		System.out.println("Inside TcsCommandHandler posMatcher : Starts");

		DemandState ds = jadd(new DemandState(TcsConfig.tcsStateCK.prefix()));
		return new DemandMatcher(ds, false);
	}

}
