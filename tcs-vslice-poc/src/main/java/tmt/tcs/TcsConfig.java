package tmt.tcs;

import static javacsw.util.config.JConfigDSL.cs;

import csw.util.config.Configurations.ConfigKey;
import csw.util.config.StateVariable.CurrentState;

/*
 * This class contains all the configurations specific to TCS Assembly
 */
public class TcsConfig {

	public static final String tcsPrefix = "tcs";
	public static final String tcsTpkPrefix = "tcs.str";
	public static final String initPrefix = tcsPrefix + ".init";
	public static final String movePrefix = tcsPrefix + ".move";
	public static final String offsetPrefix = tcsPrefix + ".offset";
	public static final String tcsStatePrefix = tcsPrefix + ".tcsState";
	public static final String tcsStatsPrefix = tcsPrefix + ".stats";
	public static final String positionDemandPrefix = tcsTpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = tcsTpkPrefix + ".offsetdemands";
	public static final String currentPosPrefix = tcsPrefix + ".currentposition";

	public static final ConfigKey dummyCK = new ConfigKey(tcsPrefix);
	public static final ConfigKey initCK = new ConfigKey(initPrefix);
	public static final ConfigKey moveCK = new ConfigKey(movePrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey tcsStateCK = new ConfigKey(tcsStatePrefix);
	public static final ConfigKey tcsStatsCK = new ConfigKey(tcsStatsPrefix);
	public static final ConfigKey positionDemandCK = new ConfigKey(positionDemandPrefix);
	public static final ConfigKey offsetDemandCK = new ConfigKey(offsetDemandPrefix);
	public static final ConfigKey currentPosCK = new ConfigKey(currentPosPrefix);

	public static final CurrentState defaultTcsStatsState = cs(tcsStatsCK.prefix());

}
