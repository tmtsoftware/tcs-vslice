package tmt.tcs.m3;

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
import csw.services.ccs.AssemblyMessages;
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
import javacsw.services.ccs.JAssemblyMessages;
import javacsw.services.ccs.JValidation;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import javacsw.services.loc.JLocationSubscriberActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseAssembly;

/**
 * Top Level Actor for M3 Assembly
 *
 * M3Assembly starts up the component doing the following: creating all needed
 * actors, handling initialization, participating in life cycle with Supervisor,
 * handles locations for distribution throughout component receives commands and
 * forwards them to the CommandHandler by extending the BaseAssembly
 */
@SuppressWarnings("unused")
public class M3Assembly extends BaseAssembly {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;
	private final AssemblyContext assemblyContext;
	private ActorRef commandHandler;

	private Optional<ActorRef> badHcdReference = Optional.empty();
	private Optional<ActorRef> m3Hcd = badHcdReference;

	private final Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	private final Optional<ITelemetryService> badTelemetryService = Optional.empty();
	private Optional<ITelemetryService> telemetryService = badTelemetryService;

	private ActorRef diagnosticPublisher;

	public static File m3ConfigFile = new File("m3/assembly/m3Assembly.conf");
	public static File resource = new File("m3Assembly.conf");

	public M3Assembly(Component.AssemblyInfo info, ActorRef supervisor) {
		super(info);
		this.supervisor = supervisor;

		log.debug("Inside M3Assembly Constructor");

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

			log.debug("Inside M3Assembly initialize: Connections: " + info.connections());

			ActorRef trackerSubscriber = context().actorOf(LocationSubscriberActor.props());
			trackerSubscriber.tell(JLocationSubscriberActor.Subscribe, self());

			ActorRef eventPublisher = context()
					.actorOf(M3EventPublisher.props(assemblyContext, Optional.empty(), Optional.empty()));

			commandHandler = context()
					.actorOf(M3CommandHandler.props(assemblyContext, m3Hcd, Optional.of(eventPublisher)));

			diagnosticPublisher = context()
					.actorOf(M3EventDelegator.props(assemblyContext, m3Hcd, Optional.of(eventPublisher)));

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
			log.debug("Inside M3Assembly initializingReceive");
			context().become(runningReceive());
		}).matchAny(t -> log.debug("Unexpected message in M3Assembly:initializingReceive: " + t)).build());
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
				log.debug("Inside M3Assembly locationReceive: actorRef: " + l.getActorRef());
				m3Hcd = l.getActorRef();
				supervisor.tell(Initialized, self());

			} else if (location instanceof ResolvedHttpLocation) {
				log.debug("Inside M3Assembly locationReceive: HTTP Service is: " + location.connection());

			} else if (location instanceof ResolvedTcpLocation) {
				ResolvedTcpLocation t = (ResolvedTcpLocation) location;
				log.debug("Inside M3Assembly locationReceive: TCP Location is: " + t.connection());

				if (location.connection().equals(IEventService.eventServiceConnection())) {
					log.debug("Inside M3Assembly locationReceive: ES connection is: " + t);
					eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
					log.debug("Inside M3Assembly Event Service at: " + eventService);
				}

				if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
					log.debug("Inside M3Assembly locationReceive: TS connection is: " + t);
					telemetryService = Optional
							.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
					log.debug("Inside M3Assembly Telemetry Service at: " + telemetryService);
				}

				if (location.connection().equals(IAlarmService.alarmServiceConnection(IAlarmService.defaultName))) {
					log.debug("Inside M3Assembly locationReceive: AS connection is: " + t);
				}

			} else if (location instanceof Unresolved) {
				log.debug("Inside M3Assembly locationReceive: Unresolved location: " + location.connection());
				if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
					m3Hcd = badHcdReference;

			} else if (location instanceof UnTrackedLocation) {
				log.debug("Inside M3Assembly locationReceive: UnTracked location: " + location.connection());

			} else {
				log.debug("Inside M3Assembly locationReceive: Unknown connection: " + location.connection()); // XXX
			}
		}).build();
	}

	/**
	 * This is used when in Running state
	 *
	 * @return a partial function
	 */
	private PartialFunction<Object, BoxedUnit> runningReceive() {
		return locationReceive().orElse(diagnosticReceive()).orElse(controllerReceive())
				.orElse(lifecycleReceivePF(supervisor)).orElse(unhandledPF());
	}

	/**
	 * This is used for handling the diagnostic commands
	 *
	 * @return a partial function
	 */
	public PartialFunction<Object, BoxedUnit> diagnosticReceive() {
		return ReceiveBuilder.match(AssemblyMessages.DiagnosticMode.class, t -> {
			log.debug("Inside M3Assembly diagnosticReceive: diagnostic mode: " + t.hint());
			diagnosticPublisher.tell(new M3EventDelegator.DiagnosticState(), self());
		}).matchEquals(JAssemblyMessages.OperationsMode, t -> {
			log.debug("Inside M3Assembly diagnosticReceive: operations mode");
			diagnosticPublisher.tell(new M3EventDelegator.OperationsState(), self());
		}).build();
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
		return validateM3SetupConfigArg(sca, assemblyContext);
	}

	/**
	 * This Runs M3-specific validation on a single SetupConfig.
	 */
	public static Validation.Validation validateOneSetupConfig(SetupConfig sc, AssemblyContext ac) {
		// TODO: For now returning Valid, add specific valiations
		return JValidation.Valid;
	}

	/**
	 * This Validates SetupConfigArg for M3 Assembly
	 */
	public static List<Validation.Validation> validateM3SetupConfigArg(SetupConfigArg sca, AssemblyContext ac) {
		List<Validation.Validation> result = new ArrayList<>();
		for (SetupConfig config : sca.getConfigs()) {
			result.add(validateOneSetupConfig(config, ac));
		}
		return result;
	}

	public static Props props(Component.AssemblyInfo assemblyInfo, ActorRef supervisor) {
		return Props.create(new Creator<M3Assembly>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3Assembly create() throws Exception {
				return new M3Assembly(assemblyInfo, supervisor);
			}
		});
	}

	/**
	 * The message is used within the Assembly to update actors when the M3 HCD
	 * goes up and down and up again
	 */
	public static class UpdateM3Hcd {
		public final Optional<ActorRef> m3Hcd;

		/**
		 * @param mcdHcd
		 *            the ActorRef of the mcdHcd or None
		 */
		public UpdateM3Hcd(Optional<ActorRef> m3Hcd) {
			this.m3Hcd = m3Hcd;
		}
	}
}
