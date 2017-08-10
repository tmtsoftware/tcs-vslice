package tmt.tcs.common;

import static javacsw.services.pkg.JSupervisor.DoRestart;
import static javacsw.services.pkg.JSupervisor.DoShutdown;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.services.pkg.JSupervisor.RunningOffline;
import static javacsw.services.pkg.JSupervisor.ShutdownComplete;

import akka.actor.ActorRef;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import csw.services.pkg.Supervisor;
import javacsw.services.ccs.JHcdController;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base class for all HCDs with some common functions being used by
 * all HCDs
 */
public abstract class BaseHcd extends JHcdController {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	/**
	 * HDC's intializing receive method receives HCD messages
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> initializingReceive(ActorRef supervisor) {
		return publisherReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside BaseHcd received Running");
			context().become(runningReceive(supervisor));
		}).matchAny(x -> log.warning("Inside BaseHcd Unexpected message (Not running yet): " + x)).build());
	}

	/**
	 * This method helps maintaining HCD's lifecycle
	 * 
	 * @param supervisor
	 * @return
	 */
	public PartialFunction<Object, BoxedUnit> runningReceive(ActorRef supervisor) {
		return controllerReceive().orElse(ReceiveBuilder.matchEquals(Running, e -> {
			log.debug("Inside BaseHcd Received Running");
		}).matchEquals(RunningOffline, e -> {
			log.debug("Inside BaseHcd Received RunningOffline");
		}).matchEquals(DoRestart, e -> {
			log.debug("Inside BaseHcd Received DoRestart");
		}).matchEquals(DoShutdown, e -> {
			log.debug("Inside BaseHcd Received DoShutdown");
			supervisor.tell(ShutdownComplete, self());
		}).match(Supervisor.LifecycleFailureInfo.class, e -> {
			log.error("Received failed state: " + e.state() + " for reason: " + e.reason());
		}).matchAny(x -> log.warning("Inside BaseHcd Unexpected message :unhandledPF: " + x)).build());
	}
}
