package tmt.tcs.mcs.hcd;

import com.typesafe.config.Config;

public class McsHWConfig {
	String name;
	int lowAzLimit;
	int lowElLimit;
	int lowUser;
	int highUser;
	int highAzLimit;
	int highElLimit;
	int home;
	int startPosition;
	int stepDelayMS;

	public McsHWConfig(String name, int lowAzLimit, int lowElLimit, int lowUser, int highUser, int highAzLimit,
			int highElLimit, int home, int startPosition, int stepDelayMS) {
		this.name = name;
		this.lowAzLimit = lowAzLimit;
		this.lowElLimit = lowElLimit;
		this.lowUser = lowUser;
		this.highUser = highUser;
		this.highAzLimit = highAzLimit;
		this.highElLimit = highElLimit;
		this.home = home;
		this.startPosition = startPosition;
		this.stepDelayMS = stepDelayMS;
	}

	public McsHWConfig(Config config) {
		// Main prefix for keys used below
		String prefix = "tmt.tcs.mcs.hcd";
		name = config.getString(prefix + ".mcs-config.axisName");
		lowAzLimit = config.getInt(prefix + ".mcs-config.lowAzLimit");
		lowElLimit = config.getInt(prefix + ".mcs-config.lowElLimit");
		lowUser = config.getInt(prefix + ".mcs-config.lowUser");
		highUser = config.getInt(prefix + ".mcs-config.highUser");
		highAzLimit = config.getInt(prefix + ".mcs-config.highAzLimit");
		highElLimit = config.getInt(prefix + ".mcs-config.highElLimit");
		home = config.getInt(prefix + ".mcs-config.home");
		startPosition = config.getInt(prefix + ".mcs-config.startPosition");
		stepDelayMS = config.getInt(prefix + ".mcs-config.stepDelayMS");
	}

}
