package tmt.tcs.mcs;

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
import static tmt.tcs.common.AssemblyStateActor.azDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.elDrivePowerOn;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.mcs.McsConfig.az;
import static tmt.tcs.mcs.hcd.McsHcd.McsMessage.GetMcsUpdateNow;

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
import tmt.tcs.mcs.hcd.McsSimulator.McsPosUpdate;
import tmt.tcs.test.common.McsTestData;
import tmt.tcs.test.common.TestEnvUtil;

public class McsCommandHandlerTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;
	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public McsCommandHandlerTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("McsCommandHandlerTest");
		logger = Logging.getLogger(system, system);
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(ActorRef mcsHcd, ActorRef... a) {
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			monitor.watch(actorRef);
			system.stop(actorRef);
			monitor.expectTerminated(actorRef, timeout.duration());
		}

		monitor.watch(mcsHcd);
		mcsHcd.tell(HaltComponent, self());
		monitor.expectTerminated(mcsHcd, timeout.duration());
	}

	static final AssemblyContext ac = McsTestData.mcsTestAssemblyContext;

	void setupState(AssemblyState assemblyState) {
		// These times are important to allow time for test actors to get and
		// process the state updates when running tests
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
		system.eventStream().publish(assemblyState);
		// This is here to allow the destination to run and set its state
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
	}

	ActorRef startHcd() {
		Component.HcdInfo testInfo = JComponent.hcdInfo("mcsHcd", "tcs.mcs.hcd", "tmt.tcs.mcs.hcd.McsHcd",
				DoNotRegister, Collections.singleton(AkkaType), FiniteDuration.create(1, TimeUnit.SECONDS));

		return Supervisor.apply(testInfo);
	}

	ActorRef newCommandHandler(ActorRef mcsHcd, Optional<ActorRef> allEventPublisher) {
		return system.actorOf(McsCommandHandler.props(ac, Optional.of(mcsHcd), allEventPublisher));
	}

	@Test
	public void testSetAzimuthWhenNotFollowing() {

		logger.debug("Inside McsCommandHandler: testSetAzimuthWhenNotFollowing");

		ActorRef mcsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		mcsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(mcsHcd, Optional.empty());

		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(McsConfig.setAzimuthCK.prefix()), az(2.0)));

		ActorRef se2 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside McsCommandHandler: testSetAzimuthWhenNotFollowing: CommandResult is: " + errMsg
				+ ": Command Status is: " + errMsg.overall());

		assertEquals(errMsg.overall(), Incomplete);

		logger.debug("Inside McsCommandHandler: testSetAzimuthWhenNotFollowing: Error Class is: "
				+ errMsg.details().getResults().get(0).first());

		assertTrue(errMsg.details().getResults().get(0).first() instanceof Invalid);

		cleanup(mcsHcd, se2, commandHandler);
	}

	@Test
	public void testFollow() throws ExecutionException, InterruptedException {

		logger.debug("Inside McsCommandHandler: testFollow");

		ActorRef mcsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		mcsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(mcsHcd, Optional.empty());

		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		// set the state so the command succeeds
		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(McsConfig.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmdResult = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside McsCommandHandler: testFollow: CommandResult is: " + cmdResult);

		assertEquals(cmdResult.overall(), AllCompleted);

		cleanup(mcsHcd, se, commandHandler);
	}

	@Test
	public void testFollowWithSetAzimuth() throws ExecutionException, InterruptedException {

		logger.debug("Inside McsCommandHandler: testFollowWithSetAzimuth");

		ActorRef mcsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		mcsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(mcsHcd, Optional.empty());
		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn)));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(McsConfig.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));
		CommandResult cmsResult1 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside McsCommandHandler: testFollowWithSetAzimuth: Command 1 Result is: " + cmsResult1);

		double testAzimuth = 20.0;
		sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(McsConfig.setAzimuthCK.prefix()), jset(McsConfig.azDemandKey, testAzimuth)));
		ActorRef se3 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmsResult2 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside McsCommandHandler: testFollowWithSetAzimuth: Command 2 Result is: " + cmsResult2);

		fakeAssembly.send(mcsHcd, GetMcsUpdateNow);
		McsPosUpdate upd = fakeAssembly.expectMsgClass(McsPosUpdate.class);
		logger.info("Inside McsCommandHandler: testFollowWithSetAzimuth: McsPosUpdate is: " + upd);
		assertTrue(upd.azPosition == testAzimuth);

		cleanup(mcsHcd, commandHandler, se, se3);
	}

}
