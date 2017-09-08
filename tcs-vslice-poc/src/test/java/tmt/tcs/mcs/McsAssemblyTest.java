package tmt.tcs.mcs;

import static javacsw.services.ccs.JCommandStatus.Accepted;
import static javacsw.services.ccs.JCommandStatus.AllCompleted;
import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.RegisterAndTrackServices;
import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * This is test class for MCS which checks for Command Flow from Test Class ->
 * Assembly -> HCD It also check for Command Acceptance Status and response
 * returned
 */
public class McsAssemblyTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;
	private static String hcdName = "mcsHcd";

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;

	private static List<ActorRef> hcdActors = Collections.emptyList();

	public static final Double azValue = 1.0;
	public static final Double elValue = 2.0;
	public static final Double timeValue = 3.0;
	public static final Double offsetAzValue = 0.1;
	public static final Double offsetElValue = 0.2;

	public McsAssemblyTest() {
		super(system);
	}

	/**
	 * This method is executed before test case execution It performs all the
	 * start up tasks necessary for Assembly and HCD initialization
	 * 
	 * @throws Exception
	 */
	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();

		system = ActorSystem.create("mcsHcd");
		logger = Logging.getLogger(system, system);

		logger.debug("Inside McsAssemblyTest setup");

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

		Map<String, String> configMap = Collections.singletonMap("", "hcd/mcsHcd.conf");
		ContainerCmd cmd = new ContainerCmd("mcsHcd", new String[] { "--standalone" }, configMap);
		hcdActors = cmd.getActors();
		if (hcdActors.size() == 0)
			logger.error("Inside McsAssemblyTest Failed to create Mcs HCD");
		Thread.sleep(2000);// XXX FIXME Make sure components have time to
							// register from location service

		SequencerEnv.resolveHcd(hcdName);

	}

	/**
	 * This test case checks for offset command flow from Test Class to Assembly
	 * to HCD
	 */
	@Test
	public void test1() {
		logger.debug("Inside McsAssemblyTest test1 Offset Command");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef mcsAssembly = newMcsAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig offsetSc = jadd(new SetupConfig(McsConfig.offsetCK.prefix()),
				jset(McsConfig.azDemandKey, offsetAzValue), jset(McsConfig.elDemandKey, offsetElValue));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(mcsAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("mcsOffsetCommand",
				new SetupConfig(McsConfig.initCK.prefix()), offsetSc);

		fakeClient.send(mcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside McsAssemblyTest test1 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside McsAssemblyTest test1 Command Result: " + completeMsg + ": completeMsg.overall(): "
				+ completeMsg.overall());

		assertEquals(completeMsg.overall(), AllCompleted);

	}

	/**
	 * This test case checks for follow command flow from Test Class to Assembly
	 * to HCD
	 */
	@Test
	public void test2() {
		logger.debug("Inside McsAssemblyTest test2 Follow Command");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef mcsAssembly = newMcsAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig followSc = jadd(new SetupConfig(McsConfig.followCK.prefix()));

		SetupConfig setAzimuthSc = jadd(new SetupConfig(McsConfig.setAzimuthCK.prefix()),
				jset(McsConfig.azDemandKey, 4.0));

		SetupConfig setElevationSc = jadd(new SetupConfig(McsConfig.setElevationCK.prefix()),
				jset(McsConfig.elDemandKey, 5.0));

		SetupConfig setAzimuthSc2 = jadd(new SetupConfig(McsConfig.setAzimuthCK.prefix()),
				jset(McsConfig.azDemandKey, 7.0));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(mcsAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("mcsFollowCommand",
				new SetupConfig(McsConfig.initCK.prefix()), followSc, setAzimuthSc, setElevationSc, setAzimuthSc2);

		fakeClient.send(mcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside McsAssemblyTest test2 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside McsAssemblyTest test2 Command Result: " + completeMsg.details().status(0));

		assertEquals(completeMsg.overall(), AllCompleted);

	}

	Props getMcsProps(AssemblyInfo assemblyInfo, Optional<ActorRef> supervisorIn) {
		if (!supervisorIn.isPresent())
			return McsAssembly.props(assemblyInfo, new TestProbe(system).ref());
		return McsAssembly.props(assemblyInfo, supervisorIn.get());
	}

	ActorRef newMcsAssembly(ActorRef supervisor) {
		String componentName = "mcsAssembly";
		String componentClassName = "tmt.tcs.mcs.McsAssembly";
		String componentPrefix = "tcs.mcs";

		ComponentId hcdId = JComponentId.componentId("mcsHcd", JComponentType.HCD);
		Component.AssemblyInfo assemblyInfo = JComponent.assemblyInfo(componentName, componentPrefix,
				componentClassName, RegisterAndTrackServices, Collections.singleton(AkkaType),
				Collections.singleton(new Connection.AkkaConnection(hcdId)));

		Props props = getMcsProps(assemblyInfo, Optional.of(supervisor));
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
		logger.debug("Inside McsAssemblyTest teardown");

		hcdActors.forEach(actorRef -> {
			TestProbe probe = new TestProbe(system);
			probe.watch(actorRef);
			actorRef.tell(HaltComponent, ActorRef.noSender());
			probe.expectTerminated(actorRef, timeout.duration());
		});
		JavaTestKit.shutdownActorSystem(system);
		system = null;
		Thread.sleep(10000); // XXX FIXME Make sure components have time to
								// unregister from location service
	}
}
