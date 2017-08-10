package tmt.tcs.ecs;

import static javacsw.services.pkg.JSupervisor.DoRestart;
import static javacsw.services.pkg.JSupervisor.DoShutdown;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.services.pkg.JSupervisor.RunningOffline;
import static javacsw.services.pkg.JSupervisor.ShutdownComplete;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.ccs.AssemblyMessages;
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
import javacsw.services.ccs.JAssemblyMessages;
import javacsw.services.ccs.JValidation;
import javacsw.services.cs.akka.JConfigServiceClient;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.loc.JLocationSubscriberActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseAssembly;

/**
 * Top Level Actor for ECS Assembly
 *
 * EcsAssembly starts up the component doing the following: creating all needed
 * actors, handling initialization, participating in life cycle with Supervisor,
 * handles locations for distribution throughout component receives commands and
 * forwards them to the CommandHandler by extending the BaseAssembly
 */
@SuppressWarnings("unused")
public class EcsAssembly extends BaseAssembly {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);
	
	private final ActorRef supervisor;
	private final AssemblyContext assemblyContext;
	private ActorRef commandHandler;

	private Optional<ActorRef> badHcdReference = Optional.empty();
	private Optional<ActorRef> ecsHcd = badHcdReference;

	private final Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	private final Optional<ITelemetryService> badTelemetryService = Optional.empty();
	private Optional<ITelemetryService> telemetryService = badTelemetryService;

	private ActorRef diagPublsher;

	public static File ecsConfigFile = new File("ecs/assembly/ecsAssembly.conf");
	public static File resource = new File("ecsAssembly.conf");

	public EcsAssembly(Component.AssemblyInfo info, ActorRef supervisor) {
		super(info);
		this.supervisor = supervisor;

		log.debug("Inside EcsAssembly Constructor");

		assemblyContext = initialize(info);

		receive(initializingReceive());
	}

	/**
	 * Method for Initializing Command Handlers, Location Subscription etc at
	 * Assembly initialization
	 */
	private AssemblyContext initialize(Component.AssemblyInfo info) {
		try {
			// TODO:: Uncomment later, Commented for now as Config Service gives
			// time Out
			// Config config = getAssemblyConfigs();

			AssemblyContext assemblyContext = new AssemblyContext(info);

			log.debug("Inside EcsAssembly initialize: Connections: " + info.connections());

			ActorRef trackerSubscriber = context().actorOf(LocationSubscriberActor.props());
			trackerSubscriber.tell(JLocationSubscriberActor.Subscribe, self());

			ActorRef eventPublisher = context()
					.actorOf(EcsEventPublisher.props(assemblyContext, Optional.empty(), Optional.empty()));

			commandHandler = context()
					.actorOf(EcsCommandHandler.props(assemblyContext, ecsHcd, Optional.of(eventPublisher)));

			diagPublsher = context()
					.actorOf(EcsDiagnosticPublisher.props(assemblyContext, ecsHcd, Optional.of(eventPublisher)));

			LocationSubscriberActor.trackConnections(info.connections(), trackerSubscriber);
			LocationSubscriberActor.trackConnection(IEventService.eventServiceConnection(), trackerSubscriber);
			LocationSubscriberActor.trackConnection(ITelemetryService.telemetryServiceConnection(), trackerSubscriber);
			LocationSubscriberActor.trackConnection(IAlarmService.alarmServiceConnection(), trackerSubscriber);

			return assemblyContext;

		} catch (Exception ex) {
			supervisor.tell(new Supervisor.InitializeFailure(ex.getMessage()), self());
			return null;
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
			log.debug("Inside EcsAssembly initializingReceive");
			context().become(runningReceive());
		}).matchAny(t -> log.debug("Unexpected message in EcsAssembly:initializingReceive: " + t)).build());
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
				log.debug("Inside EcsAssembly locationReceive: actorRef: " + l.getActorRef());
				ecsHcd = l.getActorRef();
				supervisor.tell(Initialized, self());

			} else if (location instanceof ResolvedHttpLocation) {
				log.debug("Inside EcsAssembly locationReceive: HTTP Service is: " + location.connection());

			} else if (location instanceof ResolvedTcpLocation) {
				ResolvedTcpLocation t = (ResolvedTcpLocation) location;
				log.debug("Inside EcsAssembly locationReceive: TCP Location is: " + t.connection());

				if (location.connection().equals(IEventService.eventServiceConnection())) {
					log.debug("Inside EcsAssembly locationReceive: ES connection is: " + t);
					eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
					log.debug("Inside EcsAssembly Event Service at: " + eventService);
				}

				if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
					log.debug("Inside EcsAssembly locationReceive: TS connection is: " + t);
					telemetryService = Optional
							.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
					log.debug("Inside EcsAssembly Telemetry Service at: " + telemetryService);
				}

				if (location.connection().equals(IAlarmService.alarmServiceConnection(IAlarmService.defaultName))) {
					log.debug("Inside EcsAssembly locationReceive: AS connection is: " + t);
				}

			} else if (location instanceof Unresolved) {
				log.debug("Inside EcsAssembly locationReceive: Unresolved location: " + location.connection());
				if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
					ecsHcd = badHcdReference;

			} else if (location instanceof UnTrackedLocation) {
				log.debug("Inside EcsAssembly locationReceive: UnTracked location: " + location.connection());

			} else {
				log.debug("Inside EcsAssembly locationReceive: Unknown connection: " + location.connection()); // XXX
			}
		}).build();
	}

	/**
	 * This is used when in Running state
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> runningReceive() {
		return locationReceive().orElse(diagReceive()).orElse(controllerReceive()).orElse(lifecycleReceivePF(supervisor))
				.orElse(unhandledPF());
	}

	/**
	 * This overrides JAssemblyController setup function which processes
	 * incoming SetupConfigArg messages
	 * 
	 * @param sca
	 *            received SetupConfgiArg
	 * @param commandOriginator
	 *            the sender of the command
	 * @return a validation object that indicates if the received config is
	 *         valid
	 */
	@Override
	public List<Validation.Validation> setup(SetupConfigArg sca, Optional<ActorRef> commandOriginator) {
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
		return validateEcsSetupConfigArg(sca, assemblyContext);
	}

	/**
	 * This Runs Ecs-specific validation on a single SetupConfig.
	 */
	public static Validation.Validation validateOneSetupConfig(SetupConfig sc, AssemblyContext ac) {
		// TODO: For now returning Valid, add specific valiations
		return JValidation.Valid;
	}

	/**
	 * This Validates SetupConfigArg for Ecs Assembly
	 */
	public static List<Validation.Validation> validateEcsSetupConfigArg(SetupConfigArg sca, AssemblyContext ac) {
		List<Validation.Validation> result = new ArrayList<>();
		for (SetupConfig config : sca.getConfigs()) {
			result.add(validateOneSetupConfig(config, ac));
		}
		return result;
	}

	public static Props props(Component.AssemblyInfo assemblyInfo, ActorRef supervisor) {
		return Props.create(new Creator<EcsAssembly>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsAssembly create() throws Exception {
				return new EcsAssembly(assemblyInfo, supervisor);
			}
		});
	}

}
