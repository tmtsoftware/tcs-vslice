package tmt.tcs.m3;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;
import static tmt.tcs.common.AssemblyStateActor.rotationFollowing;
import static tmt.tcs.common.AssemblyStateActor.rotationItem;
import static tmt.tcs.common.AssemblyStateActor.tiltFollowing;
import static tmt.tcs.common.AssemblyStateActor.tiltItem;
import static tmt.tcs.m3.M3Config.rotation;
import static tmt.tcs.m3.M3Config.tilt;
import static tmt.tcs.test.common.M3TestData.newRotationAndTiltData;
import static tmt.tcs.test.common.M3TestData.testRotation;

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
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.loc.LocationService;
import csw.util.config.DoubleItem;
import csw.util.config.Events;
import csw.util.config.Events.EventServiceEvent;
import csw.util.config.Events.StatusEvent;
import csw.util.config.Events.SystemEvent;
import javacsw.services.events.IEventService;
import javacsw.services.events.ITelemetryService;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.common.AssemblyContext;
import tmt.tcs.common.AssemblyStateActor.AssemblyState;
import tmt.tcs.m3.M3FollowActor.UpdatedEventData;
import tmt.tcs.test.common.M3TestData;
import tmt.tcs.test.common.TestEnvUtil;

public class M3EventPublisherTest extends JavaTestKit {

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

			@Override
			public String toString() {
				return "Results [msgs=" + msgs + "]";
			}

		}

		Vector<EventServiceEvent> msgs = new Vector<>();

		public TestSubscriber() {
			receive(ReceiveBuilder.match(SystemEvent.class, event -> {
				msgs.add(event);
				log.info("Inside M3EventPublisherTest TestSubscriber: Received System: " + event.info().source()
						+ "  event: " + event);
			}).match(StatusEvent.class, event -> {
				msgs.add(event);
				log.info("Inside M3EventPublisherTest TestSubscriber: Received Status: " + event.info().source()
						+ " event: " + event);
			}).match(GetResults.class, t -> sender().tell(new Results(msgs), self()))
					.matchAny(t -> log
							.warning("Inside M3EventPublisherTest TestSubscriber: Unknown message received: " + t))
					.build());
		}
	}

	private static ActorSystem system;
	private static LoggingAdapter logger;

	// private static double initialElevation = 90.0;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	private static AssemblyContext assemblyContext = M3TestData.m3TestAssemblyContext;

	private static ITelemetryService telemetryService;

	private static IEventService eventService;

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public M3EventPublisherTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("M3EventPublishTest");
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

	DoubleItem initialRotation = jset(M3Config.rotation, 0.0);
	DoubleItem initialTilt = jset(M3Config.tilt, 0.0);

	ActorRef newTestFollower(Optional<ActorRef> m3Control, Optional<ActorRef> publisher,
			Optional<ActorRef> stateActor) {
		Props props = M3FollowActor.props(assemblyContext, initialRotation, initialTilt, m3Control, publisher,
				stateActor);
		ActorRef a = system.actorOf(props);
		expectNoMsg(duration("200 millis"));
		return a;
	}

	ActorRef newTestPublisher(Optional<IEventService> eventService, Optional<ITelemetryService> telemetryService) {
		Props testEventPublisherProps = M3EventPublisher.props(assemblyContext, eventService, telemetryService);
		ActorRef a = system.actorOf(testEventPublisherProps);
		expectNoMsg(duration("200 millis"));
		return a;
	}

	// Stop any actors created for a test to avoid conflict with other tests
	private void cleanup(ActorRef... a) {
		@SuppressWarnings("unused")
		TestProbe monitor = new TestProbe(system);
		for (ActorRef actorRef : a) {
			system.stop(actorRef);
		}
	}

	@Test
	public void test1() {
		logger.debug("Inside M3EventPublisherTest test1: STARTS");
		ActorRef publisher = newTestPublisher(Optional.of(eventService), Optional.of(telemetryService));
		ActorRef follower = newTestFollower(Optional.empty(), Optional.of(publisher), Optional.empty());

		// set the state so that Follow Actor Receive Position Parameters
		setupState(new AssemblyState(null, null, rotationItem(rotationFollowing), tiltItem(tiltFollowing)));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		telemetryService.subscribe(resultSubscriber, false, M3Config.telemetryEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		TestProbe fakeTromboneEventSubscriber = new TestProbe(system);

		fakeTromboneEventSubscriber.send(follower,
				new M3FollowActor.UpdatedEventData(rotation(0), tilt(0), Events.getEventTime()));

		expectNoMsg(duration("200 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());

		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);
		logger.debug("Inside M3EventPublisherTest test1: result is: " + result.msgs + ": result size is: "
				+ result.msgs.size());
		assertEquals(result.msgs.size(), 1);
		StatusEvent se = jadd(new StatusEvent(M3Config.telemetryEventPrefix), jset(M3Config.rotation, 0.0),
				jset(M3Config.tilt, 0.0));
		Vector<StatusEvent> v = new Vector<>();
		v.add(se);
		assertEquals(result.msgs, v);
		cleanup(publisher, follower);
		logger.debug("Inside M3EventPublisherTest test1: ENDS");
	}

	@Test
	public void test2() {
		logger.debug("Inside M3EventPublisherTest test2: STARTS");
		ActorRef publisher = newTestPublisher(Optional.of(eventService), Optional.of(telemetryService));
		ActorRef follower = newTestFollower(Optional.empty(), Optional.of(publisher), Optional.empty());

		// set the state so that Follow Actor Receive Position Parameters
		setupState(new AssemblyState(null, null, rotationItem(rotationFollowing), tiltItem(tiltFollowing)));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		telemetryService.subscribe(resultSubscriber, false, M3Config.telemetryEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		double testTilt = 10.0;

		List<UpdatedEventData> events = testRotation.stream()
				.map(td -> new UpdatedEventData(rotation(td), tilt(testTilt), Events.getEventTime()))
				.collect(Collectors.toList());

		TestProbe fakeTromboneSubscriber = new TestProbe(system);
		events.forEach(ev -> fakeTromboneSubscriber.send(follower, ev));

		expectNoMsg(duration("200 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());
		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);

		logger.debug("Inside M3EventPublisherTest test2: result is: " + result);

		List<Pair<Double, Double>> testResult = newRotationAndTiltData(testTilt);

		List<StatusEvent> resultExpected = testResult
				.stream().map(f -> jadd(new StatusEvent(M3Config.telemetryEventPrefix),
						jset(M3Config.rotation, f.first()), jset(M3Config.tilt, f.second())))
				.collect(Collectors.toList());

		assertEquals(resultExpected, result.msgs);

		cleanup(publisher, follower);
		logger.debug("Inside M3EventPublisherTest test2: ENDS");
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
