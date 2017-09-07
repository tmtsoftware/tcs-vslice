<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
	pageEncoding="ISO-8859-1"%>
<%@ page import="tmt.tcs.TcsConfig"%>
<!DOCTYPE html>
<html>
<head>
<meta charset="ISO-8859-1">
<title>TMT-TCS GUI</title>
</head>
<script type="text/javascript">
	function getPreviousValues() {
		<%
		  String command = request.getParameter("command") == null ? "": request.getParameter("command");
		  String ra = request.getParameter("ra") == null ? "": request.getParameter("ra");
		  String dec = request.getParameter("dec") == null ? "": request.getParameter("dec");
		  String targetName = request.getParameter("targetName") == null ? "": request.getParameter("targetName");
		  String frame = request.getParameter("frame") == null ? "": request.getParameter("frame");
		  
		%>
		document.getElementById('command').value = '<%=command%>';
	}
</script>
<body onload="getPreviousValues()">

	<form action="tcsProcessor" method="post">
		<div align="center">
			<table>
				<tr bgcolor="#FF9933">
				    <th colspan="2" >TMT-TCS</th>
				</tr>
				<tr>
				    <td colspan="2" ></td>
				</tr>
				<tr>
					<td>Command Name:</td>
					<td>
						<select id="command" name="command">
							<option value="">--Select Command--</option>
							<option value="<%=TcsConfig.followPrefix%>">Follow</option>
							<option value="<%=TcsConfig.offsetPrefix%>">Offset</option>
						</select>
					</td>
				</tr>
				<tr>
					<td>Target Name:</td>
					<td>
						<input type="text" name="targetName" id="targetName" value="<%=targetName%>">
					</td>
				</tr>
				<tr>
					<td>Ra (Double):</td>
					<td>
						<input type="text" name="ra" id="ra" value="<%=ra%>">
					</td>
				</tr>
				<tr>
					<td>Dec (Double):</td>
					<td>
						<input type="text" name="dec" id="dec" value="<%=dec%>">
					</td>
				</tr>
				<tr>
					<td>Frame:</td>
					<td>
						<input type="text" name="frame" id="frame" value="<%=frame%>">
					</td>
				</tr>
				<tr>
					<td rowspan="2" align="center">
						<input type="submit">
					</td>
				</tr>
			</table>
		</div>
	</form>
	<br />
	<br />
	<p align="center">
		<iframe src="Data.jsp" title="Data Display" width="1000" height="120">
			<p>Your browser does not support iframes.</p>
		</iframe>
	</p>

</body>
</html>