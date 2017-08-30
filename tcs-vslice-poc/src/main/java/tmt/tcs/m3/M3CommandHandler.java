package tmt.tcs.m3;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static scala.compat.java8.OptionConverters.toJava;
import static tmt.tcs.common.AssemblyStateActor.az;
import static tmt.tcs.common.AssemblyStateActor.azDatumed;
import static tmt.tcs.common.AssemblyStateActor.azDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.azFollowing;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.el;
import static tmt.tcs.common.AssemblyStateActor.elDatumed;
import static tmt.tcs.common.AssemblyStateActor.elDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.elFollowing;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.m3.M3Config.M3_IDLE;
import static tmt.tcs.m3.M3Config.m3StateKey;

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
import csw.services.ccs.CommandStatus.Error;
import csw.services.ccs.CommandStatus.Invalid;
import csw.services.ccs.CommandStatus.NoLongerValid;
import csw.services.ccs.DemandMatcher;
import csw.services.ccs.SequentialExecutor.ExecuteOne;
import csw.services.ccs.Validation.RequiredHCDUnavailableIssue;
import csw.services.ccs.Validation.UnsupportedCommandInStateIssue;
import csw.services.ccs.Validation.WrongInternalStateIssue;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
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

/**
 * This is an actor class which receives commands forwarded by M3 Assembly And
 * based upon the command config key send to specific command actor class
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
		return stateReceive()
				.orElse(ReceiveBuilder.match(Location.class, this::handleLocations).match(ExecuteOne.class, t -> {

					SetupConfig sc = t.sc();
					Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
					ConfigKey configKey = sc.configKey();

					log.debug("Inside M3CommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc
							+ ": configKey is: " + configKey);

					if (configKey.equals(M3Config.initCK)) {
						log.info(
								"Inside M3CommandHandler initReceive: Init not fully implemented -- only sets state ready!");
						try {
							ask(m3StateActor, new AssemblySetState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)),
									5000).toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside M3CommandHandler Error setting state");
						}
						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

					} else if (configKey.equals(M3Config.followCK)) {
						if (!(az(currentState()).equals(azDatumed) || az(currentState()).equals(azDrivePowerOn))
								&& !(el(currentState()).equals(elDatumed)
										|| az(currentState()).equals(elDrivePowerOn))) {
							String errorMessage = "M3 Assembly state of " + az(currentState()) + "/"
									+ el(currentState()) + " does not allow follow";
							log.debug("Inside M3CommandHandler initReceive: Error Message is: " + errorMessage);
							sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
						} else {
							log.debug("Inside M3CommandHandler initReceive: Follow command -- START: " + t);

							Double initialRotation = 0.0;
							Double initialTilt = 0.0;

							// The event publisher may be passed in
							Props props = M3FollowCommand.props(assemblyContext,
									jset(M3Config.rotation, initialRotation), jset(M3Config.tilt, initialTilt),
									Optional.of(m3Hcd), allEventPublisher, eventService.get(),
									Optional.of(m3StateActor));

							ActorRef followCommandActor = context().actorOf(props);
							log.info("Inside M3CommandHandler initReceive: Going to followReceive");
							context().become(followReceive(followCommandActor));

							try {
								ask(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
										.toCompletableFuture().get();
							} catch (Exception e) {
								log.error(e, "Inside M3CommandHandler initReceive: Error setting state");
							}
							commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

						}
					} else if (configKey.equals(M3Config.offsetDemandCK)) {
						log.debug("Inside M3CommandHandler initReceive: ExecuteOne: offsetCK Command ");
						ActorRef offsetActorRef = context().actorOf(M3OffsetCommand.props(assemblyContext, sc, m3Hcd,
								currentState(), Optional.of(m3StateActor)));
						context().become(actorExecutingReceive(offsetActorRef, commandOriginator));
						self().tell(JSequentialExecutor.CommandStart(), self());
					} else {
						log.error("Inside M3CommandHandler initReceive: Received an unknown command: " + t + " from "
								+ sender());
						commandOriginator.ifPresent(actorRef -> actorRef.tell(new Invalid(
								new UnsupportedCommandInStateIssue("M3 assembly does not support the command "
										+ configKey.prefix() + " in the current state.")),
								self()));
					}

				}).build());
	}

	/**
	 * This responds to Command Originator in case HCD is not available
	 * 
	 * @param commandOriginator
	 */
	private void hcdNotAvailableResponse(Optional<ActorRef> commandOriginator) {
		commandOriginator.ifPresent(actorRef -> actorRef.tell(new NoLongerValid(
				new RequiredHCDUnavailableIssue(assemblyContext.hcdComponentId.toString() + " is not available")),
				self()));
	}

	private PartialFunction<Object, BoxedUnit> followReceive(ActorRef followActor) {
		return stateReceive().orElse(ReceiveBuilder.match(ExecuteOne.class, t -> {
			SetupConfig sc = t.sc();
			log.debug("Inside M3CommandHandler followReceive: ExecuteOne: SetupConfig is: " + sc);
			Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
			ConfigKey configKey = sc.configKey();
			log.debug("Inside M3CommandHandler followReceive: ExecuteOne: configKey is: " + configKey);

			if (configKey.equals(M3Config.setRotationCK)) {
				log.debug("Inside M3CommandHandler followReceive: Started for: " + configKey);
				try {
					ask(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside M3CommandHandler followReceive: Error setting state");
				}

				DoubleItem rotationItem = jitem(sc, M3Config.rotationDemandKey);

				Double rotation = jvalue(rotationItem);

				followActor.tell(new M3FollowActor.SetRotation(jset(M3Config.rotation, rotation)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), rotationPosMatcher(rotation), m3Hcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside M3CommandHandler followReceive: Command Completed for: " + configKey);
							ask(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside M3CommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside M3CommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			} else if (configKey.equals(M3Config.setTiltCK)) {
				log.debug("Inside M3CommandHandler followReceive: Started for: " + configKey);
				try {
					ask(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside M3CommandHandler followReceive: Error setting state");
				}

				DoubleItem tiltItem = jitem(sc, M3Config.tiltDemandKey);

				Double tilt = jvalue(tiltItem);

				followActor.tell(new M3FollowActor.SetTilt(jset(M3Config.tilt, tilt)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), tiltPosMatcher(tilt), m3Hcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside M3CommandHandler followReceive: Command Completed for: " + configKey);
							ask(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside M3CommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside M3CommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			}
		}).matchAny(t -> log.warning("Inside M3CommandHandler followReceive:  received an unknown message: " + t))
				.build());
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
	 * @param rotation
	 * @param tilt
	 * @return DemandMatcher
	 */
	public static DemandMatcher posMatcher(double rotation, double tilt) {
		System.out.println("Inside M3CommandHandler posMatcher Move: Starts");

		DemandState ds = jadd(new DemandState(M3Config.currentPosPrefix), jset(m3StateKey, M3_IDLE),
				jset(M3Config.rotationPosKey, rotation), jset(M3Config.tiltPosKey, tilt));

		System.out.println("Inside M3CommandHandler posMatcher Move: DemandState is: " + tilt);
		return new DemandMatcher(ds, false);
	}

	/**
	 * Based upon command parameters being passed to Set Rotation command this
	 * helps in generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param x
	 * @return DemandMatcher
	 */
	public static DemandMatcher rotationPosMatcher(double rotation) {
		System.out.println("Inside EcsCommandHandler rotationPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(M3Config.currentPosPrefix), jset(m3StateKey, M3_IDLE),
				jset(M3Config.rotationPosKey, rotation));
		return new DemandMatcher(ds, false);
	}

	/**
	 * Based upon command parameters being passed to Set Tilt command this helps
	 * in generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param x
	 * @return DemandMatcher
	 */
	public static DemandMatcher tiltPosMatcher(double tilt) {
		System.out.println("Inside EcsCommandHandler tiltPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(M3Config.currentPosPrefix), jset(m3StateKey, M3_IDLE),
				jset(M3Config.tiltPosKey, tilt));
		return new DemandMatcher(ds, false);
	}

}
