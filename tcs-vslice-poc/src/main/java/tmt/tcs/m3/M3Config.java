package tmt.tcs.m3;

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
 * This class contains all the configurations specific to M3 Assembly
 */
public class M3Config {
	public static final String m3Prefix = "tcs.m3";
	public static final String m3TpkPrefix = "tcs.str.m3";
	public static final String initPrefix = m3Prefix + ".init";
	public static final String followPrefix = m3Prefix + ".follow";
	public static final String offsetPrefix = m3Prefix + ".offset";
	public static final String setRotationPrefix = m3Prefix + ".rotation";
	public static final String setTiltPrefix = m3Prefix + ".tilt";
	public static final String m3StateEventPrefix = m3Prefix + ".m3State";
	public static final String positionDemandPrefix = m3TpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = m3TpkPrefix + ".offsetdemands";
	public static final String currentPosPrefix = m3Prefix + ".currentposition";
	public static final String telemetryEventPrefix = m3Prefix + ".telemetry";

	public static final ConfigKey initCK = new ConfigKey(initPrefix);
	public static final ConfigKey followCK = new ConfigKey(followPrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey setRotationCK = new ConfigKey(setRotationPrefix);
	public static final ConfigKey setTiltCK = new ConfigKey(setTiltPrefix);
	public static final ConfigKey positionDemandCK = new ConfigKey(positionDemandPrefix);
	public static final ConfigKey offsetDemandCK = new ConfigKey(offsetDemandPrefix);
	public static final ConfigKey currentPosCK = new ConfigKey(currentPosPrefix);

	public static final DoubleKey rotationDemandKey = new DoubleKey("tcs.str.m3.rotation");
	public static final DoubleKey tiltDemandKey = new DoubleKey("tcs.str.m3.tilt");
	public static final DoubleKey timeDemandKey = new DoubleKey("tcs.str.m3.time");

	public static final DoubleKey rotation = new DoubleKey("tcs.m3.rotation");
	public static final DoubleKey tilt = new DoubleKey("tcs.m3.tilt");
	public static final DoubleKey time = new DoubleKey("tcs.m3.time");

	public static final DoubleKey rotationPosKey = new DoubleKey("tcs.m3.rotation_pos");
	public static final DoubleKey rotationPosDemandKey = new DoubleKey("tcs.m3.rotation_pos_demand");
	public static final DoubleKey rotationPosErrorKey = new DoubleKey("tcs.m3.rotation_pos_error");
	public static final BooleanKey rotationInpositionKey = new BooleanKey("tcs.m3.rotation_inposition");
	public static final DoubleKey tiltPosKey = new DoubleKey("tcs.m3.tilt_pos");
	public static final DoubleKey tiltPosDemandKey = new DoubleKey("tcs.m3.tilt_pos_demand");
	public static final DoubleKey tiltPosErrorKey = new DoubleKey("cs.m3.tilt_pos_error");
	public static final BooleanKey tiltInpositionKey = new BooleanKey("tcs.m3.tilt_inposition");
	public static final DoubleKey encoderLatchTimeKey = new DoubleKey("tcs.m3.encoder_latching_time");
	public static final IntKey rotationPosDmdErrcnt = new IntKey("tcs.m3.rotation_pos_dmd_errcnt");
	public static final IntKey tiltPosDmdErrcnt = new IntKey("tcs.m3.tilt_pos_dmd_errcnt");
	public static final DoubleKey posTimeKey = new DoubleKey("tcs.m3.time");
	
	public static final Double defaultRotationValue = 3.1;
	public static final Double defaultTiltValue = 3.2;
	
	public static final Double defaultRotationIncrementer = 0.1;
	public static final Double defaultTiltIncrementer = 0.1;

	public static DoubleItem rotation(double rotationValue) {
		return jset(rotation, rotationValue);
	}

	public static DoubleItem tilt(double tiltValue) {
		return jset(tilt, tiltValue);
	}

	// Refered by M3 HCD
	public static final Choice M3_IDLE = new Choice(M3State.M3_IDLE.toString());
	public static final Choice M3_MOVING = new Choice(M3State.M3_MOVING.toString());
	public static final Choice M3_ERROR = new Choice(M3State.M3_ERROR.toString());
	public static final ChoiceKey m3StateKey = new ChoiceKey("m3State",
			Choices.from(M3_IDLE.toString(), M3_MOVING.toString(), M3_ERROR.toString()));

	public enum M3State {
		M3_IDLE, M3_MOVING, M3_ERROR,
	}

}
