package tmt.tcs.m3;

import static javacsw.services.ccs.JCommandStatus.AllCompleted;
import static javacsw.services.ccs.JCommandStatus.Incomplete;
import static javacsw.services.loc.JConnectionType.AkkaType;
import static javacsw.services.pkg.JComponent.DoNotRegister;
import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.services.pkg.JSupervisor.LifecycleRunning;
import static javacsw.util.config.JConfigDSL.sc;
import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static tmt.tcs.common.AssemblyStateActor.rotationDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.rotationItem;
import static tmt.tcs.common.AssemblyStateActor.tiltDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.tiltItem;
import static tmt.tcs.m3.M3Config.rotation;
import static tmt.tcs.m3.hcd.M3Hcd.M3Message.GetM3UpdateNow;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.ccs.CommandStatus.CommandResult;
import csw.services.ccs.CommandStatus.Invalid;
import csw.services.ccs.SequentialExecutor;
import csw.services.loc.LocationService;
import csw.services.pkg.Component;
import csw.services.pkg.Supervisor;
import csw.services.pkg.SupervisorExternal.LifecycleStateChanged;
import csw.services.pkg.SupervisorExternal.SubscribeLifecycleCallback;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.JComponent;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.m3.hcd.M3Simulator.M3PosUpdate;
import tmt.tcs.test.common.M3TestData;
import tmt.tcs.test.common.TestEnvUtil;

public class M3CommandHandlerTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;
	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public M3CommandHandlerTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("M3CommandHandlerTest");
		logger = Logging.getLogger(system, system);
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(ActorRef m3Hcd, ActorRef... a) {
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			monitor.watch(actorRef);
			system.stop(actorRef);
			monitor.expectTerminated(actorRef, timeout.duration());
		}

		monitor.watch(m3Hcd);
		m3Hcd.tell(HaltComponent, self());
		monitor.expectTerminated(m3Hcd, timeout.duration());
	}

	static final AssemblyContext ac = M3TestData.m3TestAssemblyContext;

	void setupState(AssemblyState assemblyState) {
		// These times are important to allow time for test actors to get and
		// process the state updates when running tests
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
		system.eventStream().publish(assemblyState);
		// This is here to allow the destination to run and set its state
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
	}

	ActorRef startHcd() {
		Component.HcdInfo testInfo = JComponent.hcdInfo("m3Hcd", "tcs.m3.hcd", "tmt.tcs.m3.hcd.M3Hcd", DoNotRegister,
				Collections.singleton(AkkaType), FiniteDuration.create(1, TimeUnit.SECONDS));

		return Supervisor.apply(testInfo);
	}

	ActorRef newCommandHandler(ActorRef m3Hcd, Optional<ActorRef> allEventPublisher) {
		return system.actorOf(M3CommandHandler.props(ac, Optional.of(m3Hcd), allEventPublisher));
	}

	@Test
	public void testSetRotationWhenNotFollowing() {

		logger.debug("Inside M3CommandHandlerTest: testSetRotationWhenNotFollowing");

		ActorRef m3Hcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		m3Hcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(m3Hcd, Optional.empty());

		setupState(new AssemblyState(null, null, rotationItem(rotationDrivePowerOn), tiltItem(tiltDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(M3Config.setRotationCK.prefix()), rotation(2.0)));

		ActorRef se2 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside M3CommandHandlerTest: testSetRotationWhenNotFollowing: CommandResult is: " + errMsg
				+ ": Command Status is: " + errMsg.overall());

		assertEquals(errMsg.overall(), Incomplete);

		logger.debug("Inside M3CommandHandlerTest: testSetRotationWhenNotFollowing: Error Class is: "
				+ errMsg.details().getResults().get(0).first());

		assertTrue(errMsg.details().getResults().get(0).first() instanceof Invalid);

		cleanup(m3Hcd, se2, commandHandler);
	}

	@Test
	public void testFollow() throws ExecutionException, InterruptedException {

		logger.debug("Inside M3CommandHandlerTest: testFollow");

		ActorRef m3Hcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		m3Hcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(m3Hcd, Optional.empty());

		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		// set the state so the command succeeds
		setupState(new AssemblyState(null, null, rotationItem(rotationDrivePowerOn), tiltItem(tiltDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(M3Config.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmdResult = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside M3CommandHandlerTest: testFollow: CommandResult is: " + cmdResult);

		assertEquals(cmdResult.overall(), AllCompleted);

		cleanup(m3Hcd, se, commandHandler);
	}

	@Test
	public void testFollowWithSetRotation() throws ExecutionException, InterruptedException {

		logger.debug("Inside M3CommandHandlerTest: testFollowWithSetRotation");

		ActorRef m3Hcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		m3Hcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(m3Hcd, Optional.empty());
		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		setupState(new AssemblyState(null, null, rotationItem(rotationDrivePowerOn), tiltItem(tiltDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(M3Config.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));
		CommandResult cmsResult1 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside M3CommandHandlerTest: testFollowWithSetRotation: Command 1 Result is: " + cmsResult1);

		double testRotation = 20.0;
		sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(M3Config.setRotationCK.prefix()), jset(M3Config.rotationDemandKey, testRotation)));
		ActorRef se3 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmsResult2 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside M3CommandHandlerTest: testFollowWithSetRotation: Command 2 Result is: " + cmsResult2);

		fakeAssembly.send(m3Hcd, GetM3UpdateNow);
		M3PosUpdate upd = fakeAssembly.expectMsgClass(M3PosUpdate.class);
		logger.info("Inside M3CommandHandlerTest: testFollowWithSetRotation: M3PosUpdate is: " + upd);
		assertTrue(upd.rotationPosition == testRotation);

		cleanup(m3Hcd, commandHandler, se, se3);
	}

}
