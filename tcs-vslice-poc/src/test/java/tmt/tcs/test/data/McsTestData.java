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

public class McsTestData {

	public static ComponentId mcsHcdId = new ComponentId("mcsHcd", JComponentType.HCD);

	public static AssemblyInfo mcsTestAssemblyInfo = JComponent.assemblyInfo("mcsAssembly", "tcs.mcs",
			"tmt.tcs.mcs.McsAssembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.singleton(new Connection.AkkaConnection(mcsHcdId)));

	public static AssemblyContext mcsTestAssemblyContext = new AssemblyContext(mcsTestAssemblyInfo);

}
