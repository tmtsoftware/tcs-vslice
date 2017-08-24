package tmt.tcs.mcs;

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

public class McsControl extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;

	private McsControl(AssemblyContext assemblyContext, Optional<ActorRef> mcsHcd) {
		this.assemblyContext = assemblyContext;
		log.info("Inside McsControl: Hcd ref is: " + mcsHcd);

		// Initial receive - start with initial values
		receive(controlReceive(mcsHcd));
	}

	private PartialFunction<Object, BoxedUnit> controlReceive(Optional<ActorRef> mcsHcd) {
		return ReceiveBuilder.match(GoToPosition.class, t -> {
			log.info("Inside McsControl controlReceive: Got GoToPosition");
			DoubleItem az = t.azimuth;
			DoubleItem el = t.elevation;

			SetupConfig scOut = jadd(sc(McsConfig.followPrefix), az, el);

			// Send command to HCD here
			mcsHcd.ifPresent(actorRef -> actorRef.tell(new Submit(scOut), self()));
		}).match(McsAssembly.UpdateHcd.class, t -> {
			log.info("Inside McsControl controlReceive: Got UpdateHcd");
			context().become(controlReceive(t.hcdActorRef));
		}).matchAny(t -> log.warning("Inside McsControl: controlReceive Unexpected message received : " + t)).build();
	}

	// Props for creating the McsControl actor
	public static Props props(AssemblyContext ac, Optional<ActorRef> mcsHcd) {
		return Props.create(new Creator<McsControl>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsControl create() throws Exception {
				return new McsControl(ac, mcsHcd);
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

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((azimuth == null) ? 0 : azimuth.hashCode());
			result = prime * result + ((elevation == null) ? 0 : elevation.hashCode());
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
			if (azimuth == null) {
				if (other.azimuth != null)
					return false;
			} else if (!azimuth.equals(other.azimuth))
				return false;
			if (elevation == null) {
				if (other.elevation != null)
					return false;
			} else if (!elevation.equals(other.elevation))
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "GoToPosition [azimuth=" + azimuth + ", elevation=" + elevation + "]";
		}

	}
}
