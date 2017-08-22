package tmt.tcs.mcs;

import java.time.Instant;
import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.util.config.DoubleItem;
import csw.util.config.Events.EventTime;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.mcs.McsEventPublisher.EngrUpdate;
import tmt.tcs.mcs.McsEventPublisher.SystemUpdate;

public class McsFollowActor extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> mcsControl;
	private final Optional<ActorRef> eventPublisher;

	public final DoubleItem initialAzimuth;
	public final DoubleItem initialElevation;

	private McsFollowActor(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsControl, Optional<ActorRef> eventPublisher) {
		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.mcsControl = mcsControl;
		this.eventPublisher = eventPublisher;

		// Initial receive - start with initial values
		receive(followingReceive(initialAzimuth, initialElevation));
	}

	private PartialFunction<Object, BoxedUnit> followingReceive(DoubleItem initialAzimuth, DoubleItem initialElevation) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			// do nothing
		}).match(UpdatedEventData.class, t -> {
			log.info("Inside McsFollowActor followingReceive: Got an Update Event: " + t);

			sendMcsPosition(t.azimuth, t.elevation);

			// Post a StatusEvent for telemetry updates
			sendEngrUpdate(t.azimuth, t.elevation);

			// Post a SystemEvent for System updates
			sendSystemUpdate(t.azimuth, t.elevation);

			context().become(followingReceive(t.azimuth, t.elevation));
		}).match(SetElevation.class, t -> {
			log.info("Inside McsFollowActor followingReceive: Got elevation: " + t.elevation);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(initialAzimuth, t.elevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(initialAzimuth, t.elevation));
		}).match(SetAzimuth.class, t -> {
			log.info("Inside McsFollowActor followingReceive: Got azimuth: " + t.azimuth);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(t.azimuth, initialElevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(t.azimuth, initialElevation));
		}).matchAny(t -> log.warning("Inside McsFollowActor followingReceive: Unexpected message is: " + t)).build();
	}

	private void sendMcsPosition(DoubleItem az, DoubleItem el) {
		log.debug(
				"Inside McsFollowActor sendMcsPosition: to Actor: " + mcsControl + ": az is: " + az + ": el is: " + el);
		mcsControl.ifPresent(actorRef -> actorRef.tell(new McsControl.GoToPosition(az, el), self()));
	}

	private void sendEngrUpdate(DoubleItem az, DoubleItem el) {
		log.debug("Inside McsFollowActor sendEngrUpdate publish engUpdate: " + eventPublisher + ": az is: " + az
				+ ": el is: " + el);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new EngrUpdate(az, el), self()));
	}

	private void sendSystemUpdate(DoubleItem az, DoubleItem el) {
		log.debug("Inside McsFollowActor sendSystemUpdate publish systemUpdate: " + eventPublisher);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new SystemUpdate(az, el), self()));
	}

	/**
	 * Props for creating the follow actor
	 * 
	 * @param assemblyContext
	 * @param initialAzimuth
	 * @param initialElivation
	 * @param mcsControl
	 * @param aoPublisher
	 * @param engPublisher
	 * @return
	 */
	public static Props props(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElivation,
			Optional<ActorRef> mcsControl, Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<McsFollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsFollowActor create() throws Exception {
				return new McsFollowActor(assemblyContext, initialAzimuth, initialElivation, mcsControl,
						eventPublisher);
			}
		});
	}

	/**
	 * Messages received by McsFollowActor Update from subscribers
	 */
	interface FollowActorMessages {
	}

	public static class UpdatedEventData implements FollowActorMessages {
		public final DoubleItem azimuth;
		public final DoubleItem elevation;
		public final EventTime time;

		public UpdatedEventData(DoubleItem azimuth, DoubleItem elevation, EventTime time) {
			this.azimuth = azimuth;
			this.elevation = elevation;
			this.time = time;
		}

		@Override
		public String toString() {
			return "UpdatedEventData [azimuth=" + azimuth + ", elevation=" + elevation + ", time=" + time + "]";
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((azimuth == null) ? 0 : azimuth.hashCode());
			result = prime * result + ((elevation == null) ? 0 : elevation.hashCode());
			result = prime * result + ((time == null) ? 0 : time.hashCode());
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
			UpdatedEventData other = (UpdatedEventData) obj;
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
			if (time == null) {
				if (other.time != null)
					return false;
			} else if (!time.equals(other.time))
				return false;
			return true;
		}

	}

	// Messages to Follow Actor
	public static class SetElevation implements FollowActorMessages {
		public final DoubleItem elevation;

		public SetElevation(DoubleItem elevation) {
			this.elevation = elevation;
		}
	}

	public static class SetAzimuth implements FollowActorMessages {
		public final DoubleItem azimuth;

		public SetAzimuth(DoubleItem azimuth) {
			this.azimuth = azimuth;
		}
	}

	public static class StopFollowing implements FollowActorMessages {
	}

}
