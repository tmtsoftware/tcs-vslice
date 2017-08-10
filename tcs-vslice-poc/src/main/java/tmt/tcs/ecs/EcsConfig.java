package tmt.tcs.ecs;

import static javacsw.util.config.JConfigDSL.cs;
import static javacsw.util.config.JItems.jset;

import csw.util.config.BooleanKey;
import csw.util.config.Configurations.ConfigKey;
import csw.util.config.DoubleKey;
import csw.util.config.IntKey;
import csw.util.config.StateVariable.CurrentState;

/*
 * This class contains all the configurations specific to ECS Assembly
 */
public class EcsConfig {

	public static final String ecsPrefix = "tcs.ecs";
	public static final String ecsTpkPrefix = "tcs.str.ecs";
	public static final String initPrefix = ecsPrefix + ".init";
	public static final String movePrefix = ecsPrefix + ".move";
	public static final String offsetPrefix = ecsPrefix + ".offset";
	public static final String ecsStatePrefix = ecsPrefix + ".ecsState";
	public static final String ecsStatsPrefix = ecsPrefix + ".stats";
	public static final String ecsStateEventPrefix = ecsPrefix + ".ecsState";
	public static final String ecsStatsEventPrefix = ecsPrefix + ".stats";
	public static final String positionDemandPrefix = ecsTpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = ecsTpkPrefix + ".offsetdemands";
	public static final String currentPosPrefix = ecsPrefix + ".currentposition";

	public static final ConfigKey dummyCK = new ConfigKey(ecsPrefix);
	public static final ConfigKey initCK = new ConfigKey(initPrefix);
	public static final ConfigKey moveCK = new ConfigKey(movePrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey ecsStateCK = new ConfigKey(ecsStatePrefix);
	public static final ConfigKey ecsStatsCK = new ConfigKey(ecsStatsPrefix);
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

	public static final CurrentState defaultEcsStatsState = cs(ecsStatsCK.prefix(), jset(az, 1.0));

}
