package tmt.tcs.tpk;

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
import csw.services.ccs.AssemblyController.Submit;
import csw.services.ccs.CommandStatus.CommandResult;
import csw.services.loc.LocationService;
import csw.services.pkg.Component;
import csw.services.pkg.Component.AssemblyInfo;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.JComponent;
import scala.concurrent.duration.FiniteDuration;

/**
 * This is test class for MCS which checks for Command Flow from Test Class ->
 * Assembly -> HCD It also check for Command Acceptance Status and response
 * returned
 */
public class TpkAssemblyTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;

	public static final String targetValue = "testtarget";
	public static final Double raValue = 185.79;
	public static final Double decValue = 6.753333;
	public static final String frameValue = "fa5";
	public static final Double ra0Value = 5.0;
	public static final Double dec0Value = -5.0;

	public TpkAssemblyTest() {
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

		system = ActorSystem.create("TpkAssemeblyTest");
		logger = Logging.getLogger(system, system);

		logger.debug("Inside TpkAssemblyTest setup");

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

	}

	/**
	 * This test case checks for follow command flow from Test Class to TPK
	 * Assembly to Event Publisher
	 */
	@Test
	public void test1() {
		logger.debug("Inside TpkAssemblyTest test1 Follow Command: STARTS");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef tpkAssembly = newTpkAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig followSc = jadd(new SetupConfig(TpkConfig.followCK.prefix()), jset(TpkConfig.target, targetValue),
				jset(TpkConfig.ra, raValue), jset(TpkConfig.dec, decValue), jset(TpkConfig.frame, frameValue));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(tpkAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("tpkFollowCommand", followSc);

		fakeClient.send(tpkAssembly, new Submit(sca));

		expectNoMsg(duration("300 millis"));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside TpkAssemblyTest test1 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside TpkAssemblyTest test1 Command Result: " + completeMsg.details().status(0));

		assertEquals(completeMsg.overall(), AllCompleted);

		logger.debug("Inside TpkAssemblyTest test1 Follow Command: ENDS");
	}

	/**
	 * This test case checks for Offset command flow from Test Class to TPK
	 * Assembly to Event Publisher
	 */
	@Test
	public void test2() {
		logger.debug("Inside TpkAssemblyTest test2 Offset Command: STARTS");

		TestProbe fakeSupervisor = new TestProbe(system);
		ActorRef tpkAssembly = newTpkAssembly(fakeSupervisor.ref());
		TestProbe fakeClient = new TestProbe(system);

		SetupConfig offsetSc = jadd(new SetupConfig(TpkConfig.offsetCK.prefix()), jset(TpkConfig.ra, ra0Value),
				jset(TpkConfig.dec, dec0Value));

		fakeSupervisor.expectMsg(Initialized);
		fakeSupervisor.send(tpkAssembly, Running);

		SetupConfigArg sca = Configurations.createSetupConfigArg("tpkOffsetCommand", offsetSc);

		fakeClient.send(tpkAssembly, new Submit(sca));

		expectNoMsg(duration("300 millis"));

		CommandResult acceptedMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		assertEquals(acceptedMsg.overall(), Accepted);
		logger.debug("Inside TpkAssemblyTest test2 Command Accepted Result: " + acceptedMsg);

		CommandResult completeMsg = fakeClient.expectMsgClass(duration("3 seconds"), CommandResult.class);
		logger.debug("Inside TpkAssemblyTest test2 Command Result: " + completeMsg + ": completeMsg.overall(): "
				+ completeMsg.overall());

		assertEquals(completeMsg.overall(), AllCompleted);

		logger.debug("Inside TpkAssemblyTest test2 Offset Comman: ENDS");
	}

	Props getTpkProps(AssemblyInfo assemblyInfo, Optional<ActorRef> supervisorIn) {
		if (!supervisorIn.isPresent())
			return TpkAssembly.props(assemblyInfo, new TestProbe(system).ref());
		return TpkAssembly.props(assemblyInfo, supervisorIn.get());
	}

	ActorRef newTpkAssembly(ActorRef supervisor) {
		String componentName = "tpkAssembly";
		String componentClassName = "tmt.tcs.tpk.TpkAssembly";
		String componentPrefix = "tcs.tpk";

		Component.AssemblyInfo assemblyInfo = JComponent.assemblyInfo(componentName, componentPrefix,
				componentClassName, RegisterAndTrackServices, Collections.singleton(AkkaType), Collections.emptySet());

		Props props = getTpkProps(assemblyInfo, Optional.of(supervisor));
		expectNoMsg(duration("300 millis"));
		return system.actorOf(props);
	}

	/**
	 * This method is executed after test case execution It performs all the
	 * clean up tasks necessary for Assembly cleanup
	 * 
	 * @throws Exception
	 */
	@AfterClass
	public static void teardown() throws InterruptedException {
		logger.debug("Inside TpkAssemblyTest teardown");

		JavaTestKit.shutdownActorSystem(system);
		system = null;
		Thread.sleep(10000); // XXX FIXME Make sure components have time to
								// unregister from location service
	}
}
