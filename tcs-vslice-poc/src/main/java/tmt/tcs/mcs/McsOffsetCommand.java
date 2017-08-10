package tmt.tcs.mcs;

import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static tmt.tcs.common.AssemblyStateActor.az;
import static tmt.tcs.common.AssemblyStateActor.azFollowing;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.azPointing;
import static tmt.tcs.common.AssemblyStateActor.el;
import static tmt.tcs.common.AssemblyStateActor.elFollowing;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.common.AssemblyStateActor.elPointing;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus.Error;
import csw.services.ccs.CommandStatus.NoLongerValid;
import csw.services.ccs.DemandMatcher;
import csw.services.ccs.HcdController;
import csw.services.ccs.Validation.WrongInternalStateIssue;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
import javacsw.services.ccs.JSequentialExecutor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.common.BaseCommand;

/*
 * This is an actor class which receives command specific to Offset Operation
 * And after any modifications if required, redirect the same to MCS HCD
 */
public class McsOffsetCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> mcsStateActor;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creats hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param ac
	 * @param sc
	 * @param mcsHcd
	 * @param mcsStartState
	 * @param stateActor
	 */
	public McsOffsetCommand(AssemblyContext ac, SetupConfig sc, ActorRef mcsHcd, AssemblyState mcsStartState,
			Optional<ActorRef> stateActor) {
		this.mcsStateActor = stateActor;

		receive(processCommand(sc, mcsHcd, mcsStartState));
	}

	@Override
	public PartialFunction<Object, BoxedUnit> processCommand(SetupConfig sc, ActorRef mcsHcd,
			AssemblyState mcsStartState) {

		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!(az(mcsStartState).equals(azPointing) && az(mcsStartState).equals(elPointing))) {
				String errorMessage = "Mcs Assembly state of " + az(mcsStartState) + "/" + el(mcsStartState)
						+ " does not allow move";
				log.debug("Inside McsOffsetCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				log.debug("Inside McsOffsetCommand: Move command -- START: " + t);

				DoubleItem azItem = jitem(sc, McsConfig.azDemandKey);
				DoubleItem elItem = jitem(sc, McsConfig.elDemandKey);

				Double az = jvalue(azItem);
				Double el = jvalue(elItem);

				log.debug("Inside McsOffsetCommand: Move command: sc is: " + sc + ": az is: " + az + ": el is: " + el);

				DemandMatcher stateMatcher = McsCommandHandler.posMatcher(az, el);
				SetupConfig scOut = jadd(sc(McsConfig.offsetPrefix), jset(McsConfig.az, az), jset(McsConfig.el, el));

				sendState(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)));

				mcsHcd.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				McsCommandHandler.executeMatch(context(), stateMatcher, mcsHcd, Optional.of(sender()), timeout,
						status -> {
							if (status == Completed) {
								log.debug("Inside McsOffsetCommand: Move Command Completed");
								sendState(mcsStateActor, new AssemblySetState(azItem(azPointing), elItem(elPointing)));
							} else if (status instanceof Error) {
								log.error("Inside McsOffsetCommand: Offset command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside McsOffsetCommand: Offset command -- STOP: " + t);
			mcsHcd.tell(new HcdController.Submit(jadd(sc("tcs.mcs.stop"))), self());
		}).matchAny(t -> log.warning("Inside McsOffsetCommand: Unknown message received: " + t)).build();
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef mcsHcd, AssemblyState mcsState,
			Optional<ActorRef> stateActor) {
		return Props.create(new Creator<McsOffsetCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsOffsetCommand create() throws Exception {
				return new McsOffsetCommand(ac, sc, mcsHcd, mcsState, stateActor);
			}
		});
	}
}
