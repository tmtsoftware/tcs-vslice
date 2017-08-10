package tmt.tcs;

import static akka.pattern.PatternsCS.ask;
import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;
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
import javacsw.services.ccs.JSequentialExecutor;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;

/*
 * This is an actor class which receives command specific to Offset Operation
 * And after any modifications if required, redirect the same to TPK
 */
public class TcsOffsetCommand extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> tcsStateActor;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creats hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param ac
	 * @param sc
	 * @param referedActor
	 * @param tcsStartState
	 * @param stateActor
	 */
	public TcsOffsetCommand(AssemblyContext ac, SetupConfig sc, ActorRef referedActor, AssemblyState tcsStartState,
			Optional<ActorRef> stateActor) {
		this.tcsStateActor = stateActor;

		receive(ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!az(tcsStartState).equals(azDatumed) && !el(tcsStartState).equals(azDatumed)) {
				String errorMessage = "Tcs Assembly state of " + az(tcsStartState) + "/" + el(tcsStartState)
						+ " does not allow move";
				log.debug("Inside TcsOffsetCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				log.debug("Inside TcsOffsetCommand: Move command -- START: " + t);

				DemandMatcher stateMatcher = TcsCommandHandler.posMatcher();
				SetupConfig scOut = jadd(sc(TcsConfig.offsetPrefix));

				sendState(new AssemblySetState(azItem(azPointing), elItem(elPointing)));

				referedActor.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				TcsCommandHandler.executeMatch(context(), stateMatcher, referedActor, Optional.of(sender()), timeout,
						status -> {
							if (status == Completed) {
								log.debug("Inside TcsOffsetCommand: Move Command Completed");
								sendState(new AssemblySetState(azItem(azDatumed), elItem(elDatumed)));
							} else if (status instanceof Error) {
								log.error("Inside TcsOffsetCommand: Offset command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside TcsOffsetCommand: Offset command -- STOP: " + t);
			referedActor.tell(new HcdController.Submit(jadd(sc("tcs.stop"))), self());
		}).matchAny(t -> log.warning("Inside TcsOffsetCommand: Unknown message received: " + t)).build());
	}

	/**
	 * This helps in updating assembly state while command execution
	 * 
	 * @param tcsSetState
	 */
	private void sendState(AssemblySetState tcsSetState) {
		tcsStateActor.ifPresent(actorRef -> {
			try {
				ask(actorRef, tcsSetState, 5000).toCompletableFuture().get();
			} catch (Exception e) {
				log.error(e, "Inside TcsOffsetCommand: sendState: Error setting state");
			}
		});
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef referedActor, AssemblyState tcsState,
			Optional<ActorRef> stateActor) {
		return Props.create(new Creator<TcsOffsetCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsOffsetCommand create() throws Exception {
				return new TcsOffsetCommand(ac, sc, referedActor, tcsState, stateActor);
			}
		});
	}
}
