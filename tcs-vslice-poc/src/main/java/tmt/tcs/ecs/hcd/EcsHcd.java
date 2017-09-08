package tmt.tcs.ecs.hcd;

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
import static tmt.tcs.ecs.EcsConfig.ecsStateKey;

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
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.ecs.EcsConfig.EcsState;
import tmt.tcs.ecs.hcd.EcsSimulator.EcsPosUpdate;

/**
 * This is the Top Level Actor for the Ecs HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class EcsHcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final ActorRef supervisor;

	ActorRef ecsSimulator;
	EcsPosUpdate current;
	private static boolean publishDefaultEvent = true;

	public static File ecsConfigFile = new File("ecs/hcd/ecsHcd.conf");
	public static File resource = new File("ecsHcd.conf");

	private EcsHcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside EcsHcd");

		this.supervisor = supervisor;
		this.ecsSimulator = getSimulator();
		this.current = new EcsPosUpdate(EcsState.ECS_IDLE, EcsConfig.defaultAzValue, EcsConfig.defaultElValue);

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
			log.debug("Inside EcsHcd received Running");
			context().become(runningReceive());
		}).matchAny(x -> log.warning("Inside EcsHcd Unexpected message (Not running yet): " + x)).build());
	}

	/**
	 * This method helps maintaining HCD's lifecycle
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> runningReceive() {
		return controllerReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside EcsHcd Received Running");
		}).matchEquals(RunningOffline, e -> {
			log.debug("Inside EcsHcd Received RunningOffline");
		}).matchEquals(DoRestart, e -> {
			log.debug("Inside EcsHcd Received DoRestart");
		}).matchEquals(DoShutdown, e -> {
			log.debug("Inside EcsHcd Received DoShutdown");
			supervisor.tell(ShutdownComplete, self());
		}).match(Supervisor.LifecycleFailureInfo.class, e -> {
			log.error("Inside EcsHcd Received failed state: " + e.state() + " for reason: " + e.reason());
		}).matchEquals(EcsMessage.GetEcsUpdateNow, e -> {
			sender().tell(current, self());
		}).matchEquals(EcsMessage.GetEcsDefaultUpdate, e -> {
			log.debug("Inside EcsHcd Received GetEcsDefaultUpdate: publishDefaultEvent is: " + publishDefaultEvent);
			Thread.sleep(1000);
			CurrentState ecsState = cs(EcsConfig.currentPosPrefix, jset(ecsStateKey, Choice(current.state.toString())),
					jset(EcsConfig.azPosKey, current.azPosition), jset(EcsConfig.elPosKey, current.elPosition));
			notifySubscribers(ecsState);
			if (publishDefaultEvent) {
				current.azPosition += EcsConfig.defaultAzIncrementer;
				current.elPosition += EcsConfig.defaultElIncrementer;
				self().tell(EcsMessage.GetEcsDefaultUpdate, self());
			}
		}).match(EcsPosUpdate.class, e -> {
			log.debug("Inside EcsHcd Received EcsUpdate: " + e);
			current = e;
			CurrentState ecsState = cs(EcsConfig.currentPosPrefix, jset(ecsStateKey, Choice(e.state.toString())),
					jset(EcsConfig.azPosKey, e.azPosition), jset(EcsConfig.elPosKey, e.elPosition));
			log.debug("Inside EcsHcd Sending CurrentState: " + ecsState);
			notifySubscribers(ecsState);
		}).matchAny(x -> log.warning("Inside EcsHcd Unexpected message :unhandledPF: " + x)).build());
	}

	@Override
	public void process(SetupConfig sc) {
		log.debug("Inside EcsHcd process received sc: " + sc);

		ConfigKey configKey = sc.configKey();

		if (configKey.equals(EcsConfig.followCK)) {
			log.debug("Inside EcsHcd process received move command");
			publishDefaultEvent = false;
			ecsSimulator.tell(new EcsSimulator.Move(jvalue(jitem(sc, EcsConfig.az)), jvalue(jitem(sc, EcsConfig.el))),
					self());
		} else {
			log.debug("Inside EcsHcd process received offset command");
			publishDefaultEvent = false;
			ecsSimulator.tell(new EcsSimulator.Move(jvalue(jitem(sc, EcsConfig.az)), jvalue(jitem(sc, EcsConfig.el))),
					self());
		}
	}

	private ActorRef getSimulator() {
		return context().actorOf(EcsSimulator.props(Optional.of(self())), "EcsSimulator");
	}

	public static Props props(final Component.HcdInfo info, ActorRef supervisor) {
		return Props.create(new Creator<EcsHcd>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsHcd create() throws Exception {
				return new EcsHcd(info, supervisor);
			}
		});
	}

	public enum EcsMessage {
		GetEcsDefaultUpdate,

		/**
		 * Directly returns an EcsPosUpdate to sender
		 */
		GetEcsUpdateNow
	}
}
