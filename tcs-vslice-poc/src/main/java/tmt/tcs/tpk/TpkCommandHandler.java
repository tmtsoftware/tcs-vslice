package tmt.tcs.tpk;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
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
import csw.services.ccs.CommandStatus.Invalid;
import csw.services.ccs.SequentialExecutor.ExecuteOne;
import csw.services.ccs.Validation.UnsupportedCommandInStateIssue;
import csw.services.loc.LocationService.Location;
import csw.services.loc.LocationService.ResolvedAkkaLocation;
import csw.services.loc.LocationService.ResolvedTcpLocation;
import csw.services.loc.LocationService.Unresolved;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
import javacsw.services.ccs.JSequentialExecutor;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.BaseCommandHandler;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;
import tmt.tcs.tpk.TpkEventPublisher.EcsPosDemand;
import tmt.tcs.tpk.TpkEventPublisher.M3PosDemand;
import tmt.tcs.tpk.TpkEventPublisher.McsPosDemand;
import tmt.tcs.tpk.wrapper.IDemandsCB;
import tmt.tcs.tpk.wrapper.TpkPoc;

/**
 * This is an actor class which receives commands forwarded by TCS Assembly
 * Makes call to TPK JNI Wrapper for demand generation and publish the same
 * using event publisher
 */
@SuppressWarnings("unused")
public class TpkCommandHandler extends BaseCommandHandler {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> eventPublisher;

	private Optional<IEventService> badEventService = Optional.empty();
	private Optional<IEventService> eventService = badEventService;

	private TpkWrapper tpkWrapper;

	public TpkCommandHandler(Optional<ActorRef> eventPublisher) {

		log.debug("Inside TpkCommandHandler");

		this.eventPublisher = eventPublisher;

		subscribeToLocationUpdates();

		initiateTpkEndpoint();

		receive(initReceive());
	}

	/**
	 * This helps in initializing TPK JNI Wrapper in seperate thread, so that
	 * New Target and Offset requests can be passed on to it
	 */
	public void initiateTpkEndpoint() {
		log.debug("Inside TpkCommandHandler initiateTpkEndpoint");

		System.loadLibrary("example");

		tpkWrapper = new TpkWrapper();

		new Thread(new Runnable() {
			public void run() {
				tpkWrapper.initiate();
			}
		}).start();

		try {
			Thread.sleep(100, 0);
		} catch (InterruptedException e) {
			log.error("Inside TpkCommandHandler initiateTpkEndpoint: Error is: " + e);
		}
	}

	/**
	 * This method handles the locations
	 * 
	 * @param location
	 */
	private void handleLocations(Location location) {
		if (location instanceof ResolvedAkkaLocation) {
			ResolvedAkkaLocation l = (ResolvedAkkaLocation) location;
			log.debug("Inside TpkCommandHandler: CommandHandler receive an actorRef: " + l.getActorRef());
		} else if (location instanceof ResolvedTcpLocation) {
			ResolvedTcpLocation t = (ResolvedTcpLocation) location;
			log.debug("Inside TpkCommandHandler: Received TCP Location: " + t.connection());
			if (location.connection().equals(IEventService.eventServiceConnection())) {
				log.debug("Inside TpkCommandHandler: Assembly received ES connection: " + t);
				eventService = Optional.of(IEventService.getEventService(t.host(), t.port(), context().system()));
				log.debug("Inside TpkCommandHandler: Event Service at: " + eventService);
			}

		} else if (location instanceof Unresolved) {
			log.debug("Inside TpkCommandHandler: Unresolved: " + location.connection());
		} else {
			log.debug("Inside TpkCommandHandler: CommandHandler received some other location: " + location);
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

					log.debug("Inside TpkCommandHandler initReceive: ExecuteOne: SetupConfig is: " + sc
							+ ": configKey is: " + configKey);

					if (TpkConfig.followCK.equals(configKey)) {

						String target = jvalue(jitem(sc, TpkConfig.target));
						Double ra = jvalue(jitem(sc, TpkConfig.ra));
						Double dec = jvalue(jitem(sc, TpkConfig.dec));
						String frame = jvalue(jitem(sc, TpkConfig.frame));

						log.debug("Inside TpkCommandHandler initReceive: configKey is: " + configKey);

						// Call TPK Endpoint with New target coordinates
						tpkWrapper.newTarget(ra, dec);

						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
					} else if (TpkConfig.offsetCK.equals(configKey)) {

						Double ra0 = jvalue(jitem(sc, TpkConfig.ra));
						Double dec0 = jvalue(jitem(sc, TpkConfig.dec));

						log.debug("Inside TpkCommandHandler initReceive: configKey is: " + configKey);

						// Call TPK Endpoint with Offset coordinates
						tpkWrapper.offset(ra0, dec0);

						commandOriginator.ifPresent(actorRef -> actorRef.tell(Completed, self()));
					} else {
						log.error("Inside TpkCommandHandler initReceive: Received an unknown command: " + t + " from "
								+ sender());
						commandOriginator
								.ifPresent(
										actorRef -> actorRef.tell(
												new Invalid(new UnsupportedCommandInStateIssue(
														"Tpk assembly does not support the command "
																+ configKey.prefix() + " in the current state.")),
												self()));
					}

				}).build());
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
			log.debug("Inside TpkCommandHandler actorExecutingReceive: JSequentialExecutor.CommandStart");

			ask(currentCommand, JSequentialExecutor.CommandStart(), timeout.duration().toMillis()).thenApply(reply -> {
				CommandStatus cs = (CommandStatus) reply;
				log.debug("Inside TpkCommandHandler actorExecutingReceive: CommandStatus is: " + cs);
				commandOriginator.ifPresent(actorRef -> actorRef.tell(cs, self()));
				currentCommand.tell(PoisonPill.getInstance(), self());
				return null;
			});
		}).

				match(CommandDone.class, t -> {
					log.debug("Inside TpkCommandHandler actorExecutingReceive: CommandDone");
					context().become(initReceive());
				}).

				match(SetupConfig.class, t -> {
					log.debug("Inside TpkCommandHandler actorExecutingReceive: SetupConfig");
				}).

				match(ExecuteOne.class, t -> {
					log.debug("Inside TpkCommandHandler actorExecutingReceive: ExecuteOne");
				})
				.matchAny(t -> log
						.warning("Inside TpkCommandHandler actorExecutingReceive: received an unknown message: " + t))
				.build();
	}

	public void publishMcsPosDemand(ConfigKey configKey, DoubleItem azItem, DoubleItem elItem,
			Optional<ActorRef> eventPublisher) {
		log.debug("Inside TpkCommandHandler publishMcsPosDemand publish demand: azItem is: " + azItem + ": elItem is: "
				+ elItem);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new McsPosDemand(configKey, azItem, elItem), self()));
	}

	public void publishEcsPosDemand(ConfigKey configKey, DoubleItem azItem, DoubleItem elItem,
			Optional<ActorRef> eventPublisher) {
		log.debug("Inside TpkCommandHandler publishEcsPosDemand publish demand: azItem is: " + azItem + ": elItem is: "
				+ elItem);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new EcsPosDemand(configKey, azItem, elItem), self()));
	}

	public void publishM3PosDemand(ConfigKey configKey, DoubleItem rotationItem, DoubleItem tiltItem,
			Optional<ActorRef> eventPublisher) {
		log.debug("Inside TpkCommandHandler publishM3PosDemand publish demand: rotationItem is: " + rotationItem
				+ ": tiltItem is: " + tiltItem);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new M3PosDemand(configKey, rotationItem, tiltItem), self()));
	}

	public static Props props(Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<TpkCommandHandler>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TpkCommandHandler create() throws Exception {
				return new TpkCommandHandler(eventPublisher);
			}
		});
	}

	public class TpkWrapper {

		private TpkPoc tpkEndpoint;

		private boolean publishDemands = false;
		private ConfigKey configKey = null;

		/**
		 * Callback which is register with the C++ code and call from the fast
		 * loop
		 *
		 */
		public class DemandsCallback extends IDemandsCB {

			double ci = 32.5;
			double ciz = 90 - ci;
			double phir = Math.PI * ci / 180;
			double tci = Math.tan(ci);
			double cci = Math.cos(ci);
			double PI2 = Math.PI * 2;

			public void newDemands(double mcsAz, double mcsEl, double ecsAz, double ecsEl, double m3Rotation,
					double m3Tilt) {

				// Convert eAz, eEl into base & cap coordinates
				double azShift, base1, cap1, base2, cap2;
				if ((ecsEl > PI2) || (ecsEl < 0))
					ecsEl = 0;
				if ((ecsAz > PI2) || (ecsAz < 0))
					ecsAz = 0;

				cap1 = Math.acos(Math.tan(ecsEl - ciz) / tci);
				cap2 = PI2 - cap1;

				if (ecsEl == PI2)
					azShift = 0;
				else
					azShift = Math.atan(Math.sin(cap1) / cci * (1 - Math.cos(cap1)));

				if ((ecsAz + azShift) > PI2)
					base1 = (ecsAz + azShift) - PI2;
				else
					base1 = ecsAz + azShift;

				if (ecsAz < azShift)
					base2 = PI2 + ecsAz - azShift;
				else
					base2 = ecsAz - azShift;

				base1 = 180 * base1 / Math.PI;
				cap1 = 180 * cap1 / Math.PI;

				if (publishDemands) {
					// System.out.printf("%.2f, %.2f, %.2f, %.2f, %.2f, %.2f\n",
					// mAz, mEl, base1, cap1, m3R, m3T);

					if (TpkConfig.positionDemandCK.equals(configKey)) {
						DoubleItem mcsAzItem = jset(McsConfig.azDemandKey, mcsAz);
						DoubleItem mcsElItem = jset(McsConfig.elDemandKey, mcsEl);
						DoubleItem ecsAzItem = jset(EcsConfig.azDemandKey, base1);
						DoubleItem ecsElItem = jset(EcsConfig.elDemandKey, cap1);
						DoubleItem m3RotationItem = jset(M3Config.rotationDemandKey, m3Rotation);
						DoubleItem m3TiltItem = jset(M3Config.tiltDemandKey, m3Tilt);

						publishMcsPosDemand(McsConfig.positionDemandCK, mcsAzItem, mcsElItem, eventPublisher);

						publishEcsPosDemand(EcsConfig.positionDemandCK, ecsAzItem, ecsElItem, eventPublisher);

						publishM3PosDemand(M3Config.positionDemandCK, m3RotationItem, m3TiltItem, eventPublisher);
					} else if (TpkConfig.offsetDemandCK.equals(configKey)) {
						DoubleItem mcsAzItem = jset(McsConfig.azDemandKey, mcsAz);
						DoubleItem mcsElItem = jset(McsConfig.elDemandKey, mcsEl);

						publishMcsPosDemand(McsConfig.offsetDemandCK, mcsAzItem, mcsElItem, eventPublisher);

					}
				}
			}
		}

		public void initiate() {
			DemandsCallback cb = new DemandsCallback();
			tpkEndpoint = new TpkPoc();
			tpkEndpoint._register(cb);

			tpkEndpoint.init();
		}

		/**
		 * New target from Ra, Dec in degrees. Target applies to Mount and
		 * Enclosure
		 * 
		 * @param ra
		 * @param dec
		 */
		void newTarget(double ra, double dec) {
			publishDemands = true;
			configKey = TpkConfig.positionDemandCK;
			log.debug("Inside TpkCommandHandler TpkWrapper: newTarget: configKey is: " + configKey);
			tpkEndpoint.newTarget(ra, dec);
		}

		/**
		 * New mount offset. Ra, Dec offset values are in arcseconds
		 * 
		 * @param raO
		 * @param decO
		 */
		void offset(double raO, double decO) {
			publishDemands = true;
			configKey = TpkConfig.offsetDemandCK;
			log.debug("Inside TpkCommandHandler TpkWrapper: offset: configKey is: " + configKey);
			tpkEndpoint.offset(raO, decO);
		}

	}

}
