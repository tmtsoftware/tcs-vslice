package tmt.tcs.test.data;

import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;

import java.util.Collections;

import csw.services.loc.ComponentId;
import csw.services.loc.Connection;
import csw.services.pkg.Component.AssemblyInfo;
import javacsw.services.loc.JComponentType;
import javacsw.services.pkg.JComponent;
import tmt.tcs.common.AssemblyContext;

public class M3TestData {

	public static ComponentId m3HcdId = new ComponentId("m3Hcd", JComponentType.HCD);

	public static AssemblyInfo m3TestAssemblyInfo = JComponent.assemblyInfo("m3Assembly", "tcs.m3",
			"tmt.tcs.m3.M3Assembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.singleton(new Connection.AkkaConnection(m3HcdId)));

	public static AssemblyContext m3TestAssemblyContext = new AssemblyContext(m3TestAssemblyInfo);

}
