package tmt.tcs.mcs.hcd;

import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.util.config.JConfigDSL.cs;
import static javacsw.util.config.JItems.Choice;
import static javacsw.util.config.JItems.jset;
import static tmt.tcs.mcs.McsConfig.mcsStateKey;
import static tmt.tcs.mcs.McsConfig.McsState.MCS_IDLE;

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
import tmt.tcs.mcs.McsConfig;

/**
 * This is the Top Level Actor for the Mcs HCD It supports below operations-
 * Initializes itself from Configuration Service, Works with the Supervisor to
 * implement the lifecycle, Handles incoming commands
 */
public class McsHcd extends BaseHcd {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final ActorRef supervisor;

	public static File mcsConfigFile = new File("mcs/hcd/mcsHcd.conf");
	public static File resource = new File("mcsHcd.conf");

	private McsHcd(final Component.HcdInfo info, ActorRef supervisor) throws Exception {
		log.debug("Inside McsHcd");

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
		log.debug("Inside McsHcd process received sc: " + sc);

		CurrentState mcsState;
		ConfigKey configKey = sc.configKey();

		if (configKey.equals(McsConfig.moveCK)) {
			log.debug("Inside McsHcd process received move command");
			mcsState = cs(McsConfig.mcsStatePrefix, jset(mcsStateKey, Choice(MCS_IDLE.toString())));
		} else {
			log.debug("Inside McsHcd process received offset command");
			mcsState = cs(McsConfig.mcsStatsPrefix, jset(mcsStateKey, Choice(MCS_IDLE.toString())));
		}
		notifySubscribers(mcsState);
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

}
