package tmt.tcs.mcs;

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
import tmt.tcs.mcs.McsEventPublisher.McsStateUpdate;

/**
 * This class helps in receiving Current State being published by HCD and
 * redirects to Event Publisher as specific class type
 */
@SuppressWarnings({ "unused" })
public class McsEventDelegator extends BaseEventDelegator {

	LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private final Optional<ActorRef> eventPublisher;
	private final String hcdName;

	private McsEventDelegator(AssemblyContext assemblyContext, Optional<ActorRef> mcsHcd,
			Optional<ActorRef> eventPublisher) {

		log.debug("Inside McsEventDelegator");
		this.eventPublisher = eventPublisher;

		subscribeToLocationUpdates();

		mcsHcd.ifPresent(actorRef -> actorRef.tell(JPublisherActor.Subscribe, self()));
		this.hcdName = assemblyContext.info.getConnections().get(0).name();

		receive(operationsReceive(hcdName, 0, mcsHcd, eventPublisher));
	}

	/**
	 * This method will be called in case Telemetry Operations mode
	 */
	public PartialFunction<Object, BoxedUnit> operationsReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(McsConfig.currentPosCK)) {
				publishMcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside McsEventDelegator operationsReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(operationsReceive(hcdName, stateMessageCounter, newHcdActorRef, eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside McsEventDelegator operationsReceive got unresolve for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside McsEventDelegator operationsReceive got untrack for HCD");
					context().become(operationsReceive(hcdName, stateMessageCounter, Optional.empty(), eventPublisher));
				}
			}
		}).matchAny(
				t -> log.warning("Inside McsEventDelegator :operationsReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This method will be called in case Telemetry Diagnostic mode
	 */
	public PartialFunction<Object, BoxedUnit> diagnosticReceive(String hcdName, int stateMessageCounter,
			Optional<ActorRef> hcd, Cancellable cancelToken, Optional<ActorRef> eventPublisher) {
		return ReceiveBuilder.match(CurrentState.class, cs -> {
			if (cs.configKey().equals(McsConfig.currentPosCK)) {
				publishMcsPosUpdate(cs, eventPublisher);
			}
		}).match(Location.class, location -> {

			if (location instanceof LocationService.ResolvedAkkaLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					LocationService.ResolvedAkkaLocation rloc = (LocationService.ResolvedAkkaLocation) location;
					log.debug("Inside McsEventDelegator diagnosticReceive updated actorRef: " + rloc.getActorRef());
					Optional<ActorRef> newHcdActorRef = rloc.getActorRef();
					newHcdActorRef.ifPresent(actorRef -> actorRef.tell(JHcdController.Subscribe, self()));
					context().become(diagnosticReceive(hcdName, stateMessageCounter, newHcdActorRef, cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.Unresolved) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside McsEventDelegator diagnosticReceive got unresolve for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}

			} else if (location instanceof LocationService.UnTrackedLocation) {
				if (Objects.equals(location.connection().name(), hcdName)) {
					log.debug("Inside McsEventDelegator diagnosticReceive got untrack for HCD");
					context().become(diagnosticReceive(hcdName, stateMessageCounter, Optional.empty(), cancelToken,
							eventPublisher));
				}
			}
		}).matchAny(
				t -> log.warning("Inside McsEventDelegator: diagnosticReceive received an unexpected message: " + t))
				.build();
	}

	/**
	 * This publishes State Updates
	 */
	public void publishMcsPosUpdate(CurrentState cs, Optional<ActorRef> eventPublisher) {
		log.debug("Inside McsEventDelegator publish state: " + cs);
		eventPublisher.ifPresent(actorRef -> actorRef.tell(new McsStateUpdate(jitem(cs, McsConfig.mcsStateKey),
				jitem(cs, McsConfig.azPosKey), jitem(cs, McsConfig.elPosKey)), self()));
	}

	public static Props props(AssemblyContext assemblyContext, Optional<ActorRef> mcsHcd,
			Optional<ActorRef> eventPublisher) {
		return Props.create(new Creator<McsEventDelegator>() {
			private static final long serialVersionUID = 1L;

			@Override
			public McsEventDelegator create() throws Exception {
				return new McsEventDelegator(assemblyContext, mcsHcd, eventPublisher);
			}
		});
	}
}
