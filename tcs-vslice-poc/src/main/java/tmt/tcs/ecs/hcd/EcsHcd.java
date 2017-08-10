package tmt.tcs.ecs.hcd;

import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;

import java.io.File;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.StateVariable.CurrentState;
import tmt.tcs.common.BaseHcd;
import tmt.tcs.ecs.EcsConfig;

/**
 * This is the Top Level Actor for the Ecs HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class EcsHcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final ActorRef supervisor;

	public static File ecsConfigFile = new File("ecs/hcd/ecsHcd.conf");
	public static File resource = new File("ecsHcd.conf");

	private EcsHcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside EcsHcd");

		this.supervisor = supervisor;

		try {
			supervisor.tell(Initialized, self());
		} catch (Exception ex) {
			supervisor.tell(new Supervisor.InitializeFailure(ex.getMessage()), self());
		}

		receive(initializingReceive(supervisor));
	}

	@Override
	public void process(SetupConfig sc) {
		log.debug("Inside EcsHcd process received sc: " + sc);

		CurrentState ecsState;
		ConfigKey configKey = sc.configKey();

		if (configKey.equals(EcsConfig.moveCK)) {
			log.debug("Inside EcsHcd process received move command");
			ecsState = jadd(EcsConfig.defaultEcsStatsState, jset(EcsConfig.az, 1.0));
		} else {
			log.debug("Inside EcsHcd process received offset command");
			ecsState = jadd(EcsConfig.defaultEcsStatsState, jset(EcsConfig.az, 2.0));
		}
		notifySubscribers(ecsState);
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
}
