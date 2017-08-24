package tmt.tcs.mcs;

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
import static tmt.tcs.mcs.McsConfig.MCS_IDLE;
import static tmt.tcs.mcs.McsConfig.mcsStateKey;

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
 * This is an actor class which receives commands forwarded by MCS Assembly
 * And based upon the command config key send to specific command actor class
 */
public class McsCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> allEventPublisher;
	private final ActorRef mcsStateActor;

	private final ActorRef badHcdReference;
	private ActorRef mcsHcd;

	private boolean isHcdAvailable() {
		return !mcsHcd.equals(badHcdReference);
	}

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	public McsCommandHandler(AssemblyContext ac, Optional<ActorRef> mcsHcd, Optional<ActorRef> allEventPublisher) {

		log.debug("Inside McsCommandHandler");

		this.assemblyContext = ac;
		badHcdReference = context().system().deadLetters();
		this.mcsHcd = mcsHcd.orElse(badHcdReference);
		this.allEventPublisher = allEventPublisher;
		mcsStateActor = context().actorOf(AssemblyStateActor.props());

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
			log.debug("Inside McsCommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
			mcsHcd = l.getActorRef().orElse(badHcdReference);

		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside McsCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside McsCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside McsCommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside McsCommandHandler: Unresolved: " + location.connection());
			if (location.connection().equals(IEventService.eventServiceConnection()))
				eventService = badEventService;
			if (location.connection().componentId().equals(assemblyContext.hcdComponentId))
				mcsHcd = badHcdReference;

		} else {
			log.debug("Inside McsCommandHandler: CommandHandler received some other location: " + location);
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

					log.debug("Inside McsCommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc
							+ ": configKey is: " + configKey);

					if (configKey.equals(McsConfig.initCK)) {
						log.info(
								"Inside McsCommandHandler initReceive: Init not fully implemented -- only sets state ready!");
						try {
							ask(mcsStateActor, new AssemblySetState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)),
									5000).toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside McsCommandHandler initReceive: Error setting state");
						}
						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

					} else if (configKey.equals(McsConfig.followCK)) {
						if (!(az(currentState()).equals(azDatumed) || az(currentState()).equals(azDrivePowerOn))
								&& !(el(currentState()).equals(elDatumed)
										|| az(currentState()).equals(elDrivePowerOn))) {
							String errorMessage = "Mcs Assembly state of " + az(currentState()) + "/"
									+ el(currentState()) + " does not allow follow";
							log.debug("Inside McsCommandHandler initReceive: Error Message is: " + errorMessage);
							sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
						} else {
							log.debug("Inside McsCommandHandler initReceive: Follow command -- START: " + t);

							Double initialAz = 0.0;
							Double initialEl = 0.0;

							// The event publisher may be passed in
							Props props = McsFollowCommand.props(assemblyContext, jset(McsConfig.az, initialAz),
									jset(McsConfig.el, initialEl), Optional.of(mcsHcd), allEventPublisher,
									eventService.get());

							ActorRef followCommandActor = context().actorOf(props);
							log.info("Inside McsCommandHandler initReceive: Going to followReceive");
							context().become(followReceive(followCommandActor));

							try {
								ask(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
										.toCompletableFuture().get();
							} catch (Exception e) {
								log.error(e, "Inside McsCommandHandler initReceive: Error setting state");
							}
							commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));

						}
					} else if (configKey.equals(McsConfig.offsetDemandCK)) {
						if (isHcdAvailable()) {
							log.debug("Inside McsCommandHandler initReceive: ExecuteOne: offsetCK Command ");
							ActorRef offsetActorRef = context().actorOf(McsOffsetCommand.props(assemblyContext, sc,
									mcsHcd, currentState(), Optional.of(mcsStateActor)));
							context().become(actorExecutingReceive(offsetActorRef, commandOriginator));
							self().tell(JSequentialExecutor.CommandStart(), self());
						} else {
							hcdNotAvailableResponse(commandOriginator);
						}
					} else {
						log.error("Inside McsCommandHandler initReceive: Received an unknown command: " + t + " from "
								+ sender());
						commandOriginator
								.ifPresent(
										actorRef -> actorRef.tell(
												new Invalid(new UnsupportedCommandInStateIssue(
														"Mcs assembly does not support the command "
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
			log.debug("Inside McsCommandHandler followReceive: ExecuteOne: SetupConfig is: " + sc);
			Optional<ActorRef> commandOriginator = toJava(t.commandOriginator());
			ConfigKey configKey = sc.configKey();
			log.debug("Inside McsCommandHandler followReceive: ExecuteOne: configKey is: " + configKey);

			if (configKey.equals(McsConfig.setAzimuthCK)) {
				log.debug("Inside McsCommandHandler followReceive: Started for: " + configKey);
				try {
					ask(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside McsCommandHandler followReceive: Error setting state");
				}

				DoubleItem azItem = jitem(sc, McsConfig.azDemandKey);

				Double az = jvalue(azItem);

				followActor.tell(new McsFollowActor.SetAzimuth(jset(McsConfig.az, az)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), azPosMatcher(az), mcsHcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside McsCommandHandler followReceive: Command Completed for: " + configKey);
							ask(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside McsCommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside McsCommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			} else if (configKey.equals(McsConfig.setElevationCK)) {
				log.debug("Inside McsCommandHandler followReceive: Started for: " + configKey);
				try {
					ask(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
							.toCompletableFuture().get();
				} catch (Exception e) {
					log.error(e, "Inside McsCommandHandler followReceive: Error setting state");
				}

				DoubleItem elItem = jitem(sc, McsConfig.elDemandKey);

				Double el = jvalue(elItem);

				followActor.tell(new McsFollowActor.SetElevation(jset(McsConfig.el, el)), self());
				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				executeMatch(context(), elPosMatcher(el), mcsHcd, commandOriginator, timeout, status -> {
					if (status == Completed) {
						try {
							log.debug("Inside McsCommandHandler followReceive: Command Completed for: " + configKey);
							ask(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)), 5000)
									.toCompletableFuture().get();
						} catch (Exception e) {
							log.error(e, "Inside McsCommandHandler followReceive: Error setting state");
						}
					} else if (status instanceof Error)
						log.error("Inside McsCommandHandler followReceive: command failed with message: "
								+ ((Error) status).message());
				});
			}
		}).matchAny(t -> log.warning("Inside McsCommandHandler followReceive:  received an unknown message: " + t))
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
			log.debug("Inside McsCommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside McsCommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside McsCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside McsCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public static Props props(AssemblyContext ac, Optional<ActorRef> mcsHcd, Optional<ActorRef> allEventPublisher) {
		return Props.create(new Creator<McsCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsCommandHandler create() throws Exception {
				return new McsCommandHandler(ac, mcsHcd, allEventPublisher);
			}
		});
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
	public static DemandMatcher posMatcher(double az, double el) {
		System.out.println("Inside McsCommandHandler posMatcher Offset: Starts");

		DemandState ds = jadd(new DemandState(McsConfig.currentPosPrefix), jset(mcsStateKey, MCS_IDLE),
				jset(McsConfig.azPosKey, az), jset(McsConfig.elPosKey, el));
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
		System.out.println("Inside McsCommandHandler azPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(McsConfig.currentPosPrefix), jset(mcsStateKey, MCS_IDLE),
				jset(McsConfig.azPosKey, az));
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
		System.out.println("Inside McsCommandHandler elPosMatcher : Starts");

		DemandState ds = jadd(new DemandState(McsConfig.currentPosPrefix), jset(mcsStateKey, MCS_IDLE),
				jset(McsConfig.elPosKey, el));
		return new DemandMatcher(ds, false);
	}

}
