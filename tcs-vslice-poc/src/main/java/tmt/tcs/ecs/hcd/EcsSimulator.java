package tmt.tcs.ecs.hcd;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.ecs.EcsConfig.EcsState;

public class EcsSimulator extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> replyTo;
	private EcsState currentState = EcsState.ECS_IDLE;
	private Double currentAz;
	private Double currentEl;

	private EcsSimulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;
		this.currentAz = 0.0;
		this.currentEl = 0.0;

		receive(idleReceive());
	}

	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			// Setting currentState to Moving while performing move operation
			currentState = EcsState.ECS_MOVING;
			log.debug("Inside EcsSimulator idleReceive Move received: az is: " + e.az + ": el is: " + e.el);

			// TODO:: Move Operation to be performed here

			currentAz = e.az;
			currentEl = e.el;
			currentState = EcsState.ECS_IDLE;

			update(replyTo, getState());
		}).matchAny(x -> log.warning("Inside EcsSimulator Unexpected message in idleReceive: " + x)).build();
	}

	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside EcsSimulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
	}

	EcsPosUpdate getState() {
		return new EcsPosUpdate(currentState, currentAz, currentEl);
	}

	public static Props props(final Optional<ActorRef> replyTo) {
		return Props.create(new Creator<EcsSimulator>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsSimulator create() throws Exception {
				return new EcsSimulator(replyTo);
			}
		});
	}

	public static class EcsPosUpdate {
		public EcsState state;
		public Double azPosition;
		public Double elPosition;

		public EcsPosUpdate(EcsState state, Double azPosition, Double elPosition) {
			this.state = state;
			this.azPosition = azPosition;
			this.elPosition = elPosition;
		}

		@Override
		public String toString() {
			return "EcsPosUpdate [state=" + state + ", azPosition=" + azPosition + ", elPosition=" + elPosition + "]";
		}
	}

	public static class Move {
		double az;
		double el;

		public Move(double az, double el) {
			this.az = az;
			this.el = el;
		}

	}
}
