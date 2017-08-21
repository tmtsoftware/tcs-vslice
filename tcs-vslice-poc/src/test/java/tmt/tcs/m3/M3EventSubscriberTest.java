package tmt.tcs.m3;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.loc.LocationService;
import csw.util.config.DoubleItem;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.m3.M3FollowActor.StopFollowing;
import tmt.tcs.m3.M3FollowActor.UpdatedEventData;
import tmt.tcs.test.common.M3TestData;

public class M3EventSubscriberTest extends JavaTestKit {

	private static ActorSystem system;
	private static LoggingAdapter logger;

	private static Timeout timeout = new Timeout(20, TimeUnit.SECONDS);

	private static AssemblyContext assemblyContext = M3TestData.m3TestAssemblyContext;

	private static IEventService eventService;

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public M3EventSubscriberTest() {
		super(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("M3EventSubscriberTests");
		logger = Logging.getLogger(system, system);

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);
		logger.info("Got Event Service!");
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	TestActorRef<M3EventSubscriber> newTestEventSubscriber(Optional<ActorRef> followActor, IEventService eventService) {
		Props props = M3EventSubscriber.props(assemblyContext, followActor, eventService);
		TestActorRef<M3EventSubscriber> a = TestActorRef.create(system, props);
		expectNoMsg(duration("200 milli")); // give the new actor time to
											// subscribe before any test
											// publishing...
		return a;
	}

	ActorRef newEventSubscriber(Optional<ActorRef> followActor, IEventService eventService) {
		Props props = M3EventSubscriber.props(assemblyContext, followActor, eventService);
		ActorRef a = system.actorOf(props);
		expectNoMsg(duration("200 milli")); // give the new actor time to
											// subscribe before any test
											// publishing...
		return a;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(ActorRef... a) {
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			monitor.watch(actorRef);
			system.stop(actorRef);
			monitor.expectTerminated(actorRef, timeout.duration());
		}
	}

	@Test
	public void test1() {
		// should be created with no issues
		TestProbe fakeFollowActor = new TestProbe(system);

		TestActorRef<M3EventSubscriber> es = newTestEventSubscriber(Optional.of(fakeFollowActor.ref()), eventService);

		es.tell(new StopFollowing(), self());
		fakeFollowActor.expectNoMsg(duration("500 milli"));
		cleanup(es);
	}

	@Test
	public void test2() throws InterruptedException, ExecutionException, TimeoutException {
		TestProbe fakeFollowActor = new TestProbe(system);

		DoubleItem rotation = jset(M3Config.rotationDemandKey, 2.0);
		DoubleItem tilt = jset(M3Config.tiltDemandKey, 2.0);

		ActorRef es = newEventSubscriber(Optional.of(fakeFollowActor.ref()), eventService);

		IEventService tcsRtc = eventService;

		tcsRtc.publish(jadd(new SystemEvent(M3Config.positionDemandPrefix), rotation, tilt));

		UpdatedEventData msg = fakeFollowActor.expectMsgClass(duration("10 seconds"), UpdatedEventData.class);

		assertEquals(msg.rotation, rotation);
		assertEquals(msg.tilt, tilt);

		// No more messages please
		fakeFollowActor.expectNoMsg(duration("500 milli"));
		es.tell(new StopFollowing(), self());
		cleanup(es);
	}

	@Test
	public void test3() throws InterruptedException, ExecutionException, TimeoutException {
		TestProbe fakeFollowActor = new TestProbe(system);

		DoubleItem rotation = jset(M3Config.rotationDemandKey, 2.0);
		DoubleItem tilt = jset(M3Config.tiltDemandKey, 2.0);

		ActorRef es = newEventSubscriber(Optional.of(fakeFollowActor.ref()), eventService);

		IEventService tcsRtc = eventService;

		tcsRtc.publish(jadd(new SystemEvent(M3Config.offsetDemandPrefix), rotation, tilt));

		UpdatedEventData msg = fakeFollowActor.expectMsgClass(duration("10 seconds"), UpdatedEventData.class);

		assertEquals(msg.rotation, rotation);
		assertEquals(msg.tilt, tilt);

		// No more messages please
		fakeFollowActor.expectNoMsg(duration("500 milli"));
		es.tell(new StopFollowing(), self());
		cleanup(es);
	}

}
