package tmt.tcs.common;

import static akka.pattern.PatternsCS.ask;

import java.util.Optional;
import java.util.function.Consumer;

import akka.actor.AbstractActor;
import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus.CommandStatus;
import csw.services.ccs.MultiStateMatcherActor;
import csw.services.ccs.StateMatcher;
import javacsw.services.pkg.ILocationSubscriberClient;

/**
 * This is base class for all command handler classes
 * 
 */
public abstract class BaseCommandHandler extends AbstractActor
		implements AssemblyStateClient, ILocationSubscriberClient {

	private AssemblyStateActor.AssemblyState internalState = AssemblyStateActor.defaultAssemblyState;

	public static class CommandDone {
	}

	@Override
	public void setCurrentState(AssemblyStateActor.AssemblyState state) {
		internalState = state;
	}

	/**
	 * @return AssemblyStateActor.AssemblyState
	 */
	public AssemblyStateActor.AssemblyState currentState() {
		return internalState;
	}

	/**
	 * This method helps in demand matching for marking command execution as
	 * failed or success
	 * 
	 * @param context
	 * @param stateMatcher
	 * @param currentStateSource
	 * @param replyTo
	 * @param timeout
	 * @param codeBlock
	 */
	public static void executeMatch(ActorContext context, StateMatcher stateMatcher, ActorRef currentStateSource,
			Optional<ActorRef> replyTo, Timeout timeout, Consumer<CommandStatus> codeBlock) {

		System.out.println("Inside BaseCommandHandler executeMatch: Starts");

		ActorRef matcher = context.actorOf(MultiStateMatcherActor.props(currentStateSource, timeout));

		System.out.println("Inside BaseCommandHandler executeMatch: matcher is: " + matcher);
		System.out.println("Inside BaseCommandHandler executeMatch: stateMatcher is: " + stateMatcher);
		System.out.println("Inside BaseCommandHandler executeMatch: context is: " + context);
		System.out.println("Inside BaseCommandHandler executeMatch: currentStateSource is: " + currentStateSource);
		System.out.println("Inside BaseCommandHandler executeMatch: replyTo is: " + replyTo);

		ask(matcher, MultiStateMatcherActor.createStartMatch(stateMatcher), timeout).thenApply(reply -> {
			System.out.println("Inside BaseCommandHandler executeMatch: reply: " + reply);
			CommandStatus cmdStatus = (CommandStatus) reply;
			System.out.println("Inside BaseCommandHandler executeMatch: cmdStatus: " + cmdStatus);
			codeBlock.accept(cmdStatus);
			replyTo.ifPresent(actorRef -> actorRef.tell(cmdStatus, context.self()));
			return null;
		});
	}
}
