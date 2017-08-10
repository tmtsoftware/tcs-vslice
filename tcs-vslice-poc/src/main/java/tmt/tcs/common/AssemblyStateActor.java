package tmt.tcs.common;

import static javacsw.util.config.JItems.jset;
import static javacsw.util.config.JItems.jvalue;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.pf.ReceiveBuilder;
import csw.util.config.Choice;
import csw.util.config.ChoiceItem;
import csw.util.config.ChoiceKey;
import csw.util.config.Choices;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is Base State Actor class which helps in keeps details about possible
 * states of assembly and keeps track of assembly states
 */
public class AssemblyStateActor extends AbstractActor {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	private AssemblyStateActor() {
		receive(stateReceive(new AssemblyState(azDefault, elDefault)));
	}

	/**
	 * This method receives state requests and set or fetch Assembly state
	 * accordingly
	 * 
	 * @param assemblyCurrentState
	 * @return
	 */
	private PartialFunction<Object, BoxedUnit> stateReceive(AssemblyState assemblyCurrentState) {
		return ReceiveBuilder.match(AssemblySetState.class, t -> {
			log.debug("Inside AssemblyStateActor stateReceive : AssemblySetState: " + assemblyCurrentState);
			AssemblyState assemblyState = t.assemblyState;
			if (!assemblyState.equals(assemblyCurrentState)) {
				log.debug("Inside AssemblyStateActor stateReceive : Setting State: " + assemblyState);
				context().system().eventStream().publish(assemblyState);
				context().become(stateReceive(assemblyState));
				sender().tell(new AssemblyStateWasSet(true), self());
			} else {
				sender().tell(new AssemblyStateWasSet(false), self());
			}
		}).match(AssemblyGetState.class, t -> {
			log.debug("Inside AssemblyStateActor stateReceive : GetState");
			sender().tell(assemblyCurrentState, self());
		}).matchAny(t -> log.warning("Inside AssemblyStateActor stateReceive message is: " + t)).build();
	}

	public static Props props() {
		return Props.create(new Creator<AssemblyStateActor>() {
			private static final long serialVersionUID = 1L;

			@Override
			public AssemblyStateActor create() throws Exception {
				return new AssemblyStateActor();
			}
		});
	}

	// Keys for state telemetry item
	public static final Choice azShutDown = new Choice("shutdown");
	public static final Choice azDrivePowerOn = new Choice("downpoweron");
	public static final Choice azDatumed = new Choice("datumed");
	public static final Choice azFollowing = new Choice("following");
	public static final Choice azPointing = new Choice("pointing");
	public static final Choice azFaulted = new Choice("error");
	public static final ChoiceKey azKey = new ChoiceKey("az",
			Choices.fromChoices(azShutDown, azDrivePowerOn, azDatumed, azFollowing, azPointing, azFaulted));
	public static final ChoiceItem azDefault = azItem(azDrivePowerOn);

	public static Choice az(AssemblyState assemblyState) {
		return jvalue(assemblyState.az);
	}

	/**
	 * A convenience method to set the azItem choice
	 *
	 * @param ch
	 *            one of the az choices
	 * @return a ChoiceItem with the choice value
	 */
	public static ChoiceItem azItem(Choice ch) {
		return jset(azKey, ch);
	}

	public static final Choice elShutDown = new Choice("shutdown");
	public static final Choice elDrivePowerOn = new Choice("downpoweron");
	public static final Choice elDatumed = new Choice("datumed");
	public static final Choice elFollowing = new Choice("following");
	public static final Choice elPointing = new Choice("pointing");
	public static final Choice elFaulted = new Choice("error");
	public static final ChoiceKey elKey = new ChoiceKey("el",
			Choices.fromChoices(elShutDown, elDrivePowerOn, elDatumed, elFollowing, azPointing, elFaulted));
	public static final ChoiceItem elDefault = elItem(elDrivePowerOn);

	public static Choice el(AssemblyState assemblyState) {
		return jvalue(assemblyState.el);
	}

	/**
	 * A convenience method to set the elItem choice
	 *
	 * @param ch
	 *            one of the el choices
	 * @return a ChoiceItem with the choice value
	 */
	public static ChoiceItem elItem(Choice ch) {
		return jset(elKey, ch);
	}

	public static final AssemblyState defaultAssemblyState = new AssemblyState(azDefault, elDefault);

	/**
	 * This class is sent to the publisher for publishing when any state value
	 * changes
	 */
	public static class AssemblyState {
		public final ChoiceItem az;
		public final ChoiceItem el;

		/**
		 * Constructor
		 *
		 * @param az
		 *            the current az state
		 * @param move
		 *            the current el state
		 */
		public AssemblyState(ChoiceItem az, ChoiceItem el) {
			this.az = az;
			this.el = el;
		}

		@Override
		public String toString() {
			return "AssemblyState [az=" + az + ", el=" + el + "]";
		}

	}

	/**
	 * Update the current state with a AssemblyState
	 */
	public static class AssemblySetState {
		public final AssemblyState assemblyState;

		/**
		 * Constructor
		 *
		 * @param assemblyState
		 *            the new assembly state value
		 */
		public AssemblySetState(AssemblyState assemblyState) {
			this.assemblyState = assemblyState;
		}

		/**
		 * Alternate way to create the SetState message using items
		 *
		 * @param cmd
		 *            a ChoiceItem created with azItem
		 * @param move
		 *            a ChoiceItem created with elItem
		 */
		public AssemblySetState(ChoiceItem az, ChoiceItem el) {
			this(new AssemblyState(az, el));
		}

		/**
		 * Alternate way to create the SetState message using primitives
		 *
		 * @param az
		 *            a Choice for the az value
		 * @param el
		 *            a Choice for the el value
		 */
		public AssemblySetState(Choice az, Choice el) {
			this(new AssemblyState(azItem(az), elItem(el)));
		}

		@Override
		public String toString() {
			return "AssemblySetState [assemblyState=" + assemblyState + "]";
		}

	}

	/**
	 * A message that causes the current state to be sent back to the sender
	 */
	public static class AssemblyGetState {
	}

	/**
	 * Reply to SetState message that indicates if the state was actually set
	 */
	public static class AssemblyStateWasSet {
		final boolean wasSet;

		public AssemblyStateWasSet(boolean wasSet) {
			this.wasSet = wasSet;
		}
	}

}
