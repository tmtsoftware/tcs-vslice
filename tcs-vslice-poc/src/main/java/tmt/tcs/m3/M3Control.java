package tmt.tcs.m3;

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

public class M3Control extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	private M3Control(AssemblyContext assemblyContext, Optional<ActorRef> m3Hcd) {
		this.assemblyContext = assemblyContext;
		log.info("Inside M3Control: Hcd ref is: " + m3Hcd);

		// Initial receive - start with initial values
		receive(controlReceive(m3Hcd));
	}

	private PartialFunction<Object, BoxedUnit> controlReceive(Optional<ActorRef> m3Hcd) {
		return ReceiveBuilder.match(GoToPosition.class, t -> {
			log.info("Inside M3Control controlReceive: Got GoToPosition");
			DoubleItem rotation = t.rotation;
			DoubleItem tilt = t.tilt;

			SetupConfig scOut = jadd(sc(M3Config.followPrefix), rotation, tilt);

			// Send command to HCD here
			m3Hcd.ifPresent(actorRef -> actorRef.tell(new Submit(scOut), self()));
		}).match(M3Assembly.UpdateHcd.class, t -> {
			log.info("Inside M3Control controlReceive: Got UpdateHcd");
			context().become(controlReceive(t.hcdActorRef));
		}).matchAny(t -> log.warning("Inside M3Control: controlReceive Unexpected message received : " + t)).build();
	}

	// Props for creating the M3Control actor
	public static Props props(AssemblyContext ac, Optional<ActorRef> m3Hcd) {
		return Props.create(new Creator<M3Control>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3Control create() throws Exception {
				return new M3Control(ac, m3Hcd);
			}
		});
	}

	// Used to send a position that requires transformation from
	static class GoToPosition {
		final DoubleItem rotation;
		final DoubleItem tilt;

		GoToPosition(DoubleItem rotation, DoubleItem tilt) {
			this.rotation = rotation;
			this.tilt = tilt;
		}

	}
}
