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

	private EcsSimulator(Optional<ActorRef> replyTo) {
		this.replyTo = replyTo;

		receive(idleReceive());
	}

	PartialFunction<Object, BoxedUnit> idleReceive() {
		return ReceiveBuilder.match(Move.class, e -> {
			log.debug("Inside EcsSimulator idleReceive Move received: az is: " + e.az + ": el is: " + e.el);
			update(replyTo, new EcsUpdate(EcsState.ECS_IDLE));
		}).matchAny(x -> log.warning("Inside EcsSimulator Unexpected message in idleReceive: " + x)).build();
	}

	void update(Optional<ActorRef> replyTo, Object msg) {
		log.debug("Inside EcsSimulator update: msg is: " + msg);
		replyTo.ifPresent(actorRef -> actorRef.tell(msg, self()));
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

	public static class EcsUpdate {
		public final EcsState state;

		public EcsUpdate(EcsState state) {
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
