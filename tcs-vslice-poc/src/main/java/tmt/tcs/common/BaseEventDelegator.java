package tmt.tcs.common;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import javacsw.services.pkg.ILocationSubscriberClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base Event Delegator class being extended by all Event Delegators
 */
public abstract class BaseEventDelegator extends AbstractActor implements ILocationSubscriberClient {

	/**
	 * Implementation for this method will be specific to implementor
	 * 
	 * @param hcdName
	 * @param stateMessageCounter
	 * @param hcd
	 * @param eventPublisher
	 * @return
	 */
	public abstract PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher);

	/**
	 * Implementation for this method will be specific to implementor
	 * 
	 * @param hcdName
	 * @param stateMessageCounter
	 * @param hcd
	 * @param cancelToken
	 * @param eventPublisher
	 * @return
	 */
	public abstract PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher);

	/**
	 * Internal messages used by Event Delegator
	 */
	public interface DiagPublisherMessages {
	}

	public static class DiagnosticState implements DiagPublisherMessages {
	}

	public static class OperationsState implements DiagPublisherMessages {
	}
}
