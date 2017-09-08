package tmt.tcs.web;

import tmt.tcs.ecs.EcsConfig;
import tmt.tcs.m3.M3Config;
import tmt.tcs.mcs.McsConfig;

/**
 * This class will be used by Event Subscriber to Set updated Position value in
 * and same will be used by JSP Class to display the same on frontend
 */
public class TcsDataHandler {

	public static Double mcsAzimuth = McsConfig.defaultAzValue;
	public static Double mcsElevation = McsConfig.defaultElValue;
	public static Double ecsAzimuth = EcsConfig.defaultAzValue;
	public static Double ecsElevation = EcsConfig.defaultElValue;
	public static Double m3Rotation = M3Config.defaultRotationValue;
	public static Double m3Tilt = M3Config.defaultTiltValue;

}
