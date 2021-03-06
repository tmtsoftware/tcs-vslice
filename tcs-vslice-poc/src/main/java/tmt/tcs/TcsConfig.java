package tmt.tcs;

import csw.util.config.Configurations.ConfigKey;
import csw.util.config.DoubleKey;
import csw.util.config.StringKey;

/**
 * This class contains all the configurations specific to TCS Assembly
 */
public class TcsConfig {

	public static final String tcsPrefix = "tcs";
	public static final String mcsPrefix = "tcs.mcs";
	public static final String ecsPrefix = "tcs.ecs";
	public static final String m3Prefix = "tcs.m3";
	public static final String tpkPrefix = "tcs.tpk";
	public static final String tcsTpkPrefix = "tcs.str";
	public static final String initPrefix = tcsPrefix + ".init";
	public static final String movePrefix = tcsPrefix + ".move";
	public static final String offsetPrefix = tcsPrefix + ".offset";
	public static final String followPrefix = tcsPrefix + ".follow";
	public static final String tcsStatePrefix = tcsPrefix + ".tcsState";
	public static final String positionDemandPrefix = tcsTpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = tcsTpkPrefix + ".offsetdemands";
	public static final String currentPosPrefix = tcsPrefix + ".currentposition";
	public static final String mcsPositionPrefix = mcsPrefix + ".position";
	public static final String ecsPositionPrefix = ecsPrefix + ".position";
	public static final String m3PositionPrefix = m3Prefix + ".position";

	public static final ConfigKey dummyCK = new ConfigKey(tcsPrefix);
	public static final ConfigKey initCK = new ConfigKey(initPrefix);
	public static final ConfigKey moveCK = new ConfigKey(movePrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey followCK = new ConfigKey(followPrefix);
	public static final ConfigKey tcsStateCK = new ConfigKey(tcsStatePrefix);
	public static final ConfigKey positionDemandCK = new ConfigKey(positionDemandPrefix);
	public static final ConfigKey offsetDemandCK = new ConfigKey(offsetDemandPrefix);
	public static final ConfigKey currentPosCK = new ConfigKey(currentPosPrefix);
	public static final ConfigKey mcsPositionCK = new ConfigKey(mcsPositionPrefix);
	public static final ConfigKey ecsPositionCK = new ConfigKey(ecsPositionPrefix);
	public static final ConfigKey m3PositionCK = new ConfigKey(m3PositionPrefix);

	public static final StringKey target = new StringKey("tcs.target");
	public static final DoubleKey ra = new DoubleKey("tcs.ra");
	public static final DoubleKey dec = new DoubleKey("tcs.dec");
	public static final StringKey frame = new StringKey("tcs.frame");

}
