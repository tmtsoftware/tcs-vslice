package tmt.tcs.ecs;

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
import tmt.tcs.common.BaseEventDelegator;
import tmt.tcs.ecs.EcsEventPublisher.EcsStateUpdate;

/**
 * This class provides diagnostic telemetry in the form of two events. It
 * operates in the 'OperationsState' or 'DiagnosticState'.
 */
@SuppressWarnings({ "unused" })
public class EcsEventDelegator extends BaseEventDelegator {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> eventPublisher;
	private final String hcdName;

	private EcsEventDelegator(AssemblyContext assemblyContext, Optional<ActorRef> ecsHcd,
			Optional<ActorRef> eventPublisher) {

		log.debug("Inside EcsEventDelegator");
		this.eventPublisher = eventPublisher;

		subscribeToLocationUpdates();

		ecsHcd.ifPresent(actorRef -> actorRef.tell(JPublisherActor.Subscribe, self()));
		this.hcdName = assemblyContext.info.getConnections().get(0).name();

		receive(operationsReceive(hcdName, 0, ecsHcd, eventPublisher));
	}

	/**
	 * This method will be called in case Telemetry Operations mode
	 */
	public PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(EcsConfig.currentPosCK)) {
				publishEcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside EcsEventDelegator operationsReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(operationsReceive(hcdName, stateMessageCounter, newHcdActorRef, eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside EcsEventDelegator operationsReceive got unresolve for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside EcsEventDelegator operationsReceive got untrack for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside EcsEventDelegator :operationsReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This method will be called in case Telemetry Diagnostic mode
	 */
	public PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(EcsConfig.currentPosCK)) {
				publishEcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside EcsEventDelegator diagnosticReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(diagnosticReceive(hcdName, stateMessageCounter, newHcdActorRef, cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside EcsEventDelegator diagnosticReceive got unresolve for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside EcsEventDelegator diagnosticReceive got untrack for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}
			}
		}).matchAny(t -> log.warning("Inside EcsEventDelegator:diagnosticReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This publishes State Updates
	 */
	public void publishEcsPosUpdate(CurrentState cs, Optional<ActorRef> eventPublisher) {
		log.debug("Inside EcsEventDelegator publish state: " + cs);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new EcsStateUpdate(jitem(cs, EcsConfig.ecsStateKey),
				jitem(cs, EcsConfig.azPosKey), jitem(cs, EcsConfig.elPosKey)), self()));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<ActorRef> ecsHcd,
			Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<EcsEventDelegator>() {
			private static final long serialVersionUID = 1L;

			@Override
			public EcsEventDelegator create() throws Exception {
				return new EcsEventDelegator(assemblyContext, ecsHcd, eventPublisher);
			}
		});
	}

}
