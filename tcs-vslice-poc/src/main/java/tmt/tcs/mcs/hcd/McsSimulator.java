package tmt.tcs.mcs.hcd;

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
import tmt.tcs.mcs.McsConfig.McsState;

/**
 * This class simulates MCS move operation and send back current position to HCD
 * for publishing
 */
public class McsSimulator extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> replyTo;
	private McsState currentState = McsState.MCS_IDLE;
	private Double currentAz;
	private Double currentEl;

	private McsSimulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;
		this.currentAz = 0.0;
		this.currentEl = 0.0;

		receive(idleReceive());
	}

	/**
	 * This helps in receiving messages being send from HCD to Simulator
	 * 
	 * @return
	 */
	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			// Setting currentState to Moving while performing move operation
			currentState = McsState.MCS_MOVING;
			log.debug("Inside McsSimulator idleReceive Move received: az is: " + e.az + ": el is: " + e.el);

			// TODO:: Move Operation to be performed here

			currentAz = e.az;
			currentEl = e.el;
			currentState = McsState.MCS_IDLE;

			update(replyTo, getState());
		}).matchAny(x -> log.warning("Inside McsSimulator Unexpected message in idleReceive: " + x)).build();
	}

	/**
	 * This helps in sending Current position to HCD for publishing
	 * 
	 * @param replyTo
	 * @param msg
	 */
	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside McsSimulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
	}

	/**
	 * This helps in generating Current Position into specific format
	 * 
	 * @return
	 */
	McsPosUpdate getState() {
		return new McsPosUpdate(currentState, currentAz, currentEl);
	}

	public static Props props(final Optional<ActorRef> replyTo) {
		return Props.create(new Creator<McsSimulator>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsSimulator create() throws Exception {
				return new McsSimulator(replyTo);
			}
		});
	}

	public static class McsPosUpdate {
		public McsState state;
		public Double azPosition;
		public Double elPosition;

		public McsPosUpdate(McsState state, Double azPosition, Double elPosition) {
			this.state = state;
			this.azPosition = azPosition;
			this.elPosition = elPosition;
		}

		@Override
		public String toString() {
			return "McsPosUpdate [state=" + state + ", azPosition=" + azPosition + ", elPosition=" + elPosition + "]";
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
