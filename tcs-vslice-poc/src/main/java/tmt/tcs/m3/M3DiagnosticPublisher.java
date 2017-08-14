package tmt.tcs.m3;

import static javacsw.util.config.JItems.jitem;

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
import tmt.tcs.common.BaseDiagnosticPublisher;
import tmt.tcs.m3.M3EventPublisher.M3StateUpdate;

/**
 * This class provides diagnostic telemetry for M3 in the form of two events. It
 * operates in the 'OperationsState' or 'DiagnosticState'.
 */
@SuppressWarnings({ "unused" })
public class M3DiagnosticPublisher extends BaseDiagnosticPublisher {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> eventPublisher;
	private final String hcdName;

	private M3DiagnosticPublisher(AssemblyContext assemblyContext, Optional<ActorRef> m3Hcd,
			Optional<ActorRef> eventPublisher) {

		log.debug("Inside M3DiagPublisher");
		this.eventPublisher = eventPublisher;

		subscribeToLocationUpdates();

		m3Hcd.ifPresent(actorRef -> actorRef.tell(JPublisherActor.Subscribe, self()));
		this.hcdName = assemblyContext.info.getConnections().get(0).name();

		receive(operationsReceive(hcdName, 0, m3Hcd, eventPublisher));
	}

	/**
	 * This method will be called in case Telemetry Operations mode
	 */
	public PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(M3Config.m3StateCK)) {
				publishStateUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside M3DiagPublisher operationsReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(operationsReceive(hcdName, stateMessageCounter, newHcdActorRef, eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside M3DiagPublisher operationsReceive got unresolve for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside M3DiagPublisher operationsReceive got untrack for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside M3DiagPublisher :operationsReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This method will be called in case Telemetry Diagnostic mode
	 */
	public PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(M3Config.m3StateCK)) {
				publishStateUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside M3DiagPublisher diagnosticReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(diagnosticReceive(hcdName, stateMessageCounter, newHcdActorRef, cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside M3DiagPublisher diagnosticReceive got unresolve for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside M3DiagPublisher diagnosticReceive got untrack for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside M3DiagPublisher:diagnosticReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This publishes State Updates
	 */
	public void publishStateUpdate(CurrentState cs, Optional<ActorRef> eventPublisher) {
		log.debug("Inside M3DiagPublisher publish state: " + cs);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new M3StateUpdate(jitem(cs, M3Config.m3StateKey)), self()));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<ActorRef> m3Hcd,
			Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<M3DiagnosticPublisher>() {
			private static final long serialVersionUID = 1L;

			@Override
			public M3DiagnosticPublisher create() throws Exception {
				return new M3DiagnosticPublisher(assemblyContext, m3Hcd, eventPublisher);
			}
		});
	}

}
