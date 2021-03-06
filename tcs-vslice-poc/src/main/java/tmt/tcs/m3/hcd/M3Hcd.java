package tmt.tcs.m3.hcd;

import static javacsw.services.pkg.JSupervisor.DoRestart;
import static javacsw.services.pkg.JSupervisor.DoShutdown;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.services.pkg.JSupervisor.RunningOffline;
import static javacsw.services.pkg.JSupervisor.ShutdownComplete;
import static javacsw.util.config.JConfigDSL.cs;
import static javacsw.util.config.JItems.Choice;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static tmt.tcs.m3.M3Config.m3StateKey;

import java.io.File;
import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.StateVariable.CurrentState;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.BaseHcd;
import tmt.tcs.m3.M3Config;
import tmt.tcs.m3.M3Config.M3State;
import tmt.tcs.m3.hcd.M3Simulator.M3PosUpdate;

/**
 * This is the Top Level Actor for the M3 HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class M3Hcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;

	ActorRef m3Simulator;
	M3PosUpdate current;
	private static boolean publishDefaultEvent = true;

	public static File m3ConfigFile = new File("m3/hcd/m3Hcd.conf");
	public static File resource = new File("m3Hcd.conf");

	private M3Hcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside M3Hcd");

		this.supervisor = supervisor;
		this.m3Simulator = getSimulator();
		this.current = new M3PosUpdate(M3State.M3_IDLE, M3Config.defaultRotationValue, M3Config.defaultTiltValue);

		try {
			supervisor.tell(Initialized, self());
		} catch (Exception ex) {
			supervisor.tell(new Supervisor.InitializeFailure(ex.getMessage()), self());
		}

		receive(initializingReceive());
	}

	/**
	 * HDC's intializing receive method receives HCD messages
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> initializingReceive() {
		return publisherReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside M3Hcd received Running");
			context().become(runningReceive());
		}).matchAny(x -> log.warning("Inside M3Hcd Unexpected message (Not running yet): " + x)).build());
	}

	/**
	 * This method helps maintaining HCD's lifecycle
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> runningReceive() {
		return controllerReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside M3Hcd Received Running");
		}).matchEquals(RunningOffline, e -> {
			log.debug("Inside M3Hcd Received RunningOffline");
		}).matchEquals(DoRestart, e -> {
			log.debug("Inside M3Hcd Received DoRestart");
		}).matchEquals(DoShutdown, e -> {
			log.debug("Inside M3Hcd Received DoShutdown");
			supervisor.tell(ShutdownComplete, self());
		}).match(Supervisor.LifecycleFailureInfo.class, e -> {
			log.error("Inside M3Hcd Received failed state: " + e.state() + " for reason: " + e.reason());
		}).matchEquals(M3Message.GetM3UpdateNow, e -> {
			sender().tell(current, self());
		}).matchEquals(M3Message.GetM3DefaultUpdate, e -> {
			log.debug("Inside M3Hcd Received GetM3DefaultUpdate: publishDefaultEvent is: " + publishDefaultEvent);
			Thread.sleep(1000);
			CurrentState m3State = cs(M3Config.currentPosPrefix, jset(m3StateKey, Choice(current.state.toString())),
					jset(M3Config.rotationPosKey, current.rotationPosition),
					jset(M3Config.tiltPosKey, current.tiltPosition));
			notifySubscribers(m3State);
			if (publishDefaultEvent) {
				current.rotationPosition += M3Config.defaultRotationIncrementer;
				current.tiltPosition += M3Config.defaultTiltIncrementer;
				self().tell(M3Message.GetM3DefaultUpdate, self());
			}
		}).match(M3PosUpdate.class, e -> {
			log.debug("Inside M3Hcd Received M3Update: " + e);
			current = e;
			CurrentState m3State = cs(M3Config.currentPosPrefix, jset(m3StateKey, Choice(e.state.toString())),
					jset(M3Config.rotationPosKey, e.rotationPosition), jset(M3Config.tiltPosKey, e.tiltPosition));
			log.debug("Inside M3Hcd Sending CurrentState: " + m3State);
			notifySubscribers(m3State);
		}).matchAny(x -> log.warning("Inside M3Hcd Unexpected message :unhandledPF: " + x)).build());
	}

	@Override
	public void process(SetupConfig sc) {
		log.debug("Inside M3Hcd process received sc: " + sc);

		ConfigKey configKey = sc.configKey();

		if (configKey.equals(M3Config.followCK)) {
			log.debug("Inside M3Hcd process received move command");
			publishDefaultEvent = false;
			m3Simulator.tell(
					new M3Simulator.Move(jvalue(jitem(sc, M3Config.rotation)), jvalue(jitem(sc, M3Config.tilt))),
					self());
		} else {
			log.debug("Inside M3Hcd process received offset command");
			publishDefaultEvent = false;
			m3Simulator.tell(
					new M3Simulator.Move(jvalue(jitem(sc, M3Config.rotation)), jvalue(jitem(sc, M3Config.tilt))),
					self());
		}
	}

	private ActorRef getSimulator() {
		return context().actorOf(M3Simulator.props(Optional.of(self())), "M3Simulator");
	}

	public static Props props(final Component.HcdInfo info, ActorRef supervisor) {
		return Props.create(new Creator<M3Hcd>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3Hcd create() throws Exception {
				return new M3Hcd(info, supervisor);
			}
		});
	}

	public enum M3Message {
		GetM3DefaultUpdate,

		/**
		 * Directly returns an M3PosUpdate to sender
		 */
		GetM3UpdateNow
	}
}
