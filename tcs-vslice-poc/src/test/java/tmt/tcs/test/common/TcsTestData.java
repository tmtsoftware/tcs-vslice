package tmt.tcs.test.common;

import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import csw.services.loc.ComponentId;
import csw.services.loc.Connection;
import csw.services.pkg.Component.AssemblyInfo;
import javacsw.services.loc.JComponentType;
import javacsw.services.pkg.JComponent;
import tmt.tcs.common.AssemblyContext;

public class TcsTestData {

	public static ComponentId mcsAssemblyId = new ComponentId("mcsAssembly", JComponentType.Assembly);

	public static ComponentId ecsAssemblyId = new ComponentId("ecsAssembly", JComponentType.Assembly);

	public static ComponentId m3AssemblyId = new ComponentId("m3Assembly", JComponentType.Assembly);

	public static ComponentId tpkAssemblyId = new ComponentId("tpkAssembly", JComponentType.Assembly);

	public static AssemblyInfo tcsTestAssemblyInfo = JComponent.assemblyInfo("tcsAssembly", "tcs",
			"tmt.tcs.TcsAssembly", RegisterAndTrackServices, Collections.singleton(AkkaType), getConnections());

	public static Set<Connection> getConnections() {
		System.out.println("Inside TcsTestData");
		Set<Connection> connections = new HashSet<Connection>();

		connections.add(new Connection.AkkaConnection(mcsAssemblyId));
		connections.add(new Connection.AkkaConnection(ecsAssemblyId));
		connections.add(new Connection.AkkaConnection(m3AssemblyId));

		return connections;
	}

	public static AssemblyContext tcsTestAssemblyContext = new AssemblyContext(tcsTestAssemblyInfo);

}
