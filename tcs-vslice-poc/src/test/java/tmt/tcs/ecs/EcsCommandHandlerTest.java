package tmt.tcs.ecs;

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
import static tmt.tcs.ecs.EcsConfig.az;
import static tmt.tcs.ecs.hcd.EcsHcd.EcsMessage.GetEcsUpdateNow;

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
import tmt.tcs.ecs.hcd.EcsSimulator.EcsPosUpdate;
import tmt.tcs.test.common.EcsTestData;
import tmt.tcs.test.common.TestEnvUtil;

public class EcsCommandHandlerTest extends JavaTestKit {
	private static ActorSystem system;
	private static LoggingAdapter logger;
	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public EcsCommandHandlerTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("EcsCommandHandlerTest");
		logger = Logging.getLogger(system, system);
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(ActorRef ecsHcd, ActorRef... a) {
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			monitor.watch(actorRef);
			system.stop(actorRef);
			monitor.expectTerminated(actorRef, timeout.duration());
		}

		monitor.watch(ecsHcd);
		ecsHcd.tell(HaltComponent, self());
		monitor.expectTerminated(ecsHcd, timeout.duration());
	}

	static final AssemblyContext ac = EcsTestData.ecsTestAssemblyContext;

	void setupState(AssemblyState assemblyState) {
		// These times are important to allow time for test actors to get and
		// process the state updates when running tests
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
		system.eventStream().publish(assemblyState);
		// This is here to allow the destination to run and set its state
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
	}

	ActorRef startHcd() {
		Component.HcdInfo testInfo = JComponent.hcdInfo("ecsHcd", "tcs.ecs.hcd", "tmt.tcs.ecs.hcd.EcsHcd",
				DoNotRegister, Collections.singleton(AkkaType), FiniteDuration.create(1, TimeUnit.SECONDS));

		return Supervisor.apply(testInfo);
	}

	ActorRef newCommandHandler(ActorRef ecsHcd, Optional<ActorRef> allEventPublisher) {
		return system.actorOf(EcsCommandHandler.props(ac, Optional.of(ecsHcd), allEventPublisher));
	}

	@Test
	public void testSetAzimuthWhenNotFollowing() {

		logger.debug("Inside EcsCommandHandlerTest: testSetAzimuthWhenNotFollowing");

		ActorRef ecsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		ecsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(ecsHcd, Optional.empty());

		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn), null, null));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(EcsConfig.setAzimuthCK.prefix()), az(2.0)));

		ActorRef se2 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult errMsg = fakeAssembly.expectMsgClass(FiniteDuration.create(35, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside EcsCommandHandlerTest: testSetAzimuthWhenNotFollowing: CommandResult is: " + errMsg
				+ ": Command Status is: " + errMsg.overall());

		assertEquals(errMsg.overall(), Incomplete);

		logger.debug("Inside EcsCommandHandlerTest: testSetAzimuthWhenNotFollowing: Error Class is: "
				+ errMsg.details().getResults().get(0).first());

		assertTrue(errMsg.details().getResults().get(0).first() instanceof Invalid);

		cleanup(ecsHcd, se2, commandHandler);
	}

	@Test
	public void testFollow() throws ExecutionException, InterruptedException {

		logger.debug("Inside EcsCommandHandlerTest: testFollow");

		ActorRef ecsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		ecsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(ecsHcd, Optional.empty());

		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		// set the state so the command succeeds
		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn), null, null));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(EcsConfig.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmdResult = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.debug("Inside EcsCommandHandlerTest: testFollow: CommandResult is: " + cmdResult);

		assertEquals(cmdResult.overall(), AllCompleted);

		cleanup(ecsHcd, se, commandHandler);
	}

	@Test
	public void testFollowWithSetAzimuth() throws ExecutionException, InterruptedException {

		logger.debug("Inside EcsCommandHandlerTest: testFollowWithSetAzimuth");

		ActorRef ecsHcd = startHcd();
		TestProbe fakeAssembly = new TestProbe(system);

		ecsHcd.tell(new SubscribeLifecycleCallback(fakeAssembly.ref()), self());
		fakeAssembly.expectMsg(new LifecycleStateChanged(LifecycleRunning));

		ActorRef commandHandler = newCommandHandler(ecsHcd, Optional.empty());
		LocationService.ResolvedTcpLocation evLocation = IEventService
				.getEventServiceLocation(IEventService.defaultName, system, timeout).get();
		commandHandler.tell(evLocation, self());

		setupState(new AssemblyState(azItem(azDrivePowerOn), elItem(elDrivePowerOn), null, null));

		SetupConfigArg sca = Configurations.createSetupConfigArg("testobsId", sc(EcsConfig.followCK.prefix()));
		ActorRef se = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));
		CommandResult cmsResult1 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside EcsCommandHandlerTest: testFollowWithSetAzimuth: Command 1 Result is: " + cmsResult1);

		double testAzimuth = 20.0;
		sca = Configurations.createSetupConfigArg("testobsId",
				jadd(sc(EcsConfig.setAzimuthCK.prefix()), jset(EcsConfig.azDemandKey, testAzimuth)));
		ActorRef se3 = system.actorOf(SequentialExecutor.props(commandHandler, sca, Optional.of(fakeAssembly.ref())));

		CommandResult cmsResult2 = fakeAssembly.expectMsgClass(FiniteDuration.create(10, TimeUnit.SECONDS),
				CommandResult.class);

		logger.info("Inside EcsCommandHandlerTest: testFollowWithSetAzimuth: Command 2 Result is: " + cmsResult2);

		fakeAssembly.send(ecsHcd, GetEcsUpdateNow);
		EcsPosUpdate upd = fakeAssembly.expectMsgClass(EcsPosUpdate.class);
		logger.info("Inside EcsCommandHandlerTest: testFollowWithSetAzimuth: EcsPosUpdate is: " + upd);
		assertTrue(upd.azPosition == testAzimuth);

		cleanup(ecsHcd, commandHandler, se, se3);
	}

}
