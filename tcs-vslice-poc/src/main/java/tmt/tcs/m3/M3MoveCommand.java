package tmt.tcs.m3;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static tmt.tcs.common.AssemblyStateActor.az;
import static tmt.tcs.common.AssemblyStateActor.azDatumed;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.azPointing;
import static tmt.tcs.common.AssemblyStateActor.el;
import static tmt.tcs.common.AssemblyStateActor.elDatumed;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.common.AssemblyStateActor.elPointing;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import akka.actor.AbstractActor;
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
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;

/*
 * This is an actor class which receives command specific to Move Operation
 * And after any modifications if required, redirect the same to M3 HCD
 */
public class M3MoveCommand extends AbstractActor {

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
	public M3MoveCommand(AssemblyContext ac, SetupConfig sc, ActorRef m3Hcd, AssemblyState m3StartState,
			Optional<ActorRef> stateActor) {
		this.m3StateActor = stateActor;

		receive(ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!az(m3StartState).equals(azDatumed) && !el(m3StartState).equals(azDatumed)) {
				String errorMessage = "M3 Assembly state of " + az(m3StartState) + "/" + el(m3StartState)
						+ " does not allow move";
				log.debug("Inside M3MoveCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				log.debug("Inside M3MoveCommand: Move command -- START: " + t);

				DoubleItem azItem = jitem(sc, M3Config.rotationDemandKey);
				DoubleItem elItem = jitem(sc, M3Config.tiltDemandKey);
				DoubleItem timeItem = jitem(sc, M3Config.timeDemandKey);

				Double az = jvalue(azItem);
				Double el = jvalue(elItem);
				Double time = jvalue(timeItem);

				log.debug("Inside M3MoveCommand: Move command: sc is: " + sc + ": az is: " + az + ": el is: "
						+ el + ": time is: " + time);

				DemandMatcher stateMatcher = M3CommandHandler.posMatcher(az, el, time);
				SetupConfig scOut = jadd(sc(M3Config.movePrefix), jset(M3Config.rotation, az), jset(M3Config.tilt, el),
						jset(M3Config.time, time));

				sendState(new AssemblySetState(azItem(azPointing), elItem(elPointing)));

				m3Hcd.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				M3CommandHandler.executeMatch(context(), stateMatcher, m3Hcd, Optional.of(sender()), timeout,
						status -> {
							if (status == Completed) {
								log.debug("Inside M3MoveCommand: Move Command Completed");
								sendState(new AssemblySetState(azItem(azDatumed), elItem(elDatumed)));
							} else if (status instanceof Error) {
								log.error("Inside M3MoveCommand: Move command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside M3MoveCommand: Move command -- STOP: " + t);
			m3Hcd.tell(new HcdController.Submit(jadd(sc("tcs.m3.stop"))), self());
		}).matchAny(t -> log.warning("Inside M3MoveCommand: Unknown message received: " + t)).build());
	}

	/**
	 * This helps in updating assembly state while command execution
	 * 
	 * @param m3SetState
	 */
	private void sendState(AssemblySetState m3SetState) {
		m3StateActor.ifPresent(actorRef -> {
			try {
				ask(actorRef, m3SetState, 5000).toCompletableFuture().get();
			} catch (Exception e) {
				log.error(e, "Inside M3MoveCommand: sendState: Error setting state");
			}
		});
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef m3Hcd, AssemblyState m3State,
			Optional<ActorRef> stateActor) {
		return Props.create(new Creator<M3MoveCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3MoveCommand create() throws Exception {
				return new M3MoveCommand(ac, sc, m3Hcd, m3State, stateActor);
			}
		});
	}
}
