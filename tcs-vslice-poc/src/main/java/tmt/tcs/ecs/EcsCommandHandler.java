package tmt.tcs.ecs;

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
import static tmt.tcs.ecs.EcsConfig.ECS_IDLE;
import static tmt.tcs.ecs.EcsConfig.ecsStateKey;

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

/*
 * This is an actor class which receives commands forwarded by ECS Assembly
 * And based upon the command config key send to specific command actor class
 */
@SuppressWarnings("unused")
public class EcsCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> allEventPublisher;

	private final ActorRef ecsStateActor;

	private final ActorRef badHcdReference;

	private ActorRef ecsHcd;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public EcsCommandHandler(AssemblyContext ac, Optional<ActorRef> ecsHcd, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside EcsCommandHandler");

		this.assemblyContext = ac;
		badHcdReference = context().system().deadLetters();
		this.ecsHcd = ecsHcd.orElse(badHcdReference);
		this.allEventPublisher = allEventPublisher;
		ecsStateActor = context().actorOf(AssemblyStateActor.props());

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
			log.debug("Inside EcsCommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
			ecsHcd = l.getActorRef().orElse(badHcdReference);

		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside EcsCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside EcsCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside EcsCommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside EcsCommandHandler: Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				eventService = badEventService;
			if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
				ecsHcd = badHcdReference;

		} else {
			log.debug("Inside EcsCommandHandler: CommandHandler received some other location: " + location);
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

					log.debug("Inside EcsCommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc
							+ ": configKey is: " + configKey);

					if (configKey.equals(EcsConfig.initCK)) {
						log.info(
								"Inside EcsCommandHandler initReceive: Init not fully implemented -- only sets state ready!");
						try {
							ask(ecsStateActor, new AssemblySetState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)),
									5000).toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside EcsCommandHandler Error setting state");
						}
						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

					} else if (configKey.equals(EcsConfig.followCK)) {
						if (!(az(currentState()).equals(azDatumed) || az(currentState()).equals(azDrivePowerOn))
								&& !(el(currentState()).equals(elDatumed)
										|| az(currentState()).equals(elDrivePowerOn))) {
							String errorMessage = "Ecs Assembly state of " + az(currentState()) + "/"
									+ el(currentState()) + " does not allow follow";
							log.debug("Inside EcsCommandHandler initReceive: Error Message is: " + errorMessage);
							sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
						} else {
							log.debug("Inside EcsCommandHandler initReceive: Follow command -- START: " + t);

							Double initialAz = 0.0;
							Double initialEl = 0.0;

							// The event publisher may be passed in
							Props props = EcsFollowCommand.props(assemblyContext, jset(EcsConfig.az, initialAz),
									jset(EcsConfig.el, initialEl), Optional.of(ecsHcd), allEventPublisher,
									eventService.get());

							ActorRef followCommandActor = context().actorOf(props);
							log.info("Inside EcsCommandHandler initReceive: Going to followReceive");
							context().become(followReceive(followCommandActor));

							try {
								ask(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
										.toCompletableFuture().get();
							} catch (Exception e) {
								log.error(e, "Inside EcsCommandHandler initReceive: Error setting state");
							}
							commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

						}
					} else if (configKey.equals(EcsConfig.offsetDemandCK)) {
						log.debug("Inside EcsCommandHandler initReceive: ExecuteOne: offsetCK Command ");
						ActorRef offsetActorRef = context().actorOf(EcsOffsetCommand.props(assemblyContext, sc, ecsHcd,
								currentState(), Optional.of(ecsStateActor)));
						context().become(actorExecutingReceive(offsetActorRef, commandOriginator));
						self().tell(JSequentialExecutor.CommandStart(), self());
					} else {
						log.error("Inside EcsCommandHandler initReceive: Received an unknown command: " + t + " from "
								+ sender());
						commandOriginator
								.ifPresent(
										actorRef -> actorRef.tell(
												new Invalid(new UnsupportedCommandInStateIssue(
														"Ecs assembly does not support the command "
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
			log.debug("Inside EcsCommandHandler followReceive: ExecuteOne: SetupConfig is: " + sc);
			Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
			ConfigKey configKey = sc.configKey();
			log.debug("Inside EcsCommandHandler followReceive: ExecuteOne: configKey is: " + configKey);

			if (configKey.equals(EcsConfig.setAzimuthCK)) {
				log.debug("Inside EcsCommandHandler followReceive: Started for: " + configKey);
				try {
					ask(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside EcsCommandHandler followReceive: Error setting state");
				}

				DoubleItem azItem = jitem(sc, EcsConfig.azDemandKey);

				Double az = jvalue(azItem);

				followActor.tell(new EcsFollowActor.SetAzimuth(jset(EcsConfig.az, az)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), azPosMatcher(az), ecsHcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside EcsCommandHandler followReceive: Command Completed for: " + configKey);
							ask(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside EcsCommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside EcsCommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			} else if (configKey.equals(EcsConfig.setElevationCK)) {
				log.debug("Inside EcsCommandHandler followReceive: Started for: " + configKey);
				try {
					ask(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside EcsCommandHandler followReceive: Error setting state");
				}

				DoubleItem elItem = jitem(sc, EcsConfig.elDemandKey);

				Double el = jvalue(elItem);

				followActor.tell(new EcsFollowActor.SetElevation(jset(EcsConfig.el, el)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), elPosMatcher(el), ecsHcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside EcsCommandHandler followReceive: Command Completed for: " + configKey);
							ask(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside EcsCommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside EcsCommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			}
		}).matchAny(t -> log.warning("Inside EcsCommandHandler followReceive:  received an unknown message: " + t))
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
			log.debug("Inside EcsCommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside EcsCommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside EcsCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside EcsCommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside EcsCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside EcsCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> ecsHcd, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<EcsCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsCommandHandler create() throws Exception {
				return new EcsCommandHandler(ac, ecsHcd, allEventPublisher);
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
	 * @return DemandMatcher
	 */
	public static DemandMatcher posMatcher(double az, double el) {
		System.out.println("Inside EcsCommandHandler posMatcher Move: Starts");

		DemandState ds = jadd(new DemandState(EcsConfig.currentPosPrefix), jset(ecsStateKey, ECS_IDLE),
				jset(EcsConfig.azPosKey, az), jset(EcsConfig.elPosKey, el));

		System.out.println("Inside EcsCommandHandler posMatcher Move: DemandState is: " + ds);
		return new DemandMatcher(ds, false);
	}

	/**
	 * Based upon command parameters being passed to Set Azimuth command this
	 * helps in generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param x
	 * @return DemandMatcher
	 */
	public static DemandMatcher azPosMatcher(double az) {
		System.out.println("Inside EcsCommandHandler azPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(EcsConfig.currentPosPrefix), jset(ecsStateKey, ECS_IDLE),
				jset(EcsConfig.azPosKey, az));
		return new DemandMatcher(ds, false);
	}

	/**
	 * Based upon command parameters being passed to Set Elevation command this
	 * helps in generating DemandMatcher which can be used to track for command
	 * completion status
	 * 
	 * @param x
	 * @return DemandMatcher
	 */
	public static DemandMatcher elPosMatcher(double el) {
		System.out.println("Inside EcsCommandHandler elPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(EcsConfig.currentPosPrefix), jset(ecsStateKey, ECS_IDLE),
				jset(EcsConfig.elPosKey, el));
		return new DemandMatcher(ds, false);
	}

}
