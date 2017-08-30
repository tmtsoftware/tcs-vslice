package tmt.tcs.mcs;

import static tmt.tcs.common.AssemblyStateActor.az;
import static tmt.tcs.common.AssemblyStateActor.azFollowing;
import static tmt.tcs.common.AssemblyStateActor.el;
import static tmt.tcs.common.AssemblyStateActor.elFollowing;

import java.time.Instant;
import java.util.Optional;

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
import tmt.tcs.common.AssemblyStateActor.AssemblyGetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.common.BaseFollowActor;
import tmt.tcs.mcs.McsEventPublisher.TelemetryUpdate;

public class McsFollowActor extends BaseFollowActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> mcsControl;
	private final Optional<ActorRef> eventPublisher;
	private final Optional<ActorRef> mcsStateActor;

	public final DoubleItem initialAzimuth;
	public final DoubleItem initialElevation;

	private McsFollowActor(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> mcsControl, Optional<ActorRef> eventPublisher, Optional<ActorRef> mcsStateActor) {
		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.mcsControl = mcsControl;
		this.eventPublisher = eventPublisher;
		this.mcsStateActor = mcsStateActor;

		subscribeToLocationUpdates();
		context().system().eventStream().subscribe(self(), AssemblyState.class);

		// Initial receive - start with initial values
		receive(followingReceive(initialAzimuth, initialElevation));
	}

	private PartialFunction<Object, BoxedUnit> followingReceive(DoubleItem initialAzimuth,
			DoubleItem initialElevation) {
		return stateReceive().orElse(ReceiveBuilder.match(StopFollowing.class, t -> {
			// do nothing
		}).match(UpdatedEventData.class, t -> {
			mcsStateActor.ifPresent(actorRef -> actorRef.tell(new AssemblyGetState(), self()));

			log.info("Inside McsFollowActor followingReceive: az state is: " + az(currentState()) + ": el state is: "
					+ el(currentState()));
			if (az(currentState()).equals(azFollowing) && el(currentState()).equals(elFollowing)) {
				log.info("Inside McsFollowActor followingReceive: Got an Update Event: " + t);

				sendMcsPosition(t.azimuth, t.elevation);

				// Post a StatusEvent for telemetry updates
				sendTelemetryUpdate(t.azimuth, t.elevation);

				context().become(followingReceive(t.azimuth, t.elevation));
			} else {
				String errorMessage = "Assembly State " + az(currentState()) + "/" + el(currentState())
						+ " does not allow moving Mcs";
				log.error("Inside McsFollowActor followingReceive: Error Message is: " + errorMessage);
			}
		}).match(SetElevation.class, t -> {
			log.info("Inside McsFollowActor followingReceive: Got elevation: " + t.elevation);

			self().tell(new UpdatedEventData(initialAzimuth, t.elevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(initialAzimuth, t.elevation));
		}).match(SetAzimuth.class, t -> {
			log.info("Inside McsFollowActor followingReceive: Got azimuth: " + t.azimuth);

			self().tell(new UpdatedEventData(t.azimuth, initialElevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(t.azimuth, initialElevation));
		}).matchAny(t -> log.warning("Inside McsFollowActor followingReceive: Unexpected message is: " + t)).build());
	}

	private void sendMcsPosition(DoubleItem az, DoubleItem el) {
		log.debug(
				"Inside McsFollowActor sendMcsPosition: to Actor: " + mcsControl + ": az is: " + az + ": el is: " + el);
		mcsControl.ifPresent(actorRef -> actorRef.tell(new McsControl.GoToPosition(az, el), self()));
	}

	private void sendTelemetryUpdate(DoubleItem az, DoubleItem el) {
		log.debug("Inside McsFollowActor sendTelemetryUpdate: " + eventPublisher + ": az is: " + az + ": el is: " + el);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new TelemetryUpdate(az, el), self()));
	}

	/**
	 * Props for creating the follow actor
	 * 
	 * @param assemblyContext
	 * @param initialAzimuth
	 * @param initialElivation
	 * @param mcsControl
	 * @param eventPublisher
	 * @return
	 */
	public static Props props(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElivation,
			Optional<ActorRef> mcsControl, Optional<ActorRef> eventPublisher, Optional<ActorRef> mcsStateActor) {
		return Props.create(new Creator<McsFollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsFollowActor create() throws Exception {
				return new McsFollowActor(assemblyContext, initialAzimuth, initialElivation, mcsControl, eventPublisher,
						mcsStateActor);
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
