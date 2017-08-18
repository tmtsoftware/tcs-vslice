package tmt.tcs;

import static javacsw.util.config.JItems.jitem;
import static javacsw.util.config.JItems.jvalue;

import java.util.Optional;

import akka.actor.AbstractActor;
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
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;

/*
 * This is an actor class which receives command specific to Position Operation
 * And after any modifications if required, redirect the same to TPK
 * This also issue follow command to MCS, ECS and M3 Assemblies
 */
public class TcsFollowCommand extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final Optional<ActorRef> tcsStateActor;

	/**
	 * Constructor Methods helps subscribing to events checks for Assembly state
	 * before enabling command execution creats hcd specific setupconfig and
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
	public TcsFollowCommand(AssemblyContext ac, SetupConfig sc, ActorRef mcsRefActor, ActorRef ecsRefActor,
			ActorRef m3RefActor, AssemblyState tcsStartState, Optional<ActorRef> stateActor) {
		this.tcsStateActor = stateActor;

		receive(ReceiveBuilder.matchEquals(JSequentialExecutor.CommandStart(), t -> {
			log.debug("Inside TcsFollowCommand: Follow command -- START: " + t + ": Config Key is: " + sc.configKey());

			SetupConfigArg mcsSetupConfigArg = Configurations.createSetupConfigArg("mcsFollowCommand",
					new SetupConfig(McsConfig.initPrefix), new SetupConfig(McsConfig.followPrefix));

			SetupConfigArg ecsSetupConfigArg = Configurations.createSetupConfigArg("ecsFollowCommand",
					new SetupConfig(EcsConfig.initPrefix), new SetupConfig(EcsConfig.followPrefix));

			SetupConfigArg m3SetupConfigArg = Configurations.createSetupConfigArg("m3FollowCommand",
					new SetupConfig(M3Config.initPrefix), new SetupConfig(M3Config.followPrefix));

			log.debug("Inside TcsFollowCommand: Follow command -- mcsRefActor is: " + mcsRefActor);

			mcsRefActor.tell(new AssemblyController.Submit(mcsSetupConfigArg), self());

			ecsRefActor.tell(new AssemblyController.Submit(ecsSetupConfigArg), self());

			m3RefActor.tell(new AssemblyController.Submit(m3SetupConfigArg), self());

			// TODO:: Code for calling TPK to be done here
			// Forward below parameters to TPK
			String target = jvalue(jitem(sc, TcsConfig.target));
			Double ra = jvalue(jitem(sc, TcsConfig.ra));
			Double dec = jvalue(jitem(sc, TcsConfig.dec));
			String frame = jvalue(jitem(sc, TcsConfig.frame));

			log.debug("Inside TcsFollowCommand: Follow command: target is: " + target + ": ra is: " + ra + ": dec is: "
					+ dec + ": frame is: " + frame);

		}).matchEquals(JSequentialExecutor.StopCurrentCommand(), t -> {
			log.debug("Inside TcsFollowCommand: Follow command -- STOP: " + t);
		}).matchAny(t -> log.warning("Inside TcsFollowCommand: Unknown message received: " + t)).build());
	}

	public static Props props(AssemblyContext ac, SetupConfig sc, ActorRef mcsRefActor, ActorRef ecsRefActor,
			ActorRef m3RefActor, AssemblyState tcsState, Optional<ActorRef> stateActor) {
		return Props.create(new Creator<TcsFollowCommand>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsFollowCommand create() throws Exception {
				return new TcsFollowCommand(ac, sc, mcsRefActor, ecsRefActor, m3RefActor, tcsState, stateActor);
			}
		});
	}
}