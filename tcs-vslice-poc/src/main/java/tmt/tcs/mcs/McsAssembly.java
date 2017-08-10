package tmt.tcs.mcs;

import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
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
import javacsw.services.ccs.JValidation;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.loc.JLocationSubscriberActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseAssembly;

/**
 * Top Level Actor for MCS Assembly
 *
 * McsAssembly starts up the component doing the following: creating all needed
 * actors, handling initialization, participating in life cycle with Supervisor,
 * handles locations for distribution throughout component receives commands and
 * forwards them to the CommandHandler by extending the BaseAssembly
 */
@SuppressWarnings("unused")
public class McsAssembly extends BaseAssembly {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;
	private final AssemblyContext assemblyContext;
	private ActorRef commandHandler;

	private Optional<ActorRef> badHcdReference = Optional.empty();
	private Optional<ActorRef> mcsHcd = badHcdReference;

	private final Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	private final Optional<ITelemetryService> badTelemetryService = Optional.empty();
	private Optional<ITelemetryService> telemetryService = badTelemetryService;

	private ActorRef diagPublsher;

	public static File mcsConfigFile = new File("mcs/assembly/mcsAssembly.conf");
	public static File resource = new File("mcsAssembly.conf");

	public McsAssembly(Component.AssemblyInfo info, ActorRef supervisor) {
		super(info);
		this.supervisor = supervisor;

		log.debug("Inside McsAssembly Constructor");

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

			log.debug("Inside McsAssembly initialize: Connections: " + info.connections());

			ActorRef trackerSubscriber = context().actorOf(LocationSubscriberActor.props());
			trackerSubscriber.tell(JLocationSubscriberActor.Subscribe, self());

			ActorRef eventPublisher = context()
					.actorOf(McsEventPublisher.props(assemblyContext, Optional.empty(), Optional.empty()));

			commandHandler = context()
					.actorOf(McsCommandHandler.props(assemblyContext, mcsHcd, Optional.of(eventPublisher)));

			diagPublsher = context()
					.actorOf(McsDiagnosticPublisher.props(assemblyContext, mcsHcd, Optional.of(eventPublisher)));

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
			log.debug("Inside McsAssembly initializingReceive");
			context().become(runningReceive());
		}).matchAny(t -> log.debug("Unexpected message in McsAssembly:initializingReceive: " + t)).build());
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
				log.debug("Inside McsAssembly locationReceive: actorRef: " + l.getActorRef());
				mcsHcd = l.getActorRef();
				supervisor.tell(Initialized, self());

			} else if (location instanceof ResolvedHttpLocation) {
				log.debug("Inside McsAssembly locationReceive: HTTP Service is: " + location.connection());

			} else if (location instanceof ResolvedTcpLocation) {
				ResolvedTcpLocation t = (ResolvedTcpLocation) location;
				log.debug("Inside McsAssembly locationReceive: TCP Location is: " + t.connection());

				if (location.connection().equals(IEventService.eventServiceConnection())) {
					log.debug("Inside McsAssembly locationReceive: ES connection is: " + t);
					eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
					log.debug("Inside McsAssembly Event Service at: " + eventService);
				}

				if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
					log.debug("Inside McsAssembly locationReceive: TS connection is: " + t);
					telemetryService = Optional
							.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
					log.debug("Inside McsAssembly Telemetry Service at: " + telemetryService);
				}

				if (location.connection().equals(IAlarmService.alarmServiceConnection(IAlarmService.defaultName))) {
					log.debug("Inside McsAssembly locationReceive: AS connection is: " + t);
				}

			} else if (location instanceof Unresolved) {
				log.debug("Inside McsAssembly locationReceive: Unresolved location: " + location.connection());
				if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
					mcsHcd = badHcdReference;

			} else if (location instanceof UnTrackedLocation) {
				log.debug("Inside McsAssembly locationReceive: UnTracked location: " + location.connection());

			} else {
				log.debug("Inside McsAssembly locationReceive: Unknown connection: " + location.connection()); // XXX
			}
		}).build();
	}

	/**
	 * This is used when in Running state
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> runningReceive() {
		return locationReceive().orElse(diagReceive()).orElse(controllerReceive())
				.orElse(lifecycleReceivePF(supervisor)).orElse(unhandledPF());
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
		log.debug("Inside McsAssembly: setup SetupConfigArg is : " + sca);
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
		return validateMcsSetupConfigArg(sca, assemblyContext);
	}

	/**
	 * This Runs Mcs-specific validation on a single SetupConfig.
	 */
	public static Validation.Validation validateOneSetupConfig(SetupConfig sc, AssemblyContext ac) {
		// TODO: For now returning Valid, add specific valiations
		return JValidation.Valid;
	}

	/**
	 * This Validates SetupConfigArg for Mcs Assembly
	 */
	public static List<Validation.Validation> validateMcsSetupConfigArg(SetupConfigArg sca, AssemblyContext ac) {
		List<Validation.Validation> result = new ArrayList<>();
		for (SetupConfig config : sca.getConfigs()) {
			result.add(validateOneSetupConfig(config, ac));
		}
		return result;
	}

	public static Props props(Component.AssemblyInfo assemblyInfo, ActorRef supervisor) {
		return Props.create(new Creator<McsAssembly>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsAssembly create() throws Exception {
				return new McsAssembly(assemblyInfo, supervisor);
			}
		});
	}

}
