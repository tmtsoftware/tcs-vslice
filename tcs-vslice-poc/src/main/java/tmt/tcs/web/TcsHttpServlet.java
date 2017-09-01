package tmt.tcs.web;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import tmt.tcs.TcsConfig;

/**
 * Servlet implementation class TcsServlet
 */
@WebServlet(description = "TCS Servlet", urlPatterns = { "/tcsProcessor", "/tcsProcessor.do" })
public class TcsHttpServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

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

		TcsHttpRequestHandler tcsHandler = new TcsHttpRequestHandler();

		if (TcsConfig.followPrefix.equals(command)) {
			tcsHandler.executeFollowCommand(targetName, ra, dec, frame);
		} else if (TcsConfig.offsetPrefix.equals(command)) {
			tcsHandler.executeOffsetCommand(ra, dec);
		}

		response.sendRedirect("Index.jsp?ra=" + ra + "&dec=" + dec + "&targetName=" + targetName + "&frame=" + frame);
	}

}
