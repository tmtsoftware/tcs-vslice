package tmt.tcs;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;

import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.ccs.AssemblyController;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.ccs.JSequentialExecutor;
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.common.BaseCommand;
import tmt.tcs.mcs.McsConfig;
import tmt.tcs.tpk.TpkConfig;

/**
 * This is an actor class which receives command specific to Offset Operation
 * And after any modifications if required, redirect the same to MCS and TPK
 */
@SuppressWarnings("unused")
public class TcsOffsetCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> tcsStateActor;

	private final Optional<ActorRef> eventPublisher;

	AssemblyContext assemblyContext;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creates hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed
	 * 
	 * @param ac
	 * @param sc
	 * @param mcsRefActor
	 * @param ecsRefActor
	 * @param m3RefActor
	 * @param tcsStartState
	 * @param stateActor
	 */
	public TcsOffsetCommand(AssemblyContext ac, SetupConfig sc, ActorRef mcsRefActor, ActorRef ecsRefActor,
			ActorRef m3RefActor, ActorRef tpkRefActor, AssemblyState tcsStartState, Optional<ActorRef> stateActor,
			IEventService eventService, Optional<ActorRef> eventPublisher) {
		this.tcsStateActor = stateActor;
		this.assemblyContext = ac;
		this.eventPublisher = eventPublisher;

		receive(followReceive(sc, mcsRefActor, ecsRefActor, m3RefActor, tpkRefActor));
	}

	/**
	 * This method receives command being redirected by Command Handler and
	 * forward offset command to MCS and TPK
	 * 
	 * @param sc
	 * @param mcsRefActor
	 * @param ecsRefActor
	 * @param m3RefActor
	 * @param tpkRefActor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> followReceive(SetupConfig sc, ActorRef mcsRefActor, ActorRef ecsRefActor,
			ActorRef m3RefActor, ActorRef tpkRefActor) {

		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			log.debug("Inside TcsOffsetCommand: Offset command -- START: " + t + ": Config Key is: " + sc.configKey());

			// Forward below parameters to TPK
			Double ra = jvalue(jitem(sc, TcsConfig.ra));
			Double dec = jvalue(jitem(sc, TcsConfig.dec));

			log.debug("Inside TcsOffsetCommand: Offset	 command: ra is: " + ra + ": dec is: " + dec);

			SetupConfig offsetSc = jadd(new SetupConfig(TpkConfig.offsetCK.prefix()), jset(TpkConfig.ra, ra),
					jset(TpkConfig.dec, dec));

			SetupConfigArg tpkSetupConfigArg = Configurations.createSetupConfigArg("tpkOffsetCommand", offsetSc);

			SetupConfigArg mcsSetupConfigArg = Configurations.createSetupConfigArg("mcsFollowCommand",
					new SetupConfig(McsConfig.offsetPrefix));

			log.debug("Inside TcsFollowCommand: Offset command -- mcsRefActor is: " + mcsRefActor);

			mcsRefActor.tell(new AssemblyController.Submit(mcsSetupConfigArg), self());

			tpkRefActor.tell(new AssemblyController.Submit(tpkSetupConfigArg), self());

		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside TcsOffsetCommand: Offset command -- STOP: " + t);
		}).matchAny(t -> log.warning("Inside TcsOffsetCommand: Unknown message received: " + t)).build();
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef mcsRefActor, ActorRef ecsRefActor,
			ActorRef m3RefActor, ActorRef tpkRefActor, AssemblyState tcsState, Optional<ActorRef> stateActor,
			IEventService eventService, Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<TcsOffsetCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsOffsetCommand create() throws Exception {
				return new TcsOffsetCommand(ac, sc, mcsRefActor, ecsRefActor, m3RefActor, tpkRefActor, tcsState,
						stateActor, eventService, eventPublisher);
			}
		});
	}
}
