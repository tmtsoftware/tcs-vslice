package tmt.tcs.tcs;

import static javacsw.services.ccs.JCommandStatus.Accepted;
import static javacsw.services.ccs.JCommandStatus.AllCompleted;
import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.apps.containerCmd.ContainerCmd;
import csw.services.ccs.AssemblyController.Submit;
import csw.services.ccs.CommandStatus.CommandResult;
import csw.services.loc.ComponentId;
import csw.services.loc.Connection;
import csw.services.loc.LocationService;
import csw.services.pkg.Component;
import csw.services.pkg.Component.AssemblyInfo;
import csw.services.sequencer.SequencerEnv;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.events.IEventService;
import javacsw.services.loc.JComponentId;
import javacsw.services.loc.JComponentType;
import javacsw.services.pkg.JComponent;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.TcsAssembly;
import tmt.tcs.TcsConfig;

/**
 * This is test class for MCS which checks for Command Flow from Test Class ->
 * Assembly -> HCD It also check for Command Acceptance Status and response
 * returned
 */
public class TcsTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;
	private static String mcsHcdName = "mcsHcd";
	private static String ecsHcdName = "ecsHcd";
	private static String m3HcdName = "m3Hcd";

	private static List<ActorRef> mcsRefActors = Collections.emptyList();
	private static List<ActorRef> ecsRefActors = Collections.emptyList();
	private static List<ActorRef> m3RefActors = Collections.emptyList();

	private static List<ActorRef> mcsAssemblyRefActors = Collections.emptyList();
	private static List<ActorRef> ecsAssemblyRefActors = Collections.emptyList();
	private static List<ActorRef> m3AssemblyRefActors = Collections.emptyList();

	public static final String targetValue = "Test";
	public static final Double raValue = 0.1;
	public static final Double decValue = 0.2;
	public static final String frameValue = "fa5";

	public static final Double raOffsetValue = 1.0;
	public static final Double decOffsetValue = 2.0;

	public TcsTest() {
		super(system);
	}

	/**
	 * This method is executed before test case execution It performs all the
	 * start up tasks necessary for Assembly initialization
	 * 
	 * @throws Exception
	 */
	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();

		system = ActorSystem.create("tcsAssembly");
		logger = Logging.getLogger(system, system);

		logger.debug("Inside TcsTest setup");

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

		Map<String, String> mcsConfigMap = Collections.singletonMap("", "hcd/mcsHcd.conf");
		ContainerCmd mcsCmd = new ContainerCmd("mcsHcd", new String[] { "--standalone" }, mcsConfigMap);
		mcsRefActors = mcsCmd.getActors();
		if (mcsRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Mcs HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		Map<String, String> mcsAssemblyConfigMap = Collections.singletonMap("", "assembly/mcsAssembly.conf");
		ContainerCmd mcsAssemblyCmd = new ContainerCmd("mcsAssembly", new String[] { "--standalone" },
				mcsAssemblyConfigMap);
		mcsAssemblyRefActors = mcsAssemblyCmd.getActors();
		if (mcsAssemblyRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Mcs Assembly");
		Thread.sleep(2000);

		Map<String, String> ecsConfigMap = Collections.singletonMap("", "hcd/ecsHcd.conf");
		ContainerCmd ecsCmd = new ContainerCmd("ecsHcd", new String[] { "--standalone" }, ecsConfigMap);
		ecsRefActors = ecsCmd.getActors();
		if (ecsRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Ecs HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		Map<String, String> ecsAssemblyConfigMap = Collections.singletonMap("", "assembly/ecsAssembly.conf");
		ContainerCmd ecsAssemblyCmd = new ContainerCmd("ecsAssembly", new String[] { "--standalone" },
				ecsAssemblyConfigMap);
		ecsAssemblyRefActors = ecsAssemblyCmd.getActors();
		if (ecsAssemblyRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Ecs Assembly");
		Thread.sleep(2000);

		Map<String, String> m3ConfigMap = Collections.singletonMap("", "hcd/m3Hcd.conf");
		ContainerCmd m3Cmd = new ContainerCmd("m3Hcd", new String[] { "--standalone" }, m3ConfigMap);
		m3RefActors = m3Cmd.getActors();
		if (m3RefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create M3 HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		Map<String, String> m3AssemblyConfigMap = Collections.singletonMap("", "assembly/m3Assembly.conf");
		ContainerCmd m3AssemblyCmd = new ContainerCmd("m3Assembly", new String[] { "--standalone" },
				m3AssemblyConfigMap);
		m3AssemblyRefActors = m3AssemblyCmd.getActors();
		if (m3AssemblyRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create M3 Assembly");
		Thread.sleep(2000);

		SequencerEnv.resolveHcd(mcsHcdName);
		SequencerEnv.resolveHcd(ecsHcdName);
		SequencerEnv.resolveHcd(m3HcdName);

	}

	/**
	 * This test case checks for offset command flow from Test Class to TCS
	 * Assembly to MCS Assembly
	 */
	@Test
	public void test1() {
		logger.debug("Inside TcsTest test1 Position Command");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef tcsAssembly = newTcsAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig positionSc = jadd(new SetupConfig(TcsConfig.positionCK.prefix()),
				jset(TcsConfig.target, targetValue), jset(TcsConfig.ra, decValue), jset(TcsConfig.dec, decValue),
				jset(TcsConfig.frame, frameValue));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(tcsAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("tcsPositionCommand", positionSc);

		fakeClient.send(tcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside TcsTest test1 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside TcsTest test1 Command Result: " + completeMsg + ": completeMsg.overall(): "
				+ completeMsg.overall());

		assertEquals(completeMsg.overall(), AllCompleted);

	}

	/**
	 * This test case checks for follow command flow from Test Class to TCS
	 * Assembly to MCS Assembly
	 */
	@Test
	public void test2() {
		logger.debug("Inside TcsTest test2 Offset Command");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef tcsAssembly = newTcsAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig offsetSc = jadd(new SetupConfig(TcsConfig.offsetCK.prefix()), jset(TcsConfig.ra, raOffsetValue),
				jset(TcsConfig.dec, decOffsetValue));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(tcsAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("tcsOffsetCommand", offsetSc);

		fakeClient.send(tcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside TcsTest test2 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside TcsTest test2 Command Result: " + completeMsg.details().status(0));

		assertEquals(completeMsg.overall(), AllCompleted);

	}

	Props getTcsProps(AssemblyInfo assemblyInfo, Optional<ActorRef> supervisorIn) {
		if (!supervisorIn.isPresent())
			return TcsAssembly.props(assemblyInfo, new TestProbe(system).ref());
		return TcsAssembly.props(assemblyInfo, supervisorIn.get());
	}

	ActorRef newTcsAssembly(ActorRef supervisor) {
		String componentName = "tcsAssembly";
		String componentClassName = "tmt.tcs.TcsAssembly";
		String componentPrefix = "tcs";

		ComponentId mcsAssembly = JComponentId.componentId("mcsAssembly", JComponentType.Assembly);
		ComponentId ecsAssembly = JComponentId.componentId("ecsAssembly", JComponentType.Assembly);
		ComponentId m3Assembly = JComponentId.componentId("m3Assembly", JComponentType.Assembly);

		Set<Connection> connections = new HashSet<Connection>();

		connections.add(new Connection.AkkaConnection(mcsAssembly));
		connections.add(new Connection.AkkaConnection(ecsAssembly));
		connections.add(new Connection.AkkaConnection(m3Assembly));

		Component.AssemblyInfo assemblyInfo = JComponent.assemblyInfo(componentName, componentPrefix,
				componentClassName, RegisterAndTrackServices, Collections.singleton(AkkaType), connections);

		Props props = getTcsProps(assemblyInfo, Optional.of(supervisor));
		expectNoMsg(duration("300 millis"));
		return system.actorOf(props);
	}

	/**
	 * This method is executed after test case execution It performs all the
	 * clean up tasks necessary for Assembly and HCD cleanup
	 * 
	 * @throws Exception
	 */
	@AfterClass
	public static void teardown() throws InterruptedException {
		logger.debug("Inside TcsTest teardown");

		JavaTestKit.shutdownActorSystem(system);
		system = null;
		Thread.sleep(10000); // XXX FIXME Make sure components have time to
								// unregister from location service
	}
}
