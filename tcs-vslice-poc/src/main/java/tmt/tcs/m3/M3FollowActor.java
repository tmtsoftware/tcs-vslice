package tmt.tcs.m3;

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
import tmt.tcs.m3.M3EventPublisher.EngrUpdate;
import tmt.tcs.m3.M3EventPublisher.SystemUpdate;

public class M3FollowActor extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> m3Control;
	private final Optional<ActorRef> eventPublisher;

	public final DoubleItem initialRotation;
	public final DoubleItem initialTilt;

	private M3FollowActor(AssemblyContext assemblyContext, DoubleItem initialRotation, DoubleItem initialTilt,
			Optional<ActorRef> m3Control, Optional<ActorRef> eventPublisher) {
		this.assemblyContext = assemblyContext;
		this.initialRotation = initialTilt;
		this.initialTilt = initialRotation;
		this.m3Control = m3Control;
		this.eventPublisher = eventPublisher;

		// Initial receive - start with initial values
		receive(followingReceive(initialRotation, initialTilt));
	}

	private PartialFunction<Object, BoxedUnit> followingReceive(DoubleItem rotation, DoubleItem tilt) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			// do nothing
		}).match(UpdatedEventData.class, t -> {
			log.info("Inside M3FollowActor followingReceive: Got an Update Event: " + t);

			sendEcsPosition(t.rotation, t.tilt);

			// Post a StatusEvent for telemetry updates
			sendEngrUpdate(t.rotation, t.tilt);

			// Post a SystemEvent for System updates
			sendSystemUpdate(t.rotation, t.tilt);

			context().become(followingReceive(t.rotation, t.tilt));
		}).match(SetRotation.class, t -> {
			log.info("Inside M3FollowActor followingReceive: Got Rotation: " + t.rotation);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(initialRotation, t.rotation, new EventTime(Instant.now())), self());
		}).match(SetTilt.class, t -> {
			log.info("Inside M3FollowActor followingReceive: Got Tilt: " + t.tilt);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(t.tilt, initialTilt, new EventTime(Instant.now())), self());
		}).matchAny(t -> log.warning("Inside M3FollowActor followingReceive: Unexpected message is: " + t)).build();
	}

	private void sendEcsPosition(DoubleItem rotation, DoubleItem tilt) {
		log.debug("Inside M3FollowActor sendEcsPosition: rotation is: " + rotation + ": tilt is: " + tilt);
		m3Control.ifPresent(actorRef -> actorRef.tell(new M3Control.GoToPosition(rotation, tilt), self()));
	}

	private void sendEngrUpdate(DoubleItem rotation, DoubleItem tilt) {
		log.debug("Inside M3FollowActor sendEngrUpdate publish engUpdate: " + eventPublisher);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new EngrUpdate(rotation, tilt), self()));
	}

	private void sendSystemUpdate(DoubleItem rotation, DoubleItem tilt) {
		log.debug("Inside M3FollowActor sendSystemUpdate publish systemUpdate: " + eventPublisher);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new SystemUpdate(rotation, tilt), self()));
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
			Optional<ActorRef> m3Control, Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<M3FollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3FollowActor create() throws Exception {
				return new M3FollowActor(assemblyContext, initialRotation, initialTilt, m3Control, eventPublisher);
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
