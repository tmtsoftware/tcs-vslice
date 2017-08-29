package tmt.tcs.ecs;

import static javacsw.util.config.JItems.jset;

import csw.util.config.BooleanKey;
import csw.util.config.Choice;
import csw.util.config.ChoiceKey;
import csw.util.config.Choices;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.DoubleItem;
import csw.util.config.DoubleKey;
import csw.util.config.IntKey;

/**
 * This class contains all the configurations specific to ECS Assembly
 */
public class EcsConfig {

	public static final String ecsPrefix = "tcs.ecs";
	public static final String ecsTpkPrefix = "tcs.str.ecs";
	public static final String initPrefix = ecsPrefix + ".init";
	public static final String followPrefix = ecsPrefix + ".follow";
	public static final String offsetPrefix = ecsPrefix + ".offset";
	public static final String setAzimuthPrefix = ecsPrefix + ".azimuth";
	public static final String setElevationPrefix = ecsPrefix + ".elevation";
	public static final String ecsStateEventPrefix = ecsPrefix + ".ecsState";
	public static final String positionDemandPrefix = ecsTpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = ecsTpkPrefix + ".offsetdemands";
	public static final String currentPosPrefix = ecsPrefix + ".currentposition";
	public static final String telemetryEventPrefix = ecsPrefix + ".telemetry";

	public static final ConfigKey initCK = new ConfigKey(initPrefix);
	public static final ConfigKey followCK = new ConfigKey(followPrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey setAzimuthCK = new ConfigKey(setAzimuthPrefix);
	public static final ConfigKey setElevationCK = new ConfigKey(setElevationPrefix);
	public static final ConfigKey positionDemandCK = new ConfigKey(positionDemandPrefix);
	public static final ConfigKey offsetDemandCK = new ConfigKey(offsetDemandPrefix);
	public static final ConfigKey currentPosCK = new ConfigKey(currentPosPrefix);

	public static final DoubleKey azDemandKey = new DoubleKey("tcs.str.ecs.az");
	public static final DoubleKey elDemandKey = new DoubleKey("tcs.str.ecs.el");
	public static final DoubleKey timeDemandKey = new DoubleKey("tcs.str.ecs.time");

	public static final DoubleKey az = new DoubleKey("tcs.ecs.az");
	public static final DoubleKey el = new DoubleKey("tcs.ecs.el");
	public static final DoubleKey time = new DoubleKey("tcs.ecs.time");

	public static final DoubleKey azPosKey = new DoubleKey("tcs.ecs.az_pos");
	public static final DoubleKey azPosDemandKey = new DoubleKey("tcs.ecs.az_pos_demand");
	public static final DoubleKey azPosErrorKey = new DoubleKey("tcs.ecs.az_pos_error");
	public static final BooleanKey azInpositionKey = new BooleanKey("tcs.ecs.az_inposition");
	public static final DoubleKey elPosKey = new DoubleKey("tcs.ecs.el_pos");
	public static final DoubleKey elPosDemandKey = new DoubleKey("tcs.ecs.el_pos_demand");
	public static final DoubleKey elPosErrorKey = new DoubleKey("cs.ecs.el_pos_error");
	public static final BooleanKey elInpositionKey = new BooleanKey("tcs.ecs.el_inposition");
	public static final DoubleKey encoderLatchTimeKey = new DoubleKey("tcs.ecs.encoder_latching_time");
	public static final IntKey azPosDmdErrcnt = new IntKey("tcs.ecs.az_pos_dmd_errcnt");
	public static final IntKey elPosDmdErrcnt = new IntKey("tcs.ecs.el_pos_dmd_errcnt");
	public static final DoubleKey posTimeKey = new DoubleKey("tcs.ecs.time");

	public static DoubleItem az(double azValue) {
		return jset(az, azValue);
	}

	public static DoubleItem el(double elValue) {
		return jset(el, elValue);
	}

	// Refered by ECS HCD
	public static final Choice ECS_IDLE = new Choice(EcsState.ECS_IDLE.toString());
	public static final Choice ECS_MOVING = new Choice(EcsState.ECS_MOVING.toString());
	public static final Choice ECS_ERROR = new Choice(EcsState.ECS_ERROR.toString());
	public static final ChoiceKey ecsStateKey = new ChoiceKey("ecsState",
			Choices.from(ECS_IDLE.toString(), ECS_MOVING.toString(), ECS_ERROR.toString()));

	public enum EcsState {
		ECS_IDLE, ECS_MOVING, ECS_ERROR,
	}

}
