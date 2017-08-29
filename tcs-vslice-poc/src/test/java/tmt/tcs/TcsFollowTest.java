package tmt.tcs;

import static javacsw.services.ccs.JCommandStatus.Accepted;
import static javacsw.services.ccs.JCommandStatus.AllCompleted;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.apps.containerCmd.ContainerCmd;
import csw.services.ccs.AssemblyController.Submit;
import csw.services.ccs.CommandStatus.CommandResult;
import csw.services.loc.LocationService;
import csw.services.sequencer.SequencerEnv;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.JSupervisor;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.test.common.EcsTestData;
import tmt.tcs.test.common.M3TestData;
import tmt.tcs.test.common.McsTestData;
import tmt.tcs.test.common.TcsTestData;
import tmt.tcs.test.common.TpkTestData;

/**
 * This is test class for MCS which checks for Command Flow from Test Class ->
 * Assembly -> HCD It also check for Command Acceptance Status and response
 * returned
 */
public class TcsFollowTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;

	private static ActorRef tcsAssembly;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;
	private static String mcsHcdName = "mcsHcd";
	private static String ecsHcdName = "ecsHcd";
	private static String m3HcdName = "m3Hcd";

	private static List<ActorRef> mcsRefActors = Collections.emptyList();
	private static List<ActorRef> ecsRefActors = Collections.emptyList();
	private static List<ActorRef> m3RefActors = Collections.emptyList();

	public static final String targetValue = "Test";
	public static final Double raValue = 185.79;
	public static final Double decValue = 6.753333;
	public static final String frameValue = "fa5";

	public TcsFollowTest() {
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

		System.out.println("Inside TcsTest setup");

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

		Map<String, String> mcsConfigMap = Collections.singletonMap("", "hcd/mcsHcd.conf");
		ContainerCmd mcsCmd = new ContainerCmd("mcsHcd", new String[] { "--standalone" }, mcsConfigMap);
		mcsRefActors = mcsCmd.getActors();
		if (mcsRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Mcs HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		Map<String, String> ecsConfigMap = Collections.singletonMap("", "hcd/ecsHcd.conf");
		ContainerCmd ecsCmd = new ContainerCmd("ecsHcd", new String[] { "--standalone" }, ecsConfigMap);
		ecsRefActors = ecsCmd.getActors();
		if (ecsRefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create Ecs HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		Map<String, String> m3ConfigMap = Collections.singletonMap("", "hcd/m3Hcd.conf");
		ContainerCmd m3Cmd = new ContainerCmd("m3Hcd", new String[] { "--standalone" }, m3ConfigMap);
		m3RefActors = m3Cmd.getActors();
		if (m3RefActors.size() == 0)
			logger.error("Inside TcsTest Failed to create M3 HCD");
		Thread.sleep(2000); // XXX FIXME Make sure components have time to
							// register from location service

		JSupervisor.create(McsTestData.mcsTestAssemblyContext.info);
		JSupervisor.create(EcsTestData.ecsTestAssemblyContext.info);
		JSupervisor.create(M3TestData.m3TestAssemblyContext.info);
		JSupervisor.create(TpkTestData.tpkTestAssemblyContext.info);

		tcsAssembly = JSupervisor.create(TcsTestData.tcsTestAssemblyContext.info);

		SequencerEnv.resolveHcd(mcsHcdName);
		SequencerEnv.resolveHcd(ecsHcdName);
		SequencerEnv.resolveHcd(m3HcdName);

	}

	/**
	 * This test case checks for follow command flow from Test Class to TCS
	 * Assembly to MCS, ECS and M3 Assembly
	 */
	@Test
	public void test1() {
		logger.debug("Inside TcsTest test1 Position Command");

		TestProbe fakeClient = new TestProbe(system);

		SetupConfig positionSc = jadd(new SetupConfig(TcsConfig.positionCK.prefix()),
				jset(TcsConfig.target, targetValue), jset(TcsConfig.ra, decValue), jset(TcsConfig.dec, decValue),
				jset(TcsConfig.frame, frameValue));

		SetupConfigArg sca = Configurations.createSetupConfigArg("tcsPositionCommand", positionSc);

		fakeClient.send(tcsAssembly, new Submit(sca));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside TcsTest test1 Command Accepted Result: " + acceptedMsg);

		expectNoMsg(duration("300 millis"));

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside TcsTest test1 Command Result: " + completeMsg + ": completeMsg.overall(): "
				+ completeMsg.overall());

		assertEquals(completeMsg.overall(), AllCompleted);

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
