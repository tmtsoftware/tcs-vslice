package tmt.tcs.mcs;

import static javacsw.services.pkg.JSupervisor.HaltComponent;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;
import static tmt.tcs.common.AssemblyStateActor.azFollowing;
import static tmt.tcs.common.AssemblyStateActor.azItem;
import static tmt.tcs.common.AssemblyStateActor.elFollowing;
import static tmt.tcs.common.AssemblyStateActor.elItem;
import static tmt.tcs.mcs.McsConfig.az;
import static tmt.tcs.mcs.McsConfig.el;
import static tmt.tcs.test.common.McsTestData.newAzAndElData;
import static tmt.tcs.test.common.McsTestData.testAz;

import java.util.List;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Creator;
import akka.japi.Pair;
import akka.japi.pf.ReceiveBuilder;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.loc.LocationService;
import csw.util.config.DoubleItem;
import csw.util.config.Events;
import csw.util.config.Events.EventServiceEvent;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.mcs.McsControl.GoToPosition;
import tmt.tcs.mcs.McsEventPublisher.TelemetryUpdate;
import tmt.tcs.mcs.McsFollowActor.UpdatedEventData;
import tmt.tcs.test.common.McsTestData;
import tmt.tcs.test.common.TestEnvUtil;

@SuppressWarnings("unused")
public class McsFollowActorTest extends JavaTestKit {

	/*
	 * Test event service client, subscribes to some event
	 */
	private static class TestSubscriber extends AbstractActor {
		private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

		public static Props props() {
			return Props.create(new Creator<TestSubscriber>() {
				private static final long serialVersionUID = 1L;

				@Override
				public TestSubscriber create() throws Exception {
					return new TestSubscriber();
				}
			});
		}

		// --- Actor message classes ---
		static class GetResults {
		}

		static class Results {
			public final Vector<EventServiceEvent> msgs;

			public Results(Vector<EventServiceEvent> msgs) {
				this.msgs = msgs;
			}
		}

		Vector<EventServiceEvent> msgs = new Vector<>();

		public TestSubscriber() {
			receive(ReceiveBuilder.match(SystemEvent.class, event -> {
				msgs.add(event);
				log.info("RECEIVED System " + event.info().source() + "  event: " + event);
			}).match(Events.StatusEvent.class, event -> {
				msgs.add(event);
				log.info("RECEIVED Status " + event.info().source() + " event: " + event);
			}).match(GetResults.class, t -> sender().tell(new Results(msgs), self()))
					.matchAny(t -> log.warning("Unknown message received: " + t)).build());
		}
	}

	private static ActorSystem system;
	private static LoggingAdapter logger;

	// private static double initialElevation = 90.0;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	private static ITelemetryService telemetryService;

	private static IEventService eventService;

	private static AssemblyContext assemblyContext = McsTestData.mcsTestAssemblyContext;

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public McsFollowActorTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("McsFollowActorTests");
		logger = Logging.getLogger(system, system);

		telemetryService = ITelemetryService.getTelemetryService(ITelemetryService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);

		eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
				TimeUnit.SECONDS);
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	DoubleItem initialAz = jset(McsConfig.az, 0.0);
	DoubleItem initialEl = jset(McsConfig.el, 0.0);

	TestActorRef<McsFollowActor> newFollower(Optional<ActorRef> mcsControl, Optional<ActorRef> publisher,
			Optional<ActorRef> stateActor) {

		Props props = McsFollowActor.props(assemblyContext, initialAz, initialEl, mcsControl, publisher, stateActor);
		TestActorRef<McsFollowActor> followActor = TestActorRef.create(system, props);
		expectNoMsg(duration("200 milli")); // give it time to initialize...
		return followActor;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(Optional<ActorRef> mcsHcdOpt, ActorRef... a) {
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			monitor.watch(actorRef);
			system.stop(actorRef);
			monitor.expectTerminated(actorRef, timeout.duration());
		}

		mcsHcdOpt.ifPresent(mcsHcd -> {
			monitor.watch(mcsHcd);
			mcsHcd.tell(HaltComponent, self());
			monitor.expectTerminated(mcsHcd, timeout.duration());
		});
	}

	// --- Basic tests for connectivity ----

	TestProbe fakeStateActor = new TestProbe(system);
	TestProbe fakeMcsControl = new TestProbe(system);
	TestProbe fakePublisher = new TestProbe(system);

	@Test
	public void test1() {
		TestActorRef<McsFollowActor> followActor = newFollower(Optional.of(fakeMcsControl.ref()),
				Optional.of(fakePublisher.ref()), Optional.of(fakeStateActor.ref()));

		assertEquals(followActor.underlyingActor().initialElevation, initialEl);

		fakeMcsControl.expectNoMsg(duration("1 seconds"));
		cleanup(Optional.empty(), followActor);
	}

	// --- Test set initial elevation ---

	@Test
	public void test2() {
		TestActorRef<McsFollowActor> followActor = newFollower(Optional.of(fakeMcsControl.ref()),
				Optional.of(fakePublisher.ref()), Optional.of(fakePublisher.ref()));

		assertEquals(followActor.underlyingActor().initialElevation, initialEl);

		cleanup(Optional.empty(), followActor);
	}

	@Test
	public void test3() {
		TestActorRef<McsFollowActor> followActor = newFollower(Optional.of(fakeMcsControl.ref()),
				Optional.of(fakePublisher.ref()), Optional.of(fakeStateActor.ref()));

		// set the state so that Follow Actor Receive Position Parameters
		setupState(new AssemblyState(azItem(azFollowing), elItem(elFollowing), null, null));

		followActor.tell(new UpdatedEventData(az(0), el(0), Events.getEventTime()), self());

		fakeMcsControl.expectMsgClass(GoToPosition.class);
		fakePublisher.expectMsgClass(TelemetryUpdate.class);
		cleanup(Optional.empty(), followActor);
	}

	@Test
	public void test4() {
		TestActorRef<McsFollowActor> followActor = newFollower(Optional.of(fakeMcsControl.ref()),
				Optional.of(fakePublisher.ref()), Optional.of(fakeStateActor.ref()));

		// set the state so that Follow Actor Receive Position Parameters
		setupState(new AssemblyState(azItem(azFollowing), elItem(elFollowing), null, null));

		double testEl = 10.0;

		List<UpdatedEventData> events = testAz.stream()
				.map(f -> new UpdatedEventData(az(f), el(testEl), Events.getEventTime())).collect(Collectors.toList());

		// Send the events to the follow actor
		events.forEach(f -> followActor.tell(f, self()));

		// XXX Note: The TestKit.receiveN calls below get a bit verbose due to
		// the conversion from Scala to Java collections

		List<?> telemetryEvents = scala.collection.JavaConversions
				.asJavaCollection(fakePublisher.receiveN(testAz.size())).stream().collect(Collectors.toList());

		List<?> mcsPosition = scala.collection.JavaConversions.asJavaCollection(fakeMcsControl.receiveN(testAz.size()))
				.stream().collect(Collectors.toList());

		List<Pair<Double, Double>> testdata = newAzAndElData(testEl);

		List<TelemetryUpdate> telemetryExpected = testdata.stream()
				.map(f -> new TelemetryUpdate(jset(McsConfig.az, f.first()), jset(McsConfig.el, f.second())))
				.collect(Collectors.toList());

		assertEquals(telemetryEvents, telemetryExpected);

		List<GoToPosition> positionExpected = testdata.stream()
				.map(f -> new GoToPosition(jset(McsConfig.az, f.first()), jset(McsConfig.el, f.second())))
				.collect(Collectors.toList());

		assertEquals(positionExpected, mcsPosition);

		cleanup(Optional.empty(), followActor);
	}

	void setupState(AssemblyState assemblyState) {
		// These times are important to allow time for test actors to get and
		// process the state updates when running tests
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
		system.eventStream().publish(assemblyState);
		// This is here to allow the destination to run and set its state
		expectNoMsg(FiniteDuration.apply(200, TimeUnit.MILLISECONDS));
	}

}
