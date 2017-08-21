package tmt.tcs.mcs;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;
import static junit.framework.TestCase.assertEquals;
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
import tmt.tcs.mcs.McsFollowActor.UpdatedEventData;
import tmt.tcs.test.common.McsTestData;
import tmt.tcs.test.common.TestEnvUtil;

public class McsEventPublisherTest extends JavaTestKit {

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
				log.info("Inside McsEventPublisherTest TestSubscriber: Received System: " + event.info().source()
						+ "  event: " + event);
			}).match(Events.StatusEvent.class, event -> {
				msgs.add(event);
				log.info("Inside McsEventPublisherTest TestSubscriber: Received Status " + event.info().source()
						+ " event: " + event);
			}).match(GetResults.class, t -> sender().tell(new Results(msgs), self()))
					.matchAny(t -> log
							.warning("Inside McsEventPublisherTest TestSubscriber: Unknown message received: " + t))
					.build());
		}
	}

	private static ActorSystem system;
	private static LoggingAdapter logger;

	// private static double initialElevation = 90.0;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));

	private static AssemblyContext assemblyContext = McsTestData.mcsTestAssemblyContext;

	@SuppressWarnings("unused")
	private static ITelemetryService telemetryService;

	private static IEventService eventService;

	// This def helps to make the test code look more like normal production
	// code, where self() is defined in an actor class
	ActorRef self() {
		return getTestActor();
	}

	public McsEventPublisherTest() {
		super(system);
	}

	@Before
	public void beforeEach() throws Exception {
		TestEnvUtil.resetRedisServices(system);
	}

	@BeforeClass
	public static void setup() throws Exception {
		LocationService.initInterface();
		system = ActorSystem.create("McsEventPublishTest");
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

	ActorRef newTestFollower(Optional<ActorRef> mcsControl, Optional<ActorRef> publisher) {
		Props props = McsFollowActor.props(assemblyContext, initialAz, initialEl, mcsControl, publisher);
		ActorRef a = system.actorOf(props);
		expectNoMsg(duration("200 millis"));
		return a;
	}

	ActorRef newTestPublisher(Optional<IEventService> eventService, Optional<ITelemetryService> telemetryService) {
		Props testEventPublisherProps = McsEventPublisher.props(assemblyContext, eventService, telemetryService);
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
		logger.debug("Inside McsEventPublisherTest test1: STARTS");
		ActorRef publisher = newTestPublisher(Optional.of(eventService), Optional.empty());
		ActorRef follower = newTestFollower(Optional.empty(), Optional.of(publisher));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		eventService.subscribe(resultSubscriber, false, McsConfig.systemEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		TestProbe fakeTromboneEventSubscriber = new TestProbe(system);

		fakeTromboneEventSubscriber.send(follower, new McsFollowActor.UpdatedEventData(az(0), el(0), Events.getEventTime()));

		expectNoMsg(duration("200 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());

		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);
		logger.debug("Inside McsEventPublisherTest test1: result is: " + result.msgs + ": result size is: "
				+ result.msgs.size());
		assertEquals(result.msgs.size(), 1);
		SystemEvent se = jadd(new SystemEvent(McsConfig.systemEventPrefix), jset(McsConfig.az, 0.0),
				jset(McsConfig.el, 0.0));
		Vector<SystemEvent> v = new Vector<>();
		v.add(se);
		assertEquals(result.msgs, v);
		cleanup(publisher, follower);
		logger.debug("Inside McsEventPublisherTest test1: ENDS");
	}

	@Test
	public void test2() {
		logger.debug("Inside McsEventPublisherTest test2: STARTS");
		ActorRef pub = newTestPublisher(Optional.of(eventService), Optional.empty());
		ActorRef fol = newTestFollower(Optional.empty(), Optional.of(pub));

		ActorRef resultSubscriber = system.actorOf(TestSubscriber.props());
		eventService.subscribe(resultSubscriber, false, McsConfig.systemEventPrefix);
		expectNoMsg(duration("1 second")); // Wait for the connection

		double testEl = 10.0;

		List<UpdatedEventData> events = testAz.stream()
				.map(td -> new UpdatedEventData(az(td), el(testEl), Events.getEventTime()))
				.collect(Collectors.toList());

		TestProbe fakeTromboneSubscriber = new TestProbe(system);
		events.forEach(ev -> fakeTromboneSubscriber.send(fol, ev));

		expectNoMsg(duration("100 milli"));

		resultSubscriber.tell(new TestSubscriber.GetResults(), self());
		TestSubscriber.Results result = expectMsgClass(TestSubscriber.Results.class);

		logger.debug("Inside McsEventPublisherTest test2: result is: " + result);

		List<Pair<Double, Double>> testResult = newAzAndElData(testEl);

		List<SystemEvent> resultExpected = testResult.stream()
				.map(f -> jadd(new SystemEvent(McsConfig.systemEventPrefix), jset(McsConfig.az, f.first()),
						jset(McsConfig.el, f.second())))
				.collect(Collectors.toList());

		assertEquals(resultExpected, result.msgs);

		cleanup(pub, fol);
		logger.debug("Inside McsEventPublisherTest test2: ENDS");
	}

}
