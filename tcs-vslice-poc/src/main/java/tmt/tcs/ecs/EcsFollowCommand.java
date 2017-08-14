package tmt.tcs.ecs;

import static javacsw.services.ccs.JCommandStatus.Completed;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;
import static tmt.tcs.common.AssemblyStateActor.az;
import static tmt.tcs.common.AssemblyStateActor.azDatumed;
import static tmt.tcs.common.AssemblyStateActor.azDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.azFollowing;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.azPointing;
import static tmt.tcs.common.AssemblyStateActor.el;
import static tmt.tcs.common.AssemblyStateActor.elDatumed;
import static tmt.tcs.common.AssemblyStateActor.elDrivePowerOn;
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

/**
 * This is an actor class which receives command specific to Move Operation
 * And after any modifications if required, redirect the same to ECS HCD
 */
public class EcsFollowCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> ecsStateActor;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creats hcd specific setupconfig and
	 * forwards the same And marks command as complete or failed based on
	 * response and Demand Matching
	 * 
	 * @param ac
	 * @param sc
	 * @param ecsHcd
	 * @param ecsStartState
	 * @param stateActor
	 */
	public EcsFollowCommand(AssemblyContext ac, SetupConfig sc, ActorRef ecsHcd, AssemblyState ecsStartState,
			Optional<ActorRef> stateActor) {
		
		this.ecsStateActor = stateActor;

		receive(processCommand(sc, ecsHcd, ecsStartState));
	}

	@Override
	public PartialFunction<Object, BoxedUnit> processCommand(SetupConfig sc, ActorRef ecsHcd,
			AssemblyState ecsStartState) {
		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!(az(ecsStartState).equals(azDatumed) || az(ecsStartState).equals(azDrivePowerOn))
					&& !(el(ecsStartState).equals(elDatumed) || az(ecsStartState).equals(elDrivePowerOn))) {
				String errorMessage = "Ecs Assembly state of " + az(ecsStartState) + "/" + el(ecsStartState)
						+ " does not allow move";
				log.debug("Inside EcsMoveCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				ActorRef mySender = sender();
				log.debug("Inside EcsMoveCommand: Move command -- START: " + t);

				DoubleItem azItem = jitem(sc, EcsConfig.azDemandKey);
				DoubleItem elItem = jitem(sc, EcsConfig.elDemandKey);
				DoubleItem timeItem = jitem(sc, EcsConfig.timeDemandKey);

				Double az = jvalue(azItem);
				Double el = jvalue(elItem);
				Double time = jvalue(timeItem);

				log.debug("Inside EcsMoveCommand: Move command: sc is: " + sc + ": az is: " + az + ": el is: " + el
						+ ": time is: " + time);

				DemandMatcher stateMatcher = EcsCommandHandler.posMatcher(az, el, time);
				SetupConfig scOut = jadd(sc(EcsConfig.followPrefix), jset(EcsConfig.az, az), jset(EcsConfig.el, el),
						jset(EcsConfig.time, time));

				sendState(ecsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)));

				ecsHcd.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				EcsCommandHandler.executeMatch(context(), stateMatcher, ecsHcd, Optional.of(mySender), timeout,
						status -> {
							if (status == Completed) {
								log.debug("Inside EcsMoveCommand: Move Command Completed");
								sendState(ecsStateActor, new AssemblySetState(azItem(azPointing), elItem(elPointing)));
							} else if (status instanceof Error) {
								log.error("Inside EcsMoveCommand: Move command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside EcsMoveCommand: Move command -- STOP: " + t);
			ecsHcd.tell(new HcdController.Submit(jadd(sc("tcs.ecs.stop"))), self());
		}).matchAny(t -> log.warning("Inside EcsMoveCommand: Unknown message received: " + t)).build();
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef ecsHcd, AssemblyState ecsState,
			Optional<ActorRef> stateActor) {
		return Props.create(new Creator<EcsFollowCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsFollowCommand create() throws Exception {
				return new EcsFollowCommand(ac, sc, ecsHcd, ecsState, stateActor);
			}
		});
	}
}
