package tmt.tcs;

import java.util.Objects;
import java.util.Optional;

import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.services.loc.LocationService;
import csw.services.loc.LocationService.Location;
import csw.util.config.StateVariable.CurrentState;
import javacsw.services.ccs.JHcdController;
import javacsw.util.config.JPublisherActor;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.BaseEventDelegator;

/**
 * This class provides diagnostic telemetry for TCS in the form of two events.
 * It operates in the 'OperationsState' or 'DiagnosticState'.
 */
@SuppressWarnings({ "unused" })
public class TcsDiagnosticPublisher extends BaseEventDelegator {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> eventPublisher;
	private final String actorName;

	private TcsDiagnosticPublisher(AssemblyContext assemblyContext, Optional<ActorRef> referedActor,
			Optional<ActorRef> eventPublisher) {

		log.debug("Inside TcsDiagPublisher");
		this.eventPublisher = eventPublisher;

		subscribeToLocationUpdates();

		referedActor.ifPresent(actorRef -> actorRef.tell(JPublisherActor.Subscribe, self()));
		this.actorName = assemblyContext.info.getConnections().get(0).name();

		receive(operationsReceive(actorName, 0, referedActor, eventPublisher));
	}

	/**
	 * This method will be called in case Telemetry Operations mode
	 */
	public PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(TcsConfig.tcsStateCK)) {
				publishTcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug(
							"Inside TcsDiagPublisher operationsReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(operationsReceive(hcdName, stateMessageCounter, newHcdActorRef, eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside TcsDiagPublisher operationsReceive got unresolve for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside TcsDiagPublisher operationsReceive got untrack for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside TcsDiagPublisher :operationsReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This method will be called in case Telemetry Diagnostic mode
	 */
	public PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(TcsConfig.tcsStateCK)) {
				publishTcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug(
							"Inside TcsDiagPublisher diagnosticReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(diagnosticReceive(hcdName, stateMessageCounter, newHcdActorRef, cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside TcsDiagPublisher diagnosticReceive got unresolve for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside TcsDiagPublisher diagnosticReceive got untrack for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside TcsDiagPublisher:diagnosticReceive received an unexpected message: " + t)).build();
	}

	/**
	 * This publishes State Updates
	 */
	public void publishTcsPosUpdate(CurrentState cs, Optional<ActorRef> eventPublisher) {
		log.debug("Inside TcsDiagPublisher publish state: " + cs);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new String("TcsTest"), self()));
	}

	/**
	 * This publishes Stats Updates
	 */
	public void publishStatsUpdate(CurrentState cs, Optional<ActorRef> eventPublisher) {
		log.debug("Inside TcsDiagPublisher publish stats");
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new String("TcsTest"), self()));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<ActorRef> referedActor,
			Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<TcsDiagnosticPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public TcsDiagnosticPublisher create() throws Exception {
				return new TcsDiagnosticPublisher(assemblyContext, referedActor, eventPublisher);
			}
		});
	}
}
