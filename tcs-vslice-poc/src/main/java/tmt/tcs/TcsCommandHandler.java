package tmt.tcs;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
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
import csw.services.ccs.AssemblyController;
import csw.services.ccs.CommandStatus.CommandStatus;
import csw.services.ccs.SequentialExecutor.ExecuteOne;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.util.config.Configurations;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.ccs.JSequentialExecutor;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor;
import tmt.tcs.common.BaseCommandHandler;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;
import tmt.tcs.web.WebEventSubscriber;

/**
 * This is an actor class which receives commands forwarded by TCS Assembly And
 * based upon the command config key send to specific command actor class
 */
public class TcsCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> allEventPublisher;

	private final ActorRef tcsStateActor;

	private final ActorRef badActorReference;

	private ActorRef mcsRefActor;
	private ActorRef ecsRefActor;
	private ActorRef m3RefActor;
	private ActorRef tpkRefActor;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public TcsCommandHandler(AssemblyContext ac, Optional<ActorRef> mcsRefActor, Optional<ActorRef> ecsRefActor,
			Optional<ActorRef> m3RefActor, Optional<ActorRef> tpkRefActor, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside TcsCommandHandler");

		this.assemblyContext = ac;
		badActorReference = context().system().deadLetters();
		this.mcsRefActor = mcsRefActor.orElse(badActorReference);
		this.ecsRefActor = ecsRefActor.orElse(badActorReference);
		this.m3RefActor = m3RefActor.orElse(badActorReference);
		this.tpkRefActor = tpkRefActor.orElse(badActorReference);
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
			log.debug("Inside TcsCommandHandler handleLocations: actorRef: " + l.getActorRef() + ": prefix is: "
					+ l.prefix());

			if (TcsConfig.mcsPrefix.equals(l.prefix())) {
				log.debug("Inside TcsCommandHandler handleLocations: actorRef for McsAssembly ");
				mcsRefActor = l.getActorRef().orElse(badActorReference);
			} else if (TcsConfig.ecsPrefix.equals(l.prefix())) {
				log.debug("Inside TcsCommandHandler handleLocations: actorRef for EcsAssembly ");
				ecsRefActor = l.getActorRef().orElse(badActorReference);
			} else if (TcsConfig.m3Prefix.equals(l.prefix())) {
				log.debug("Inside TcsCommandHandler handleLocations: actorRef for M3Assembly ");
				m3RefActor = l.getActorRef().orElse(badActorReference);
			} else if (TcsConfig.tpkPrefix.equals(l.prefix())) {
				log.debug("Inside TcsCommandHandler handleLocations: actorRef for TpkAssembly ");
				tpkRefActor = l.getActorRef().orElse(badActorReference);
			} else {
				log.debug("Inside TcsCommandHandler handleLocations: actorRef for Unknown Actor ");
			}
		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside TcsCommandHandler: Received TCP Location: " + t.connection());
			System.out.println("*********Inside TcsCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside TcsCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside TcsCommandHandler: Event Service at: " + eventService);

				createTcsEventSubscriber(eventService.get());

				createWebEventSubscriber(eventService.get());
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

			if (configKey.equals(TcsConfig.initCK)) {
				log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: initCK Command ");
				SetupConfigArg mcsSetupConfigArg = Configurations.createSetupConfigArg("mcsFollowCommand",
						new SetupConfig(McsConfig.initPrefix));

				SetupConfigArg ecsSetupConfigArg = Configurations.createSetupConfigArg("ecsFollowCommand",
						new SetupConfig(EcsConfig.initPrefix));

				SetupConfigArg m3SetupConfigArg = Configurations.createSetupConfigArg("m3FollowCommand",
						new SetupConfig(M3Config.initPrefix));

				log.debug("Inside TcsCommandHandler: Init command: mcsRefActor is: " + mcsRefActor);

				mcsRefActor.tell(new AssemblyController.Submit(mcsSetupConfigArg), self());

				ecsRefActor.tell(new AssemblyController.Submit(ecsSetupConfigArg), self());

				m3RefActor.tell(new AssemblyController.Submit(m3SetupConfigArg), self());

				commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
			} else if (configKey.equals(TcsConfig.followCK)) {
				log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: followCK Command ");
				ActorRef followActorRef = context().actorOf(
						TcsFollowCommand.props(assemblyContext, sc, mcsRefActor, ecsRefActor, m3RefActor, tpkRefActor,
								currentState(), Optional.of(tcsStateActor), eventService.get(), allEventPublisher));
				context().become(actorExecutingReceive(followActorRef, commandOriginator));

				self().tell(JSequentialExecutor.CommandStart(), self());

				commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
			} else if (configKey.equals(TcsConfig.offsetCK)) {
				log.debug("Inside TcsCommandHandler initReceive: ExecuteOne: offsetCK Command ");
				ActorRef offsetActorRef = context().actorOf(
						TcsOffsetCommand.props(assemblyContext, sc, mcsRefActor, ecsRefActor, m3RefActor, tpkRefActor,
								currentState(), Optional.of(tcsStateActor), eventService.get(), allEventPublisher));
				context().become(actorExecutingReceive(offsetActorRef, commandOriginator));

				self().tell(JSequentialExecutor.CommandStart(), self());

				commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
			}

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

			context().become(initReceive());
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, sc -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: SetupConfig is: " + sc);
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside TcsCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside TcsCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	/**
	 * This helps creating TcsEventSubscriber ActorRef when EventService is
	 * being received by Location Handler
	 * 
	 * @param eventService
	 * @return ActorRef
	 */
	private ActorRef createTcsEventSubscriber(IEventService eventService) {
		log.debug("Inside TcsCommandHandler createTcsEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(TcsEventSubscriber.props(assemblyContext, allEventPublisher, eventService),
				"tcseventsubscriber");
	}

	/**
	 * This helps creating WebEventSubscriber ActorRef when EventService is
	 * being received by Location Handler
	 * 
	 * @param eventService
	 * @return ActorRef
	 */
	private ActorRef createWebEventSubscriber(IEventService eventService) {
		log.debug("Inside TcsCommandHandler createWebEventSubscriber: Creating Event Subscriber ");
		return context().actorOf(WebEventSubscriber.props(assemblyContext, Optional.empty(), eventService),
				"webeventsubscriber");
	}

	/**
	 * This helps in creating TcsCommandHandler Prop
	 * 
	 * @param ac
	 * @param mcsRefActor
	 * @param ecsRefActor
	 * @param m3RefActor
	 * @param tpkRefActor
	 * @param allEventPublisher
	 * @return Props
	 */
	public static Props props(AssemblyContext ac, Optional<ActorRef> mcsRefActor, Optional<ActorRef> ecsRefActor,
			Optional<ActorRef> m3RefActor, Optional<ActorRef> tpkRefActor, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<TcsCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsCommandHandler create() throws Exception {
				return new TcsCommandHandler(ac, mcsRefActor, ecsRefActor, m3RefActor, tpkRefActor, allEventPublisher);
			}
		});
	}

}
