package tmt.tcs.ecs;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;
import static tmt.tcs.ecs.EcsConfig.az;
import static tmt.tcs.ecs.EcsConfig.el;
import static tmt.tcs.test.common.EcsTestData.newAzAndElData;
import static tmt.tcs.test.common.EcsTestData.testAz;

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
import tmt.tcs.ecs.EcsFollowActor.UpdatedEventData;
import tmt.tcs.test.common.EcsTestData;
import tmt.tcs.test.common.TestEnvUtil;

public class EcsEventPublisherTest extends JavaTestKit {

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
				log.info("Inside EcsEventPublisherTest TestSubscriber: Received System: " + event.info().source()
						+ "  event: " + event);
			}).match(StatusEvent.class, event -> {
				msgs.add(event);
				log.info("Inside EcsEventPublisherTest TestSubscriber: Received Status: " + event.info().source()
						+ " event: " + event);
			}).match(GetResults.class, t -> sender().tell(new Results(msgs), self()))
					.matchAny(t -> log
							.warning("Inside EcsEventPublisherTest TestSubscriber: Unknown message received: " + t))
					.build());
		}
	}

	private static ActorSystem system;
	private static LoggingAdapter logger;

	// private static double initialElevation = 90.0;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	private static AssemblyContext assemblyContext = EcsTestData.ecsTestAssemblyContext;

	private static ITelemetryService telemetryService;

	private static IEventService eventService;

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public EcsEventPublisherTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("EcsEventPublishTest");
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

	DoubleItem initialAz = jset(EcsConfig.az, 0.0);
	DoubleItem initialEl = jset(EcsConfig.el, 0.0);

	ActorRef newTestFollower(Optional<ActorRef> ecsControl, Optional<ActorRef> publisher) {
		Props props = EcsFollowActor.props(assemblyContext, initialAz, initialEl, ecsControl, publisher);
		ActorRef a = system.actorOf(props);
		expectNoMsg(duration("200 millis"));
		return a;
	}

	ActorRef newTestPublisher(Optional<IEventService> eventService, Optional<ITelemetryService> telemetryService) {
		Props testEventPublisherProps = EcsEventPublisher.props(assemblyContext, eventService, telemetryService);
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
		logger.debug("Inside EcsEventPublisher test1: STARTS");
		ActorRef publisher = newTestPublisher(Optional.of(eventService), Optional.of(telemetryService));
		ActorRef follower = newTestFollower(Optional.empty(), Optional.of(publisher));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		telemetryService.subscribe(resultSubscriber, false, EcsConfig.telemetryEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		TestProbe fakeTromboneEventSubscriber = new TestProbe(system);

		fakeTromboneEventSubscriber.send(follower,
				new EcsFollowActor.UpdatedEventData(az(0), el(0), Events.getEventTime()));

		expectNoMsg(duration("200 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());

		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);
		logger.debug("Inside EcsEventPublisher test1: result is: " + result.msgs + ": result size is: "
				+ result.msgs.size());
		assertEquals(result.msgs.size(), 1);
		StatusEvent se = jadd(new StatusEvent(EcsConfig.telemetryEventPrefix), jset(EcsConfig.az, 0.0),
				jset(EcsConfig.el, 0.0));
		Vector<StatusEvent> v = new Vector<>();
		v.add(se);
		assertEquals(result.msgs, v);
		cleanup(publisher, follower, resultSubscriber);
		logger.debug("Inside EcsEventPublisher test1: ENDS");
	}

	@Test
	public void test2() {
		logger.debug("Inside EcsEventPublisher test2: STARTS");
		ActorRef publisher = newTestPublisher(Optional.of(eventService), Optional.of(telemetryService));
		ActorRef follower = newTestFollower(Optional.empty(), Optional.of(publisher));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		telemetryService.subscribe(resultSubscriber, false, EcsConfig.telemetryEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		double testEl = 10.0;

		List<UpdatedEventData> events = testAz.stream()
				.map(td -> new UpdatedEventData(az(td), el(testEl), Events.getEventTime()))
				.collect(Collectors.toList());

		TestProbe fakeTromboneSubscriber = new TestProbe(system);
		events.forEach(ev -> fakeTromboneSubscriber.send(follower, ev));

		expectNoMsg(duration("200 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());
		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);

		logger.debug("Inside EcsEventPublisherTest test2: result is: " + result);

		List<Pair<Double, Double>> testResult = newAzAndElData(testEl);

		List<StatusEvent> resultExpected = testResult.stream()
				.map(f -> jadd(new StatusEvent(EcsConfig.telemetryEventPrefix), jset(EcsConfig.az, f.first()),
						jset(EcsConfig.el, f.second())))
				.collect(Collectors.toList());

		assertEquals(resultExpected, result.msgs);

		cleanup(publisher, follower, resultSubscriber);
		logger.debug("Inside EcsEventPublisher test2: ENDS");
	}

}
