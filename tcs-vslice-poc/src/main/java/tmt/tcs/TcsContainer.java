package tmt.tcs;

import java.util.HashMap;
import java.util.Map;

import javacsw.services.apps.containerCmd.JContainerCmd;

public class TcsContainer {

	/**
	 * Starts the HCD and/or assembly as a standalone application. The first
	 * argument can be -s or --start with the value "hcd" or "assembly" to
	 * indicate that the HCD or assembly should be started alone. The default is
	 * to run both in the same JVM.
	 */
	public static void main(String args[]) {
		// This defines the names that can be used with the --start option and
		// the config files used ("" is the default entry)
		Map<String, String> m = new HashMap<>();
		// m.put("hcd", "hcd/mcsHcd.conf");
		m.put("assembly", "assembly/tcsAssembly.conf");
		m.put("both", "container/tcsContainer.conf");
		m.put("", "container/tcsContainer.conf"); // default value

		// Parse command line args for the application (app name is mcs, like
		// the sbt project)
		JContainerCmd.createContainerCmd("tcs", args, m);
	}
}
