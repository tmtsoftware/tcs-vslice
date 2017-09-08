package tmt.tcs;

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
 * Top Level Actor for TCS Assembly
 *
 * TcsAssembly starts up the component doing the following: creating all needed
 * actors, handling initialization, participating in life cycle with Supervisor,
 * handles locations for distribution throughout component receives commands and
 * forwards them to the CommandHandler by extending the BaseAssembly
 */
@SuppressWarnings("unused")
public class TcsAssembly extends BaseAssembly {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;
	private final AssemblyContext assemblyContext;
	private ActorRef commandHandler;

	private Optional<ActorRef> badActorReference = Optional.empty();

	private Optional<ActorRef> mcsRefActor = badActorReference;
	private Optional<ActorRef> ecsRefActor = badActorReference;
	private Optional<ActorRef> m3RefActor = badActorReference;
	private Optional<ActorRef> tpkRefActor = badActorReference;

	private final Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	private final Optional<ITelemetryService> badTelemetryService = Optional.empty();
	private Optional<ITelemetryService> telemetryService = badTelemetryService;

	public static File tcsConfigFile = new File("tcs/assembly/tcsAssembly.conf");
	public static File resource = new File("tcsAssembly.conf");

	public TcsAssembly(Component.AssemblyInfo info, ActorRef supervisor) {
		super(info);
		this.supervisor = supervisor;

		log.debug("Inside TcsAssembly Constructor");

		assemblyContext = initialize(info);

		receive(initializingReceive());
	}

	/*
	 * Method for Initializing Command Handlers, Location Subscription etc at
	 * Assembly initialization
	 */
	private AssemblyContext initialize(Component.AssemblyInfo info) {
		try {
			// TODO:: Uncomment later, Commented for now as Config Service gives
			// time Out
			// Config config = getAssemblyConfigs();

			AssemblyContext assemblyContext = new AssemblyContext(info);

			log.debug("Inside TcsAssembly initialize: Connections: " + info.connections());

			ActorRef trackerSubscriber = context().actorOf(LocationSubscriberActor.props());
			trackerSubscriber.tell(JLocationSubscriberActor.Subscribe, self());

			ActorRef eventPublisher = context()
					.actorOf(TcsEventPublisher.props(assemblyContext, Optional.empty(), Optional.empty()));

			commandHandler = context().actorOf(TcsCommandHandler.props(assemblyContext, mcsRefActor, ecsRefActor,
					m3RefActor, tpkRefActor, Optional.of(eventPublisher)));

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
			log.debug("Inside TcsAssembly initializingReceive");
			context().become(runningReceive());
		}).matchAny(t -> log.debug("Unexpected message in TcsAssembly:initializingReceive: " + t)).build());
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
				log.debug("Inside TcsAssembly locationReceive: actorRef: " + l.getActorRef() + ": prefix is: "
						+ l.prefix());
				if (TcsConfig.mcsPrefix.equals(l.prefix())) {
					log.debug("Inside TcsAssembly locationReceive: actorRef for TcsAssembly ");
					mcsRefActor = l.getActorRef();
				} else if (TcsConfig.ecsPrefix.equals(l.prefix())) {
					log.debug("Inside TcsAssembly locationReceive: actorRef for EcsAssembly ");
					ecsRefActor = l.getActorRef();
				} else if (TcsConfig.m3Prefix.equals(l.prefix())) {
					log.debug("Inside TcsAssembly locationReceive: actorRef for M3Assembly ");
					m3RefActor = l.getActorRef();
				} else if (TcsConfig.tpkPrefix.equals(l.prefix())) {
					log.debug("Inside TcsAssembly locationReceive: actorRef for TpkAssembly ");
					tpkRefActor = l.getActorRef();
				} else {
					log.debug("Inside TcsAssembly locationReceive: actorRef for Unknown Actor ");
				}
				supervisor.tell(Initialized, self());

			} else if (location instanceof ResolvedHttpLocation) {
				log.debug("Inside TcsAssembly locationReceive: HTTP Service is: " + location.connection());

			} else if (location instanceof ResolvedTcpLocation) {
				ResolvedTcpLocation t = (ResolvedTcpLocation) location;
				log.debug("Inside TcsAssembly locationReceive: TCP Location is: " + t.connection());

				if (location.connection().equals(IEventService.eventServiceConnection())) {
					log.debug("Inside TcsAssembly locationReceive: ES connection is: " + t);
					eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
					log.debug("Inside TcsAssembly Event Service at: " + eventService);
				}

				if (location.connection().equals(ITelemetryService.telemetryServiceConnection())) {
					log.debug("Inside TcsAssembly locationReceive: TS connection is: " + t);
					telemetryService = Optional
							.of(ITelemetryService.getTelemetryService(t.host(), t.port(), context().system()));
					log.debug("Inside TcsAssembly Telemetry Service at: " + telemetryService);
				}

				if (location.connection().equals(IAlarmService.alarmServiceConnection(IAlarmService.defaultName))) {
					log.debug("Inside TcsAssembly locationReceive: AS connection is: " + t);
				}

			} else if (location instanceof Unresolved) {
				log.debug("Inside TcsAssembly locationReceive: Unresolved location: " + location.connection());

			} else if (location instanceof UnTrackedLocation) {
				log.debug("Inside TcsAssembly locationReceive: UnTracked location: " + location.connection());

			} else {
				log.debug("Inside TcsAssembly locationReceive: Unknown connection: " + location.connection()); // XXX
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
			log.debug("Inside TcsAssembly diagnosticReceive: diagnostic mode: " + t.hint());
		}).matchEquals(JAssemblyMessages.OperationsMode, t -> {
			log.debug("Inside TcsAssembly diagnosticReceive: operations mode");
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
		log.debug("Inside TcsAssembly setup: SetupConfigArg is: " + sca);
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
		return validateTcsSetupConfigArg(sca, assemblyContext);
	}

	/**
	 * This Runs Tcs-specific validation on a single SetupConfig.
	 */
	public static Validation.Validation validateOneSetupConfig(SetupConfig sc, AssemblyContext ac) {
		// TODO: For now returning Valid, add specific valiations
		return JValidation.Valid;
	}

	/*
	 * This Validates SetupConfigArg for Tcs Assembly
	 */
	public static List<Validation.Validation> validateTcsSetupConfigArg(SetupConfigArg sca, AssemblyContext ac) {
		List<Validation.Validation> result = new ArrayList<>();
		for (SetupConfig config : sca.getConfigs()) {
			result.add(validateOneSetupConfig(config, ac));
		}
		return result;
	}

	public static Props props(Component.AssemblyInfo assemblyInfo, ActorRef supervisor) {
		return Props.create(new Creator<TcsAssembly>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsAssembly create() throws Exception {
				return new TcsAssembly(assemblyInfo, supervisor);
			}
		});
	}

}
