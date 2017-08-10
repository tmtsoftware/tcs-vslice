package tmt.tcs.common;

import static akka.pattern.PatternsCS.ask;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import csw.util.config.Configurations.SetupConfig;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyStateActor.AssemblySetState;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;

public abstract class BaseCommand extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	public abstract PartialFunction<Object, BoxedUnit> processCommand(SetupConfig sc, ActorRef mcsHcd,
			AssemblyState mcsStartState);

	/**
	 * This helps in updating assembly state while command execution
	 * 
	 * @param mcsSetState
	 */
	public void sendState(Optional<ActorRef> mcsStateActor, AssemblySetState mcsSetState) {
		mcsStateActor.ifPresent(actorRef -> {
			try {
				ask(actorRef, mcsSetState, 5000).toCompletableFuture().get();
			} catch (Exception e) {
				log.error(e, "Inside McsMoveCommand: sendState: Error setting state");
			}
		});
	}
}
