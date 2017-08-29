package tmt.tcs.tpk;

import csw.util.config.Configurations.ConfigKey;
import csw.util.config.DoubleKey;
import csw.util.config.StringKey;

/**
 * This class contains all the configurations specific to TPK Assembly
 */
public class TpkConfig {

	public static final String tpkPrefix = "tcs.tpk";
	public static final String followPrefix = tpkPrefix + ".follow";
	public static final String offsetPrefix = tpkPrefix + ".offset";
	public static final String positionDemandPrefix = tpkPrefix + ".positiondemands";
	public static final String offsetDemandPrefix = tpkPrefix + ".offsetdemands";

	public static final ConfigKey followCK = new ConfigKey(followPrefix);
	public static final ConfigKey offsetCK = new ConfigKey(offsetPrefix);
	public static final ConfigKey positionDemandCK = new ConfigKey(positionDemandPrefix);
	public static final ConfigKey offsetDemandCK = new ConfigKey(offsetDemandPrefix);

	public static final StringKey target = new StringKey("tcs.tpk.target");
	public static final DoubleKey ra = new DoubleKey("tcs.tpk.ra");
	public static final DoubleKey dec = new DoubleKey("tcs.tpk.dec");
	public static final StringKey frame = new StringKey("tcs.tpk.frame");

}
