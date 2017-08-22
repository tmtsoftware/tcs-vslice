package tmt.tcs.ecs;

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
import tmt.tcs.ecs.EcsEventPublisher.EngrUpdate;
import tmt.tcs.ecs.EcsEventPublisher.SystemUpdate;

public class EcsFollowActor extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> ecsControl;
	private final Optional<ActorRef> eventPublisher;

	public final DoubleItem initialAzimuth;
	public final DoubleItem initialElevation;

	private EcsFollowActor(AssemblyContext assemblyContext, DoubleItem initialElevation, DoubleItem initialAzimuth,
			Optional<ActorRef> ecsControl, Optional<ActorRef> eventPublisher) {
		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.ecsControl = ecsControl;
		this.eventPublisher = eventPublisher;

		// Initial receive - start with initial values
		receive(followingReceive(initialElevation, initialAzimuth));
	}

	private PartialFunction<Object, BoxedUnit> followingReceive(DoubleItem initialAzimuth, DoubleItem initialElevation) {
		return ReceiveBuilder.match(StopFollowing.class, t -> {
			// do nothing
		}).match(UpdatedEventData.class, t -> {
			log.info("Inside EcsFollowActor followingReceive: Got an Update Event: " + t);

			sendEcsPosition(t.azimuth, t.elevation);

			// Post a StatusEvent for telemetry updates
			sendEngrUpdate(t.azimuth, t.elevation);

			// Post a SystemEvent for System updates
			sendSystemUpdate(t.azimuth, t.elevation);

			context().become(followingReceive(t.azimuth, t.elevation));
		}).match(SetElevation.class, t -> {
			log.info("Inside EcsFollowActor followingReceive: Got elevation: " + t.elevation);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(initialAzimuth, t.elevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(initialAzimuth, t.elevation));
		}).match(SetAzimuth.class, t -> {
			log.info("Inside EcsFollowActor followingReceive: Got azimuth: " + t.azimuth);
			// No need to call followReceive again since we are using the
			// UpdateEventData message
			self().tell(new UpdatedEventData(t.azimuth, initialElevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(t.azimuth, initialElevation));
		}).matchAny(t -> log.warning("Inside EcsFollowActor followingReceive: Unexpected message is: " + t)).build();
	}

	private void sendEcsPosition(DoubleItem az, DoubleItem el) {
		log.debug("Inside EcsFollowActor sendEcsPosition: az is: " + az + ": el is: " + el);
		ecsControl.ifPresent(actorRef -> actorRef.tell(new EcsControl.GoToPosition(az, el), self()));
	}

	private void sendEngrUpdate(DoubleItem az, DoubleItem el) {
		log.debug("Inside EcsFollowActor sendEngrUpdate publish engUpdate: " + eventPublisher);
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
	 * @param ecsControl
	 * @param eventPublisher
	 * @return
	 */
	public static Props props(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElivation,
			Optional<ActorRef> ecsControl, Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<EcsFollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsFollowActor create() throws Exception {
				return new EcsFollowActor(assemblyContext, initialAzimuth, initialElivation, ecsControl, 
						eventPublisher);
			}
		});
	}

	/**
	 * Messages received by EcsFollowActor Update from subscribers
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
