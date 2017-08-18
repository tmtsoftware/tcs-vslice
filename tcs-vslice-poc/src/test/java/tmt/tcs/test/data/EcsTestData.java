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

public class EcsTestData {

	public static ComponentId ecsHcdId = new ComponentId("ecsHcd", JComponentType.HCD);

	public static AssemblyInfo ecsTestAssemblyInfo = JComponent.assemblyInfo("ecsAssembly", "tcs.ecs",
			"tmt.tcs.ecs.EcsAssembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.singleton(new Connection.AkkaConnection(ecsHcdId)));

	public static AssemblyContext ecsTestAssemblyContext = new AssemblyContext(ecsTestAssemblyInfo);

}
