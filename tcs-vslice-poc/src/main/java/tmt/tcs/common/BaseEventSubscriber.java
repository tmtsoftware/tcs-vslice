package tmt.tcs.common;

import java.util.Arrays;

import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import csw.services.events.EventService.EventMonitor;
import csw.util.config.Configurations.ConfigKey;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.ILocationSubscriberClient;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

/**
 * This is the base Event Subscriber class being extended by all Event
 * Subscribers
 */
public abstract class BaseEventSubscriber extends AbstractActor implements ILocationSubscriberClient {

	private LoggingAdapter log = Logging.getLogger(getContext().system(), this);

	public abstract PartialFunction<Object, BoxedUnit> subscribeReceive();

	/**
	 * This helps unsubscribe Event Monitor from specific Config Key
	 * 
	 * @param monitor
	 * @param configKeys
	 */
	public void unsubscribeKeys(EventMonitor monitor, ConfigKey... configKeys) {
		log.debug("Inside BaseEventSubscriber Unsubscribing to: " + Arrays.toString(configKeys));
		for (ConfigKey configKey : configKeys) {
			monitor.unsubscribeFrom(configKey.prefix());
		}
	}

	/**
	 * This helps Event Monitor in subscribing to specific Config Key
	 * 
	 * @param eventService
	 * @param configKeys
	 * @return
	 */
	public EventMonitor subscribeKeys(IEventService eventService, ConfigKey... configKeys) {
		log.debug("Inside BaseEventSubscriber Subscribing to: " + Arrays.toString(configKeys));
		String[] prefixes = new String[configKeys.length];
		for (int i = 0; i < configKeys.length; i++) {
			prefixes[i] = configKeys[i].prefix();
		}
		return eventService.subscribe(self(), false, prefixes);
	}

	/**
	 * This helps in adding more Config Key for subscription to Event Monitor
	 */
	public void subscribeKeys(EventMonitor monitor, ConfigKey... configKeys) {
		log.debug("Inside BaseEventSubscriber Subscribing to: " + Arrays.toString(configKeys));
		for (ConfigKey configKey : configKeys) {
			monitor.subscribeTo(configKey.prefix());
		}
	}
}
