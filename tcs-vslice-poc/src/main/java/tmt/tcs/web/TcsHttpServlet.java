package tmt.tcs.web;

import static javacsw.util.config.JItems.jadd;
import static javacsw.util.config.JItems.jset;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.testkit.TestProbe;
import akka.util.Timeout;
import csw.services.apps.containerCmd.ContainerCmd;
import csw.services.ccs.AssemblyController.Submit;
import csw.services.loc.LocationService;
import csw.services.sequencer.SequencerEnv;
import csw.util.config.Configurations;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.SetupConfigArg;
import javacsw.services.events.IEventService;
import javacsw.services.pkg.JSupervisor;
import scala.concurrent.duration.FiniteDuration;
import tmt.tcs.TcsConfig;
import tmt.tcs.test.common.EcsTestData;
import tmt.tcs.test.common.M3TestData;
import tmt.tcs.test.common.McsTestData;
import tmt.tcs.test.common.TcsTestData;
import tmt.tcs.test.common.TpkTestData;

/**
 * Servlet implementation class TcsServlet
 */
@WebServlet(description = "TCS Servlet", urlPatterns = { "/tcsProcessor", "/tcsProcessor.do" }, loadOnStartup = 1)
public class TcsHttpServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	private static ActorSystem system;
	private static LoggingAdapter logger;

	private static ActorRef tcsAssembly;

	private static Timeout timeout = Timeout.durationToTimeout(FiniteDuration.apply(10, TimeUnit.SECONDS));
	@SuppressWarnings("unused")
	private static IEventService eventService;
	private static String mcsHcdName = "mcsHcd";
	private static String ecsHcdName = "ecsHcd";
	private static String m3HcdName = "m3Hcd";

	private static List<ActorRef> mcsRefActors = Collections.emptyList();
	private static List<ActorRef> ecsRefActors = Collections.emptyList();
	private static List<ActorRef> m3RefActors = Collections.emptyList();

	static {
		try {
			LocationService.initInterface();

			system = ActorSystem.create("tcsAssembly");
			logger = Logging.getLogger(system, system);

			System.out.println("Inside TcsHttpServlet setup");

			eventService = IEventService.getEventService(IEventService.defaultName, system, timeout).get(5,
					TimeUnit.SECONDS);

			Map<String, String> mcsConfigMap = Collections.singletonMap("", "hcd/mcsHcd.conf");
			ContainerCmd mcsCmd = new ContainerCmd("mcsHcd", new String[] { "--standalone" }, mcsConfigMap);
			mcsRefActors = mcsCmd.getActors();
			if (mcsRefActors.size() == 0)
				logger.error("Inside TcsHttpServlet Failed to create Mcs HCD");
			Thread.sleep(2000); // XXX FIXME Make sure components have time to
								// register from location service

			Map<String, String> ecsConfigMap = Collections.singletonMap("", "hcd/ecsHcd.conf");
			ContainerCmd ecsCmd = new ContainerCmd("ecsHcd", new String[] { "--standalone" }, ecsConfigMap);
			ecsRefActors = ecsCmd.getActors();
			if (ecsRefActors.size() == 0)
				logger.error("Inside TcsHttpServlet Failed to create Ecs HCD");
			Thread.sleep(2000); // XXX FIXME Make sure components have time to
								// register from location service

			Map<String, String> m3ConfigMap = Collections.singletonMap("", "hcd/m3Hcd.conf");
			ContainerCmd m3Cmd = new ContainerCmd("m3Hcd", new String[] { "--standalone" }, m3ConfigMap);
			m3RefActors = m3Cmd.getActors();
			if (m3RefActors.size() == 0)
				logger.error("Inside TcsHttpServlet Failed to create M3 HCD");
			Thread.sleep(2000); // XXX FIXME Make sure components have time to
								// register from location service

			JSupervisor.create(McsTestData.mcsTestAssemblyContext.info);
			JSupervisor.create(EcsTestData.ecsTestAssemblyContext.info);
			JSupervisor.create(M3TestData.m3TestAssemblyContext.info);
			JSupervisor.create(TpkTestData.tpkTestAssemblyContext.info);

			tcsAssembly = JSupervisor.create(TcsTestData.tcsTestAssemblyContext.info);

			SequencerEnv.resolveHcd(mcsHcdName);
			SequencerEnv.resolveHcd(ecsHcdName);
			SequencerEnv.resolveHcd(m3HcdName);
		} catch (Exception ex) {
			System.out.println("Inside TcsHttpServlet: exception is: " + ex);
		}
	}

	public TcsHttpServlet() {
		super();
	}

	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		System.out.println("Inside CommandServlet: GET");
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {

		System.out.println("Inside CommandServlet: POST: " + request.getParameter("ra"));

		String targetName = request.getParameter("targetName");
		Double ra = new Double(request.getParameter("ra"));
		Double dec = new Double(request.getParameter("dec"));
		String frame = request.getParameter("frame");
		String command = request.getParameter("command");

		System.out.println("Target Name is: " + targetName + "Ra is: " + ra + ": Dec is: " + dec + ": Frame is: "
				+ frame + ": Command is: " + command);

		if (TcsConfig.followPrefix.equals(command)) {
			executeFollowCommand(targetName, ra, dec, frame);
		} else if (TcsConfig.offsetPrefix.equals(command)) {
			executeOffsetCommand(ra, dec);
		}

		response.sendRedirect("Index.jsp?command=" + command + "&ra=" + ra + "&dec=" + dec + "&targetName=" + targetName
				+ "&frame=" + frame);
	}

	public void executeFollowCommand(String targetValue, Double raValue, Double decValue, String frameValue) {
		logger.debug("Inside CommandServlet executeFollowCommand Starts");

		TestProbe fakeClient = new TestProbe(system);

		SetupConfig positionSc = jadd(new SetupConfig(TcsConfig.followCK.prefix()), jset(TcsConfig.target, targetValue),
				jset(TcsConfig.ra, raValue), jset(TcsConfig.dec, decValue), jset(TcsConfig.frame, frameValue));

		SetupConfigArg sca = Configurations.createSetupConfigArg("tcsPositionCommand", positionSc);

		fakeClient.send(tcsAssembly, new Submit(sca));

	}

	public void executeOffsetCommand(Double raOffsetValue, Double decOffsetValue) {
		logger.debug("Inside CommandServlet executeOffsetCommand Starts");

		TestProbe fakeClient = new TestProbe(system);

		SetupConfig offsetSc = jadd(new SetupConfig(TcsConfig.offsetCK.prefix()), jset(TcsConfig.ra, raOffsetValue),
				jset(TcsConfig.dec, decOffsetValue));

		SetupConfigArg sca = Configurations.createSetupConfigArg("tcsOffsetCommand", offsetSc);

		fakeClient.send(tcsAssembly, new Submit(sca));

	}

}
