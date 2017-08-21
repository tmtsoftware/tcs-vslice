package tmt.tcs.test.common;

import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import akka.japi.Pair;
import csw.services.loc.ComponentId;
import csw.services.loc.Connection;
import csw.services.pkg.Component.AssemblyInfo;
import javacsw.services.loc.JComponentType;
import javacsw.services.pkg.JComponent;
import net.logstash.logback.encoder.org.apache.commons.lang.ArrayUtils;
import tmt.tcs.common.AssemblyContext;

public class McsTestData {

	public static ComponentId mcsHcdId = new ComponentId("mcsHcd", JComponentType.HCD);

	public static AssemblyInfo mcsTestAssemblyInfo = JComponent.assemblyInfo("mcsAssembly", "tcs.mcs",
			"tmt.tcs.mcs.McsAssembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.singleton(new Connection.AkkaConnection(mcsHcdId)));

	public static AssemblyContext mcsTestAssemblyContext = new AssemblyContext(mcsTestAssemblyInfo);

	public static List<Double> testAz = Arrays.asList(ArrayUtils
			.toObject(new double[] { 0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0 }));

	public static List<Pair<Double, Double>> newAzAndElData(Double el) {
		return testAz.stream().map(az -> new Pair<>(az, el)).collect(Collectors.toList());
	}

}
