package tmt.tcs.m3.hcd;

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
import tmt.tcs.m3.M3Config.M3State;

public class M3Simulator extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> replyTo;
	private M3State currentState = M3State.M3_IDLE;
	private Double currentRotation;
	private Double currentTilt;

	private M3Simulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;
		this.currentRotation = 0.0;
		this.currentTilt = 0.0;

		receive(idleReceive());
	}

	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			// Setting currentState to Moving while performing move operation
			currentState = M3State.M3_MOVING;
			log.debug("Inside M3Simulator idleReceive Move received: rotation is: " + e.rotation + ": tilt is: "
					+ e.tilt);

			// TODO:: Move Operation to be performed here

			currentRotation = e.rotation;
			currentTilt = e.tilt;
			currentState = M3State.M3_IDLE;

			update(replyTo, getState());
		}).matchAny(x -> log.warning("Inside M3Simulator Unexpected message in idleReceive: " + x)).build();
	}

	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside M3Simulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
	}

	M3PosUpdate getState() {
		return new M3PosUpdate(currentState, currentRotation, currentTilt);
	}

	public static Props props(final Optional<ActorRef> replyTo) {
		return Props.create(new Creator<M3Simulator>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3Simulator create() throws Exception {
				return new M3Simulator(replyTo);
			}
		});
	}

	public static class M3PosUpdate {
		public final M3State state;
		public final Double rotationPosition;
		public final Double tiltPosition;

		public M3PosUpdate(M3State state, Double rotationPosition, Double tiltPosition) {
			this.state = state;
			this.rotationPosition = rotationPosition;
			this.tiltPosition = tiltPosition;
		}

		@Override
		public String toString() {
			return "M3PosUpdate [state=" + state + ", rotationPosition=" + rotationPosition + ", tiltPosition="
					+ tiltPosition + "]";
		}
	}

	public static class Move {
		double rotation;
		double tilt;

		public Move(double rotation, double tilt) {
			this.rotation = rotation;
			this.tilt = tilt;
		}

	}
}
