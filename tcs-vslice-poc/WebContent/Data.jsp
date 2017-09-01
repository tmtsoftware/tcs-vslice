<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
	pageEncoding="ISO-8859-1"%>
<%@ page import="tmt.tcs.web.TcsDataHandler"%>
<!DOCTYPE html>
<html>
<head>
<meta charset="ISO-8859-1">
<title>TMT-TCS Show Data</title>
<meta http-equiv="refresh" content="1">
</head>
<script type="text/javascript">
	function getData() {
		<%
			String mcsAz = TcsDataHandler.mcsAzimuth  + "";
			String mcsEl = TcsDataHandler.mcsElevation + "";
			String ecsAz = TcsDataHandler.ecsAzimuth + "";
			String ecsEl = TcsDataHandler.ecsElevation + "";
			String m3Rotation = TcsDataHandler.m3Rotation + "";
			String m3Tilt = TcsDataHandler.m3Tilt + "";
		%>
	}
</script>
<body onload="getData()">

	<form>
		<table border="1">
			<tr>
				<td>Current MCS Azimuth</td>
				<td>Current MCS Elevation</td>
				<td>Current ECS Azimuth</td>
				<td>Current ECS Elevation</td>
				<td>Current M3 Rotation</td>
				<td>Current M3 Tilt</td>
			</tr>
			<tr>
				<td><%=mcsAz%></td>
				<td><%=mcsEl%></td>
				<td><%=ecsAz%></td>
				<td><%=ecsEl%></td>
				<td><%=m3Rotation%></td>
				<td><%=m3Tilt%></td>
			</tr>
		</table>
	</form>

</body>
</html>