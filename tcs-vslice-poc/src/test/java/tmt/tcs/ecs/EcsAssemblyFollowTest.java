package tmt.tcs.ecs;

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
 * This is test class for ECS which checks for Command Flow from Test Class ->
 * Assembly -> HCD It also check for Command Acceptance Status and response
 * returned
 */
public class EcsAssemblyFollowTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;
	private static String hcdName = "ecsHcd";

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;

	private static List<ActorRef> hcdActors = Collections.emptyList();

	public static final Double azValue = 1.0;
	public static final Double elValue = 2.0;

	public EcsAssemblyFollowTest() {
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

		system = ActorSystem.create("EcsAssemblyFollowTest");
		logger = Logging.getLogger(system, system);

		logger.debug("Inside EcsAssemblyTest setup");

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

		Map<String, String> configMap = Collections.singletonMap("", "hcd/ecsHcd.conf");
		ContainerCmd cmd = new ContainerCmd("ecsHcd", new String[] { "--standalone" }, configMap);
		hcdActors = cmd.getActors();
		if (hcdActors.size() == 0)
			logger.error("Inside EcsAssemblyTest Failed to create Ecs HCD");
		Thread.sleep(2000);// XXX FIXME Make sure components have time to
							// register from location service

		SequencerEnv.resolveHcd(hcdName);

	}

	/**
	 * This test case checks for follow command flow from Test Class to Assembly
	 * to HCD
	 */
	@Test
	public void test1() {
		logger.debug("Inside EcsAssemblyTest test1 Follow Command");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef EcsAssembly = newEcsAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig followSc = jadd(new SetupConfig(EcsConfig.followCK.prefix()));

		SetupConfig setAzimuthSc = jadd(new SetupConfig(EcsConfig.setAzimuthCK.prefix()),
				jset(EcsConfig.azDemandKey, azValue));

		SetupConfig setElevationSc = jadd(new SetupConfig(EcsConfig.setElevationCK.prefix()),
				jset(EcsConfig.elDemandKey, elValue));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(EcsAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("ecsFollowCommand",
				new SetupConfig(EcsConfig.initCK.prefix()), followSc, setAzimuthSc, setElevationSc);

		fakeClient.send(EcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside EcsAssemblyTest test1 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside EcsAssemblyTest test1 Command Result: " + completeMsg.details().status(0));

		assertEquals(completeMsg.overall(), AllCompleted);

	}

	Props getEcsProps(AssemblyInfo assemblyInfo, Optional<ActorRef> supervisorIn) {
		if (!supervisorIn.isPresent())
			return EcsAssembly.props(assemblyInfo, new TestProbe(system).ref());
		return EcsAssembly.props(assemblyInfo, supervisorIn.get());
	}

	ActorRef newEcsAssembly(ActorRef supervisor) {
		String componentName = "EcsAssembly";
		String componentClassName = "tmt.tcs.ecs.EcsAssembly";
		String componentPrefix = "tcs.ecs";

		ComponentId hcdId = JComponentId.componentId("ecsHcd", JComponentType.HCD);
		Component.AssemblyInfo assemblyInfo = JComponent.assemblyInfo(componentName, componentPrefix,
				componentClassName, RegisterAndTrackServices, Collections.singleton(AkkaType),
				Collections.singleton(new Connection.AkkaConnection(hcdId)));

		Props props = getEcsProps(assemblyInfo, Optional.of(supervisor));
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
		logger.debug("Inside EcsAssemblyTest teardown");

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
