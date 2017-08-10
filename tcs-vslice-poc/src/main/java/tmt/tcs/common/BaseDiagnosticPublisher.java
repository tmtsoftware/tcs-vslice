package tmt.tcs.common;

import java.util.Optional;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import csw.util.config.StateVariable.CurrentState;
import javacsw.services.pkg.ILocationSubscriberClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base Diagnostic Publisher class being extended by all Diag
 * Publisher
 */
public abstract class BaseDiagnosticPublisher extends AbstractActor implements ILocationSubscriberClient {

	public abstract PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher);

	public abstract PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher);

	public abstract void publishStateUpdate(CurrentState cs, Optional<ActorRef> eventPublisher);

	/**
	 * Internal messages used by diag publisher
	 */
	public interface DiagPublisherMessages {
	}

	public static class DiagnosticState implements DiagPublisherMessages {
	}

	public static class OperationsState implements DiagPublisherMessages {
	}
}
