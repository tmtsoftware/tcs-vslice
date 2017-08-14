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

public class McsSimulator extends AbstractActor {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> replyTo;

	private McsSimulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;

		receive(idleReceive());
	}

	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			log.debug("Inside McsSimulator idleReceive Move received: az is: " + e.az + ": el is: " + e.el);
			update(replyTo, new McsUpdate(McsState.MCS_IDLE));
		}).matchAny(x -> log.warning("Inside McsSimulator Unexpected message in idleReceive: " + x)).build();
	}

	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside McsSimulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
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

	public static class McsUpdate {
		public final McsState state;

		public McsUpdate(McsState state) {
			this.state = state;
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