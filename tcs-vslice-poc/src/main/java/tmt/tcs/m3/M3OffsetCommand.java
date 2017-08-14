package tmt.tcs.m3;

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
 * And after any modifications if required, redirect the same to M3 HCD
 */
public class M3OffsetCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> m3StateActor;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creats hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param ac
	 * @param sc
	 * @param m3Hcd
	 * @param m3StartState
	 * @param stateActor
	 */
	public M3OffsetCommand(AssemblyContext ac, SetupConfig sc, ActorRef m3Hcd, AssemblyState m3StartState,
			Optional<ActorRef> stateActor) {
		this.m3StateActor = stateActor;

		receive(processCommand(sc, m3Hcd, m3StartState));
	}

	@Override
	public PartialFunction<Object, BoxedUnit> processCommand(SetupConfig sc, ActorRef m3Hcd,
			AssemblyState m3StartState) {

		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!(az(m3StartState).equals(azPointing) && az(m3StartState).equals(elPointing))) {
				String errorMessage = "M3 Assembly state of " + az(m3StartState) + "/" + el(m3StartState)
						+ " does not allow move";
				log.debug("Inside M3OffsetCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				log.debug("Inside M3OffsetCommand: Move command -- START: " + t);

				DoubleItem xItem = jitem(sc, M3Config.rotationDemandKey);
				DoubleItem yItem = jitem(sc, M3Config.tiltDemandKey);

				Double x = jvalue(xItem);
				Double y = jvalue(yItem);

				log.debug("Inside M3OffsetCommand: Move command: sc is: " + sc + ": x is: " + x + ": y is: " + y);

				DemandMatcher stateMatcher = M3CommandHandler.posMatcher(x, y);
				SetupConfig scOut = jadd(sc(M3Config.offsetPrefix), jset(M3Config.rotation, x), jset(M3Config.tilt, y));

				sendState(m3StateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)));

				m3Hcd.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				M3CommandHandler.executeMatch(context(), stateMatcher, m3Hcd, Optional.of(sender()), timeout,
						status -> {
							if (status == Completed) {
								log.debug("Inside M3OffsetCommand: Move Command Completed");
								sendState(m3StateActor, new AssemblySetState(azItem(azPointing), elItem(elPointing)));
							} else if (status instanceof Error) {
								log.error("Inside M3OffsetCommand: Offset command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside M3OffsetCommand: Offset command -- STOP: " + t);
			m3Hcd.tell(new HcdController.Submit(jadd(sc("tcs.m3.stop"))), self());
		}).matchAny(t -> log.warning("Inside M3OffsetCommand: Unknown message received: " + t)).build();
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef m3Hcd, AssemblyState m3State,
			Optional<ActorRef> stateActor) {
		return Props.create(new Creator<M3OffsetCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3OffsetCommand create() throws Exception {
				return new M3OffsetCommand(ac, sc, m3Hcd, m3State, stateActor);
			}
		});
	}
}
