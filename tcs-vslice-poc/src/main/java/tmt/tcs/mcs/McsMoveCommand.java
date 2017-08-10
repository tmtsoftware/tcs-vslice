package tmt.tcs.mcs;

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
import javacsw.services.events.IEventService;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.common.BaseCommand;

/*
 * This is an actor class which receives command specific to Move Operation
 * And after any modifications if required, redirect the same to MCS HCD
 */
@SuppressWarnings("unused")
public class McsMoveCommand extends BaseCommand {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final AssemblyContext ac;
	private final Optional<ActorRef> mcsStateActor;
	private final Optional<ActorRef> eventPublisher;
	private final IEventService eventService;

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
	 * @param eventPublisher
	 * @param eventService
	 */
	public McsMoveCommand(AssemblyContext ac, SetupConfig sc, ActorRef mcsHcd, AssemblyState mcsStartState,
			Optional<ActorRef> stateActor, Optional<ActorRef> eventPublisher, IEventService eventService) {

		this.ac = ac;
		this.mcsStateActor = stateActor;
		this.eventPublisher = eventPublisher;
		this.eventService = eventService;

		ActorRef initialEventSubscriber = createEventSubscriber(self(), eventService);

		receive(processCommand(sc, mcsHcd, mcsStartState));
	}

	@Override
	public PartialFunction<Object, BoxedUnit> processCommand(SetupConfig sc, ActorRef mcsHcd,
			AssemblyState mcsStartState) {
		return ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			if (!(az(mcsStartState).equals(azDatumed) || az(mcsStartState).equals(azDrivePowerOn))
					&& !(el(mcsStartState).equals(elDatumed) || az(mcsStartState).equals(elDrivePowerOn))) {
				String errorMessage = "Mcs Assembly state of " + az(mcsStartState) + "/" + el(mcsStartState)
						+ " does not allow move";
				log.debug("Inside McsMoveCommand: Error Message is: " + errorMessage);
				sender().tell(new NoLongerValid(new WrongInternalStateIssue(errorMessage)), self());
			} else {
				log.debug("Inside McsMoveCommand: Move command -- START: " + t);

				DoubleItem azItem = jitem(sc, McsConfig.azDemandKey);
				DoubleItem elItem = jitem(sc, McsConfig.elDemandKey);
				DoubleItem timeItem = jitem(sc, McsConfig.timeDemandKey);

				Double az = jvalue(azItem);
				Double el = jvalue(elItem);
				Double time = jvalue(timeItem);

				log.debug("Inside McsMoveCommand: Move command: sc is: " + sc + ": az is: " + az + ": el is: " + el
						+ ": time is: " + time);

				DemandMatcher stateMatcher = McsCommandHandler.posMatcher(az, el, time);
				SetupConfig scOut = jadd(sc(McsConfig.movePrefix), jset(McsConfig.az, az), jset(McsConfig.el, el),
						jset(McsConfig.time, time));

				sendState(mcsStateActor, new AssemblySetState(azItem(azFollowing), elItem(elFollowing)));

				mcsHcd.tell(new HcdController.Submit(scOut), self());

				Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
				McsCommandHandler.executeMatch(context(), stateMatcher, mcsHcd, Optional.of(sender()), timeout,
						status -> {
							log.debug("Inside McsMoveCommand: Move Command status is: " + status);
							if (status == Completed) {
								log.debug("Inside McsMoveCommand: Move Command Completed");
								sendState(mcsStateActor, new AssemblySetState(azItem(azPointing), elItem(elPointing)));
							} else if (status instanceof Error) {
								log.error("Inside McsMoveCommand: Move command match failed with message: "
										+ ((Error) status).message());
							}
						});
			}
		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside McsMoveCommand: Move command -- STOP: " + t);
			mcsHcd.tell(new HcdController.Submit(jadd(sc("tcs.mcs.stop"))), self());
		}).matchAny(t -> log.debug("Inside McsMoveCommand: Unknown message received: " + t)).build();
	}

	private ActorRef createEventSubscriber(ActorRef followActor, IEventService eventService) {
		return context().actorOf(McsEventSubscriber.props(ac, Optional.of(followActor), eventService),
				"eventsubscriber");
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef mcsHcd, AssemblyState mcsState,
			Optional<ActorRef> stateActor, Optional<ActorRef> eventPublisher, IEventService eventService) {
		return Props.create(new Creator<McsMoveCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsMoveCommand create() throws Exception {
				return new McsMoveCommand(ac, sc, mcsHcd, mcsState, stateActor, eventPublisher, eventService);
			}
		});
	}
}
