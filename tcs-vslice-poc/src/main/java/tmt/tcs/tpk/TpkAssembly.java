package tmt.tcs.tpk;

import static javacsw.services.pkg.JSupervisor.DoRestart;
import static javacsw.services.pkg.JSupervisor.DoShutdown;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.services.pkg.JSupervisor.RunningOffline;
import static javacsw.services.pkg.JSupervisor.ShutdownComplete;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.ccs.SequentialExecutor;
import csw.services.ccs.Validation;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.ResolvedHttpLocation;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.services.loc.LocationService.UnTrackedLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.services.loc.LocationSubscriberActor;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.alarms.IAlarmService;
import javacsw.services.ccs.JAssemblyController;
import javacsw.services.ccs.JValidation;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.loc.JLocationSubscriberActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * Top Level Actor for TPK Assembly
 *
 * TpkAssembly starts up the component doing the following: creating all needed
 * actors, handling initialization, participating in life cycle with Supervisor,
 * handles locations for distribution throughout component receives commands and
 * forwards them to the CommandHandler by extending JAssemblyController
 */
public class TpkAssembly extends JAssemblyController {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;
	private ActorRef commandHandler;

	private final Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public TpkAssembly(Component.AssemblyInfo info, ActorRef supervisor) {
		super(info);
		this.supervisor = supervisor;

		log.debug("Inside TpkAssembly Constructor");

		initialize(info);

		receive(initializingReceive());
	}

	/**
	 * Method for Initializing Command Handlers, Location Subscription etc at
	 * Assembly initialization
	 */
	private void initialize(Component.AssemblyInfo info) {
		try {
			ActorRef trackerSubscriber = context().actorOf(LocationSubscriberActor.props());
			trackerSubscriber.tell(JLocationSubscriberActor.Subscribe, self());

			// This actor handles all telemetry and system event publishing
			ActorRef eventPublisher = context().actorOf(TpkEventPublisher.props(Optional.empty(), Optional.empty()));

			// Setup command handler for assembly
			commandHandler = context().actorOf(TpkCommandHandler.props(Optional.of(eventPublisher)));

			// This tracks required services
			LocationSubscriberActor.trackConnection(IEventService.eventServiceConnection(), trackerSubscriber);

		} catch (Exception ex) {
			supervisor.tell(new Supervisor.InitializeFailure(ex.getMessage()), self());
		}
	}

	/**
	 * This contains only commands that can be received during intialization
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> initializingReceive() {
		return locationReceive().orElse(ReceiveBuilder.matchEquals(Running, location -> {
			// When Running is received, transition to running Receive
			log.debug("Inside TpkAssembly initializingReceive");
			context().become(runningReceive());
		}).matchAny(t -> log.debug("Unexpected message in TpkAssembly:initializingReceive: " + t)).build());
	}

	/**
	 * This receives locations from locations and resolves connections
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> locationReceive() {
		return ReceiveBuilder.match(Location.class, location -> {
			if (location instanceof ResolvedAkkaLocation) {
				ResolvedAkkaLocation l = (ResolvedAkkaLocation) location;
				log.debug("Inside TpkAssembly locationReceive: actorRef: " + l.getActorRef());

			} else if (location instanceof ResolvedHttpLocation) {
				log.debug("Inside TpkAssembly locationReceive: HTTP Service is: " + location.connection());

			} else if (location instanceof ResolvedTcpLocation) {
				ResolvedTcpLocation t = (ResolvedTcpLocation) location;
				log.debug("Inside TpkAssembly locationReceive: TCP Location is: " + t.connection());

				if (location.connection().equals(IEventService.eventServiceConnection())) {
					log.debug("Inside TpkAssembly locationReceive: ES connection is: " + t);
					eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
					log.debug("Inside TpkAssembly Event Service at: " + eventService);

					supervisor.tell(Initialized, self());
				}

				if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
					log.debug("Inside TpkAssembly locationReceive: TS connection is: " + t);
				}

				if (location.connection().equals(IAlarmService.alarmServiceConnection(IAlarmService.defaultName))) {
					log.debug("Inside TpkAssembly locationReceive: AS connection is: " + t);
				}

			} else if (location instanceof Unresolved) {
				log.debug("Inside TpkAssembly locationReceive: Unresolved location: " + location.connection());

			} else if (location instanceof UnTrackedLocation) {
				log.debug("Inside TpkAssembly locationReceive: UnTracked location: " + location.connection());

			} else {
				log.debug("Inside TpkAssembly locationReceive: Unknown connection: " + location.connection()); // XXX
			}
		}).build();
	}

	/**
	 * This is used when in Running state
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> runningReceive() {
		return locationReceive().orElse(controllerReceive()).orElse(lifecycleReceivePF(supervisor))
				.orElse(unhandledPF());
	}

	/**
	 * This tracks the life cycle of Assembly
	 *
	 * @return a partial function
	 */
	public PartialFunction<Object, BoxedUnit> lifecycleReceivePF(ActorRef supervisor) {
		return ReceiveBuilder.matchEquals(Running, t -> {
			// Already running so ignore
			log.debug("Inside BaseAssembly lifecycleReceivePF: Already running");
		}).matchEquals(RunningOffline, t -> {
			log.debug("Inside BaseAssembly lifecycleReceivePF: running offline");
		}).matchEquals(DoRestart, t -> log.debug("Inside BaseAssembly lifecycleReceivePF: dorestart"))
				.matchEquals(DoShutdown, t -> {
					log.debug("Inside BaseAssembly lifecycleReceivePF: doshutdown");
					supervisor.tell(ShutdownComplete, self());
				}).match(Supervisor.LifecycleFailureInfo.class, t -> {
					log.debug("Inside BaseAssembly lifecycleReceivePF: failed lifecycle state: " + t.state()
							+ " for reason: " + t.reason());
				}).build();
	}

	/**
	 * This Catches all unhandled message received
	 *
	 * @return a partial function
	 */
	public PartialFunction<Object, BoxedUnit> unhandledPF() {
		return ReceiveBuilder.matchAny(t -> log.error("Inside BaseAssembly Unexpected message:unhandledPF: " + t))
				.build();
	}

	/**
	 * This overrides JAssemblyController setup function which processes
	 * incoming SetupConfigArg messages and redirects the same to Command Handler
	 * 
	 * @param sca
	 *            received SetupConfgiArg
	 * @param commandOriginator
	 *            the sender of the command
	 * @return a validation object that indicates if the received config is
	 *         valid
	 */
	@SuppressWarnings("unused")
	@Override
	public List<Validation.Validation> setup(SetupConfigArg sca, Optional<ActorRef> commandOriginator) {
		log.debug("Inside TpkAssembly: setup SetupConfigArg is : " + sca);
		List<Validation.Validation> validations = validateSequenceConfigArg(sca);
		if (Validation.isAllValid(validations)) {
			ActorRef executor = newExecutor(commandHandler, sca, commandOriginator);
		}
		return validations;
	}

	/**
	 * This Validates received config arg
	 */
	private List<Validation.Validation> validateSequenceConfigArg(SetupConfigArg sca) {
		// Are all of the configs really for us and correctly formatted, etc?
		return validateSetupConfigArg(sca);
	}

	/**
	 * This Runs validation on a single SetupConfig.
	 */
	public static Validation.Validation validateOneSetupConfig(SetupConfig sc) {
		// TODO: For now returning Valid, add specific validations
		return JValidation.Valid;
	}

	/**
	 * This Validates SetupConfigArg
	 */
	public static List<Validation.Validation> validateSetupConfigArg(SetupConfigArg sca) {
		List<Validation.Validation> result = new ArrayList<>();
		for (SetupConfig config : sca.getConfigs()) {
			result.add(validateOneSetupConfig(config));
		}
		return result;
	}

	/**
	 * This is a convenience method to create a new SequentialExecutor
	 */
	public ActorRef newExecutor(ActorRef commandHandler, SetupConfigArg sca, Optional<ActorRef> commandOriginator) {
		return context().actorOf(SequentialExecutor.props(commandHandler, sca, commandOriginator));
	}

	public static Props props(Component.AssemblyInfo assemblyInfo, ActorRef supervisor) {
		return Props.create(new Creator<TpkAssembly>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TpkAssembly create() throws Exception {
				return new TpkAssembly(assemblyInfo, supervisor);
			}
		});
	}

}
