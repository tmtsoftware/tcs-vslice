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

	private M3Simulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;

		receive(idleReceive());
	}

	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			log.debug(
					"Inside M3Simulator idleReceive Move received: rotation is: " + e.rotation + ": el is: " + e.tilt);
			update(replyTo, new M3Update(M3State.M3_IDLE));
		}).matchAny(x -> log.warning("Inside M3Simulator Unexpected message in idleReceive: " + x)).build();
	}

	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside M3Simulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
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

	public static class M3Update {
		public final M3State state;

		public M3Update(M3State state) {
			this.state = state;
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
