package tmt.tcs.m3.hcd;

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
import tmt.tcs.m3.M3Config;

/**
 * This is the Top Level Actor for the M3 HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class M3Hcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final ActorRef supervisor;

	public static File m3ConfigFile = new File("m3/hcd/m3Hcd.conf");
	public static File resource = new File("m3Hcd.conf");

	private M3Hcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside M3Hcd");

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
		log.debug("Inside M3Hcd process received sc: " + sc);

		CurrentState m3State;
		ConfigKey configKey = sc.configKey();

		if (configKey.equals(M3Config.moveCK)) {
			log.debug("Inside M3Hcd process received move command");
			m3State = jadd(M3Config.defaultM3StatsState, jset(M3Config.rotation, 1.0));
		} else {
			log.debug("Inside M3Hcd process received offset command");
			m3State = jadd(M3Config.defaultM3StatsState, jset(M3Config.rotation, 2.0));
		}
		notifySubscribers(m3State);
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
}
