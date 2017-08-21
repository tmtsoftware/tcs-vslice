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

public class M3TestData {

	public static ComponentId m3HcdId = new ComponentId("m3Hcd", JComponentType.HCD);

	public static AssemblyInfo m3TestAssemblyInfo = JComponent.assemblyInfo("m3Assembly", "tcs.m3",
			"tmt.tcs.m3.M3Assembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.singleton(new Connection.AkkaConnection(m3HcdId)));

	public static AssemblyContext m3TestAssemblyContext = new AssemblyContext(m3TestAssemblyInfo);
	
	public static List<Double> testRotation = Arrays.asList(ArrayUtils
			.toObject(new double[] { 0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0, 50.0, 55.0, 60.0 }));

	public static List<Pair<Double, Double>> newRotationAndTiltData(Double el) {
		return testRotation.stream().map(az -> new Pair<>(az, el)).collect(Collectors.toList());
	}

}
