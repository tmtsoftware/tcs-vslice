package tmt.tcs.mcs.hcd;

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
import static tmt.tcs.mcs.McsConfig.mcsStateKey;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.StateVariable.CurrentState;
import javacsw.services.cs.akka.JConfigServiceClient;
import scala.PartialFunction;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.BaseHcd;
import tmt.tcs.mcs.McsConfig;
import tmt.tcs.mcs.McsConfig.McsState;
import tmt.tcs.mcs.hcd.McsSimulator.McsPosUpdate;

/**
 * This is the Top Level Actor for the Mcs HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class McsHcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;

	ActorRef mcsSimulator;
	McsPosUpdate current;
	private static boolean publishDefaultEvent = true;

	// McsHWConfig mcsHWConfig;

	public static File mcsConfigFile = new File("mcs/hcd/mcsHcd.conf");
	public static File resource = new File("mcsHcd.conf");

	private final Timeout timeout = new Timeout(Duration.create(2, "seconds"));

	private McsHcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside McsHcd");

		this.supervisor = supervisor;
		// TODO: Giving Ask timeout exception, to be fixed
		// this.mcsHWConfig = getMcsHWConfig().get();
		this.mcsSimulator = getSimulator();
		this.current = new McsPosUpdate(McsState.MCS_IDLE, McsConfig.defaultAzValue, McsConfig.defaultElValue);

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
			log.debug("Inside McsHcd received Running");
			context().become(runningReceive());
		}).matchAny(x -> log.warning("Inside McsHcd Unexpected message (Not running yet): " + x)).build());
	}

	/**
	 * This method helps maintaining HCD's lifecycle
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> runningReceive() {
		return controllerReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside McsHcd Received Running");
		}).matchEquals(RunningOffline, e -> {
			log.debug("Inside McsHcd Received RunningOffline");
		}).matchEquals(DoRestart, e -> {
			log.debug("Inside McsHcd Received DoRestart");
		}).matchEquals(DoShutdown, e -> {
			log.debug("Inside McsHcd Received DoShutdown");
			supervisor.tell(ShutdownComplete, self());
		}).match(Supervisor.LifecycleFailureInfo.class, e -> {
			log.error("Inside McsHcd Received failed state: " + e.state() + " for reason: " + e.reason());
		}).matchEquals(McsMessage.GetMcsUpdateNow, e -> {
			sender().tell(current, self());
		}).matchEquals(McsMessage.GetMcsDefaultUpdate, e -> {
			log.debug("Inside McsHcd Received GetMcsDefaultUpdate: publishDefaultEvent is: " + publishDefaultEvent);
			Thread.sleep(1000);
			CurrentState mcsState = cs(McsConfig.currentPosPrefix, jset(mcsStateKey, Choice(current.state.toString())),
					jset(McsConfig.azPosKey, current.azPosition), jset(McsConfig.elPosKey, current.elPosition));
			notifySubscribers(mcsState);
			if (publishDefaultEvent) {
				current.azPosition += McsConfig.defaultAzIncrementer;
				current.elPosition += McsConfig.defaultElIncrementer;
				self().tell(McsMessage.GetMcsDefaultUpdate, self());
			}
		}).match(McsPosUpdate.class, e -> {
			log.debug("Inside McsHcd Received McsUpdate: " + e);
			current = e;
			CurrentState mcsState = cs(McsConfig.currentPosPrefix, jset(mcsStateKey, Choice(e.state.toString())),
					jset(McsConfig.azPosKey, e.azPosition), jset(McsConfig.elPosKey, e.elPosition));
			log.debug("Inside McsHcd Sending CurrentState: " + mcsState);
			notifySubscribers(mcsState);
		}).matchAny(x -> log.warning("Inside McsHcd Unexpected message :unhandledPF: " + x)).build());
	}

	/**
	 * This method helps in receiving commands being sent to HCD Class and based
	 * upon Config Key it performs command specific operation
	 */
	@Override
	public void process(SetupConfig sc) {
		log.debug("Inside McsHcd process received sc: " + sc);

		ConfigKey configKey = sc.configKey();

		if (configKey.equals(McsConfig.followCK)) {
			log.debug("Inside McsHcd process received move command");
			publishDefaultEvent = false;
			mcsSimulator.tell(new McsSimulator.Move(jvalue(jitem(sc, McsConfig.az)), jvalue(jitem(sc, McsConfig.el))),
					self());
		} else {
			log.debug("Inside McsHcd process received offset command");
			publishDefaultEvent = false;
			mcsSimulator.tell(new McsSimulator.Move(jvalue(jitem(sc, McsConfig.az)), jvalue(jitem(sc, McsConfig.el))),
					self());
		}
	}

	/**
	 * This helps in instantiating Simulator Class
	 * 
	 * @return
	 */
	private ActorRef getSimulator() {
		return context().actorOf(McsSimulator.props(Optional.of(self())), "McsSimulator");
	}

	@SuppressWarnings("unused")
	private CompletableFuture<McsHWConfig> getMcsHWConfig() {
		log.debug("Inside McsHcd getMcsHWConfig");

		return JConfigServiceClient.getConfigFromConfigService(mcsConfigFile, Optional.empty(), Optional.of(resource),
				context().system(), timeout).thenApply(config -> new McsHWConfig(config.get()));
	}

	public static Props props(final Component.HcdInfo info, ActorRef supervisor) {
		return Props.create(new Creator<McsHcd>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsHcd create() throws Exception {
				return new McsHcd(info, supervisor);
			}
		});
	}

	public enum McsMessage {
		GetMcsDefaultUpdate,

		/**
		 * Directly returns an McsPosUpdate to sender
		 */
		GetMcsUpdateNow
	}

}
