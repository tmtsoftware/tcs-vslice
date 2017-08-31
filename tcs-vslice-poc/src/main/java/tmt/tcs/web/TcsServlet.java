package tmt.tcs.web;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Servlet implementation class TcsServlet
 */
@WebServlet(description = "TCS Servlet", urlPatterns = { "/tcsProcessor", "/tcsProcessor.do" })
public class TcsServlet extends HttpServlet {
	private static final long serialVersionUID = 1L;

	public TcsServlet() {
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

		System.out.println(
				"Target Name is: " + targetName + "Ra is: " + ra + ": Dec is: " + dec + ": F	rame is: " + frame);

		TcsHandler tcsHandler = new TcsHandler();
		tcsHandler.executeFollowCommand(targetName, ra, dec, frame);

		response.sendRedirect("Index.html");
	}

}
