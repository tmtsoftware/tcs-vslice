package tmt.tcs.common;

import static javacsw.services.pkg.JSupervisor.DoRestart;
import static javacsw.services.pkg.JSupervisor.DoShutdown;
import static javacsw.services.pkg.JSupervisor.Initialized;
import static javacsw.services.pkg.JSupervisor.Running;
import static javacsw.services.pkg.JSupervisor.RunningOffline;
import static javacsw.services.pkg.JSupervisor.ShutdownComplete;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import akka.util.Timeout;
import csw.services.ccs.SequentialExecutor;
import csw.services.pkg.Supervisor;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.ccs.JAssemblyController;
import javacsw.services.cs.akka.JConfigServiceClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base assembly class being extended by all Top Level Assemblies
 */
public abstract class BaseAssembly extends JAssemblyController {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	public BaseAssembly(AssemblyInfo info) {
		super(info);
	}

	/**
	 * This is used for handling the diagnostic commands
	 *
	 * @return a partial function
	 */
	public abstract PartialFunction<Object, BoxedUnit> diagnosticReceive();

	/**
	 * This tracks the life cycle of Assembly
	 *
	 * @return a partial function
	 */
	public PartialFunction<Object, BoxedUnit> lifecycleReceivePF(ActorRef supervisor) {
		return ReceiveBuilder.matchEquals(Initialized, t -> {
			log.debug("Inside BaseAssembly lifecycleReceivePF: Initialized");
		}).matchEquals(Running, t -> {
			// Already running so ignore
			log.debug("Inside BaseAssembly lifecycleReceivePF: Already running");
		}).matchEquals(RunningOffline, t -> {
			log.debug("Inside BaseAssembly lifecycleReceivePF: running offline");
		}).matchEquals(DoRestart, t -> log.debug("Inside BaseAssembly lifecycleReceivePF: dorestart"))
				.matchEquals(DoShutdown, t -> {
					log.debug("Inside BaseAssembly lifecycleReceivePF: doshutdown");
					supervisor.tell(ShutdownComplete, self());
				}).match(Supervisor.LifecycleFailureInfo.class, t -> {
					log.debug("Inside BaseAssembly lifecycleReceivePF: failed lifecycle state: " + t.state()
							+ " for reason: " + t.reason());
				}).build();
	}

	/**
	 * This Catches all unhandled message received
	 *
	 * @return a partial function
	 */
	public PartialFunction<Object, BoxedUnit> unhandledPF() {
		return ReceiveBuilder.matchAny(t -> log.debug("Inside BaseAssembly Unexpected message:unhandledPF: " + t))
				.build();
	}

	/**
	 * Gets the assembly configurations from the config service, or a resource
	 * file, if not found and returns the two parsed objects.
	 */
	public Config getAssemblyConfigs(File configFile, File resource) throws Exception {
		Timeout timeout = new Timeout(3, TimeUnit.SECONDS);
		Optional<Config> configOpt = JConfigServiceClient.getConfigFromConfigService(configFile, Optional.empty(),
				Optional.of(resource), context().system(), timeout).get();
		if (configOpt.isPresent())
			return configOpt.get();
		throw new RuntimeException("Failed to get from config service: " + configFile);
	}

	/**
	 * This is a convenience method to create a new SequentialExecutor
	 */
	public ActorRef newExecutor(ActorRef commandHandler, SetupConfigArg sca, Optional<ActorRef> commandOriginator) {
		return context().actorOf(SequentialExecutor.props(commandHandler, sca, commandOriginator));
	}

	/**
	 * The message is used within the Assembly to update actors when the HCD
	 * goes up and down and up again
	 */
	public static class UpdateHcd {
		public final Optional<ActorRef> hcdActorRef;

		/**
		 * @param hcdActorRef
		 *            the ActorRef of the hcd or None
		 */
		public UpdateHcd(Optional<ActorRef> hcdActorRef) {
			this.hcdActorRef = hcdActorRef;
		}
	}
}
