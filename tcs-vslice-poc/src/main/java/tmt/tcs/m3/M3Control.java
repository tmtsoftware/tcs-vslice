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

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((rotation == null) ? 0 : rotation.hashCode());
			result = prime * result + ((tilt == null) ? 0 : tilt.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			GoToPosition other = (GoToPosition) obj;
			if (rotation == null) {
				if (other.rotation != null)
					return false;
			} else if (!rotation.equals(other.rotation))
				return false;
			if (tilt == null) {
				if (other.tilt != null)
					return false;
			} else if (!tilt.equals(other.tilt))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "GoToPosition [rotation=" + rotation + ", tilt=" + tilt + "]";
		}

	}
}
