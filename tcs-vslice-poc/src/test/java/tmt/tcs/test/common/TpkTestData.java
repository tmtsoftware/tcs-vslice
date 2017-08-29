package tmt.tcs.test.common;

import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;

import java.util.Collections;

import csw.services.pkg.Component.AssemblyInfo;
import javacsw.services.pkg.JComponent;
import tmt.tcs.common.AssemblyContext;

public class TpkTestData {

	public static AssemblyInfo tpkTestAssemblyInfo = JComponent.assemblyInfo("tpkAssembly", "tcs.tpk",
			"tmt.tcs.tpk.TpkAssembly", RegisterAndTrackServices, Collections.singleton(AkkaType),
			Collections.emptySet());

	public static AssemblyContext tpkTestAssemblyContext = new AssemblyContext(tpkTestAssemblyInfo);

}
