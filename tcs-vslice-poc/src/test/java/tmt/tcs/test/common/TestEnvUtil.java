package tmt.tcs.test.common;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import akka.actor.ActorSystem;
import akka.util.Timeout;
import javacsw.services.cs.akka.JConfigServiceClient;
import javacsw.services.events.IEventService;
import javacsw.services.events.IEventServiceAdmin;
import javacsw.services.events.JEventServiceAdmin;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.mcs.McsAssembly;
import tmt.tcs.mcs.hcd.McsHcd;

/**
 * Helper class for setting up the test environment
 */
public class TestEnvUtil {

	// For the tests, store the HCD's configuration in the config service
	// (Normally, it would already be there)
	public static void createMcsHcdConfig(ActorSystem system) throws ExecutionException, InterruptedException {
		Config config = ConfigFactory.parseResources(McsHcd.resource.getPath());
		Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
		JConfigServiceClient.saveConfigToConfigService(McsHcd.mcsConfigFile, config, system, timeout).get();
	}

	// For the tests, store the assembly's configuration in the config service
	// (Normally, it would already be there)
	public static void createMcsAssemblyConfig(ActorSystem system) throws ExecutionException, InterruptedException {
		createMcsHcdConfig(system);
		Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
		Config config = ConfigFactory.parseResources(McsAssembly.resource.getPath());
		JConfigServiceClient.saveConfigToConfigService(McsAssembly.mcsConfigFile, config, system, timeout).get();
	}

	// Reset all redis based services before a test (assumes they are sharing
	// the same Redis instance)
	public static void resetRedisServices(ActorSystem system) throws Exception {
		int t = 10;
		TimeUnit u = TimeUnit.SECONDS;
		Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(t, u));
		// clear redis
		IEventService eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(t,
				u);
		IEventServiceAdmin eventServiceAdmin = new JEventServiceAdmin(eventService, system);
		eventServiceAdmin.reset().get(t, u);
	}
}
