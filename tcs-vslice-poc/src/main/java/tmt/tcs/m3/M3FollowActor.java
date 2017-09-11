package tmt.tcs.m3;

import static tmt.tcs.common.AssemblyStateActor.rotation;
import static tmt.tcs.common.AssemblyStateActor.rotationFollowing;
import static tmt.tcs.common.AssemblyStateActor.tilt;
import static tmt.tcs.common.AssemblyStateActor.tiltFollowing;

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
import tmt.tcs.m3.M3EventPublisher.TelemetryUpdate;

/**
 * This class receives M3 specific demand being forwarded by Event Subscriber
 * and send the same to M3Control
 */
public class M3FollowActor extends BaseFollowActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> m3Control;
	private final Optional<ActorRef> eventPublisher;
	private final Optional<ActorRef> m3StateActor;

	public final DoubleItem initialRotation;
	public final DoubleItem initialTilt;

	private M3FollowActor(AssemblyContext assemblyContext, DoubleItem initialRotation, DoubleItem initialTilt,
			Optional<ActorRef> m3Control, Optional<ActorRef> eventPublisher, Optional<ActorRef> m3StateActor) {
		log.info("Inside M3FollowActor");

		this.assemblyContext = assemblyContext;
		this.initialRotation = initialRotation;
		this.initialTilt = initialTilt;
		this.m3Control = m3Control;
		this.eventPublisher = eventPublisher;
		this.m3StateActor = m3StateActor;

		subscribeToLocationUpdates();
		context().system().eventStream().subscribe(self(), AssemblyState.class);

		// Initial receive - start with initial values
		receive(followingReceive(initialRotation, initialTilt));
	}

	private PartialFunction<Object, BoxedUnit> followingReceive(DoubleItem initialRotation, DoubleItem initialTilt) {
		return stateReceive().orElse(ReceiveBuilder.match(StopFollowing.class, t -> {
			// do nothing
		}).match(UpdatedEventData.class, t -> {
			m3StateActor.ifPresent(actorRef -> actorRef.tell(new AssemblyGetState(), self()));

			log.info("Inside M3FollowActor followingReceive: Rotation state is: " + rotation(currentState())
					+ ": Tilt state is: " + tilt(currentState()));

			if (rotation(currentState()).equals(rotationFollowing) && tilt(currentState()).equals(tiltFollowing)) {
				log.info("Inside M3FollowActor followingReceive: Got an Update Event: " + t);

				sendM3Position(t.rotation, t.tilt);

				// Post a StatusEvent for telemetry updates
				sendTelemetryUpdate(t.rotation, t.tilt);

				context().become(followingReceive(t.rotation, t.tilt));
			} else {
				String errorMessage = "Assembly State " + rotation(currentState()) + "/" + tilt(currentState())
						+ " does not allow moving M3";
				log.error("Inside M3FollowActor followingReceive: Error Message is: " + errorMessage);
			}
		}).match(SetRotation.class, t -> {
			log.info("Inside M3FollowActor followingReceive: Got Rotation: " + t.rotation);

			self().tell(new UpdatedEventData(t.rotation, initialTilt, new EventTime(Instant.now())), self());
			context().become(followingReceive(t.rotation, initialTilt));
		}).match(SetTilt.class, t -> {
			log.info("Inside M3FollowActor followingReceive: Got Tilt: " + t.tilt);

			self().tell(new UpdatedEventData(initialRotation, t.tilt, new EventTime(Instant.now())), self());
			context().become(followingReceive(initialRotation, t.tilt));
		}).matchAny(t -> log.warning("Inside M3FollowActor followingReceive: Unexpected message is: " + t)).build());
	}

	private void sendM3Position(DoubleItem rotation, DoubleItem tilt) {
		log.debug("Inside M3FollowActor sendM3Position: rotation is: " + rotation + ": tilt is: " + tilt);
		m3Control.ifPresent(actorRef -> actorRef.tell(new M3Control.GoToPosition(rotation, tilt), self()));
	}

	private void sendTelemetryUpdate(DoubleItem rotation, DoubleItem tilt) {
		log.debug("Inside M3FollowActor sendTelemetryUpdate: " + eventPublisher);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new TelemetryUpdate(rotation, tilt), self()));
	}

	/**
	 * Props for creating the follow actor
	 * 
	 * @param assemblyContext
	 * @param initialRotation
	 * @param initialTilt
	 * @param m3Control
	 * @param eventPublisher
	 * @return
	 */
	public static Props props(AssemblyContext assemblyContext, DoubleItem initialRotation, DoubleItem initialTilt,
			Optional<ActorRef> m3Control, Optional<ActorRef> eventPublisher, Optional<ActorRef> m3StateActor) {
		return Props.create(new Creator<M3FollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3FollowActor create() throws Exception {
				return new M3FollowActor(assemblyContext, initialRotation, initialTilt, m3Control, eventPublisher,
						m3StateActor);
			}
		});
	}

	/**
	 * Messages received by M3FollowActor Update from subscribers
	 */
	interface FollowActorMessages {
	}

	public static class UpdatedEventData implements FollowActorMessages {
		public final DoubleItem rotation;
		public final DoubleItem tilt;
		public final EventTime time;

		public UpdatedEventData(DoubleItem rotation, DoubleItem tilt, EventTime time) {
			this.rotation = rotation;
			this.tilt = tilt;
			this.time = time;
		}
	}

	// Messages to Follow Actor
	public static class SetRotation implements FollowActorMessages {
		public final DoubleItem rotation;

		public SetRotation(DoubleItem rotation) {
			this.rotation = rotation;
		}
	}

	public static class SetTilt implements FollowActorMessages {
		public final DoubleItem tilt;

		public SetTilt(DoubleItem tilt) {
			this.tilt = tilt;
		}
	}

	public static class StopFollowing implements FollowActorMessages {
	}

}
