package tmt.tcs.common;

import csw.services.loc.ComponentId;
import csw.services.loc.ComponentType;
import csw.services.pkg.Component.AssemblyInfo;

/*
 * This class will handle assembly properties which are common for all
 */
public class AssemblyContext {

	public final AssemblyInfo info;

	public final String componentName;
	public final String componentClassName;
	public final String componentPrefix;
	public final ComponentType componentType;
	public final String fullName;

	public final ComponentId assemblyComponentId;
	public final ComponentId hcdComponentId;

	public AssemblyContext(AssemblyInfo info) {
		this.info = info;

		componentName = info.componentName();
		componentClassName = info.componentClassName();
		componentPrefix = info.prefix();
		componentType = info.componentType();
		fullName = componentPrefix + "." + componentName;

		assemblyComponentId = new ComponentId(componentName, componentType);
		hcdComponentId = info.getConnections().get(0).componentId();
		// hcdComponentId = info.connections().head().componentId();

	}

}
