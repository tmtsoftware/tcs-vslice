package tmt.tcs.ecs;

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
import tmt.tcs.ecs.EcsEventPublisher.TelemetryUpdate;

/**
 * This class receives ECS specific demand being forwarded by Event Subscriber
 * and send the same to EcsControl
 */
public class EcsFollowActor extends BaseFollowActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	@SuppressWarnings("unused")
	private final AssemblyContext assemblyContext;
	private final Optional<ActorRef> ecsControl;
	private final Optional<ActorRef> eventPublisher;
	private final Optional<ActorRef> ecsStateActor;

	public final DoubleItem initialAzimuth;
	public final DoubleItem initialElevation;

	private EcsFollowActor(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> ecsControl, Optional<ActorRef> eventPublisher, Optional<ActorRef> ecsStateActor) {
		log.info("Inside EcsFollowActor");

		this.assemblyContext = assemblyContext;
		this.initialAzimuth = initialAzimuth;
		this.initialElevation = initialElevation;
		this.ecsControl = ecsControl;
		this.eventPublisher = eventPublisher;
		this.ecsStateActor = ecsStateActor;

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
			ecsStateActor.ifPresent(actorRef -> actorRef.tell(new AssemblyGetState(), self()));

			log.info("Inside EcsFollowActor followingReceive: az state is: " + az(currentState()) + ": el state is: "
					+ el(currentState()));

			if (az(currentState()).equals(azFollowing) && el(currentState()).equals(elFollowing)) {
				log.info("Inside EcsFollowActor followingReceive: Got an Update Event: " + t);

				sendEcsPosition(t.azimuth, t.elevation);

				// Post a StatusEvent for telemetry updates
				sendTelemetryUpdate(t.azimuth, t.elevation);

				context().become(followingReceive(t.azimuth, t.elevation));
			} else {
				String errorMessage = "Assembly State " + az(currentState()) + "/" + el(currentState())
						+ " does not allow moving Ecs";
				log.error("Inside EcsFollowActor followingReceive: Error Message is: " + errorMessage);
			}
		}).match(SetElevation.class, t -> {
			log.info("Inside EcsFollowActor followingReceive: Got elevation: " + t.elevation);

			self().tell(new UpdatedEventData(initialAzimuth, t.elevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(initialAzimuth, t.elevation));
		}).match(SetAzimuth.class, t -> {
			log.info("Inside EcsFollowActor followingReceive: Got azimuth: " + t.azimuth);

			self().tell(new UpdatedEventData(t.azimuth, initialElevation, new EventTime(Instant.now())), self());
			context().become(followingReceive(t.azimuth, initialElevation));
		}).matchAny(t -> log.warning("Inside EcsFollowActor followingReceive: Unexpected message is: " + t)).build());
	}

	private void sendEcsPosition(DoubleItem az, DoubleItem el) {
		log.debug("Inside EcsFollowActor sendEcsPosition: az is: " + az + ": el is: " + el);
		ecsControl.ifPresent(actorRef -> actorRef.tell(new EcsControl.GoToPosition(az, el), self()));
	}

	private void sendTelemetryUpdate(DoubleItem az, DoubleItem el) {
		log.debug("Inside EcsFollowActor sendTelemetryUpdate publish Telemetry Update: " + eventPublisher);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new TelemetryUpdate(az, el), self()));
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
	public static Props props(AssemblyContext assemblyContext, DoubleItem initialAzimuth, DoubleItem initialElevation,
			Optional<ActorRef> ecsControl, Optional<ActorRef> eventPublisher, Optional<ActorRef> ecsStateActor) {
		return Props.create(new Creator<EcsFollowActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsFollowActor create() throws Exception {
				return new EcsFollowActor(assemblyContext, initialAzimuth, initialElevation, ecsControl, eventPublisher,
						ecsStateActor);
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
