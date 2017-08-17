package tmt.tcs.ecs;

import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.ccs.HcdController.Submit;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.DoubleItem;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;

public class EcsControl extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	private EcsControl(AssemblyContext assemblyContext, Optional<ActorRef> ecsHcd) {
		this.assemblyContext = assemblyContext;
		log.info("Inside EcsControl: Hcd ref is: " + ecsHcd);

		// Initial receive - start with initial values
		receive(controlReceive(ecsHcd));
	}

	private PartialFunction<Object, BoxedUnit> controlReceive(Optional<ActorRef> ecsHcd) {
		return ReceiveBuilder.match(GoToPosition.class, t -> {
			log.info("Inside EcsControl controlReceive: Got GoToPosition");
			DoubleItem az = t.azimuth;
			DoubleItem el = t.elevation;

			SetupConfig scOut = jadd(sc(EcsConfig.followPrefix), az, el);

			// Send command to HCD here
			ecsHcd.ifPresent(actorRef -> actorRef.tell(new Submit(scOut), self()));
		}).match(EcsAssembly.UpdateHcd.class, t -> {
			log.info("Inside EcsControl controlReceive: Got UpdateHcd");
			context().become(controlReceive(t.hcdActorRef));
		}).matchAny(t -> log.warning("Inside EcsControl: controlReceive Unexpected message received : " + t)).build();
	}

	// Props for creating the EcsControl actor
	public static Props props(AssemblyContext ac, Optional<ActorRef> ecsHcd) {
		return Props.create(new Creator<EcsControl>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsControl create() throws Exception {
				return new EcsControl(ac, ecsHcd);
			}
		});
	}

	// Used to send a position that requires transformation from
	static class GoToPosition {
		final DoubleItem azimuth;
		final DoubleItem elevation;

		GoToPosition(DoubleItem azimuth, DoubleItem elevation) {
			this.azimuth = azimuth;
			this.elevation = elevation;
		}

	}
}
