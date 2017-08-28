/* ----------------------------------------------------------------------------
 * This file was automatically generated by SWIG (http://www.swig.org).
 * Version 2.0.10
 *
 * Do not make changes to this file unless you know what you are doing--modify
 * the SWIG interface file instead.
 * ----------------------------------------------------------------------------- */

package tmt.tcs.tpk.wrapper;

public class TpkPoc {
	private long swigCPtr;
	protected boolean swigCMemOwn;

	protected TpkPoc(long cPtr, boolean cMemoryOwn) {
		swigCMemOwn = cMemoryOwn;
		swigCPtr = cPtr;
	}

	protected static long getCPtr(TpkPoc obj) {
		return (obj == null) ? 0 : obj.swigCPtr;
	}

	protected void finalize() {
		delete();
	}

	public synchronized void delete() {
		if (swigCPtr != 0) {
			if (swigCMemOwn) {
				swigCMemOwn = false;
				exampleJNI.delete_TpkPoc(swigCPtr);
			}
			swigCPtr = 0;
		}
	}

	public TpkPoc() {
		this(exampleJNI.new_TpkPoc(), true);
	}

	public void init() {
		exampleJNI.TpkPoc_init(swigCPtr, this);
	}

	public void _register(IDemandsCB demandsNotify) {
		exampleJNI.TpkPoc__register(swigCPtr, this, IDemandsCB.getCPtr(demandsNotify), demandsNotify);
	}

	public void newDemands(double mAz, double mEl, double eAz, double eEl, double m3R, double m3T) {
		exampleJNI.TpkPoc_newDemands(swigCPtr, this, mAz, mEl, eAz, eEl, m3R, m3T);
	}

	public void newTarget(double ra, double dec) {
		System.out.println("Inside TpkPoc: Received New Target");
		exampleJNI.TpkPoc_newTarget(swigCPtr, this, ra, dec);
	}

	public void offset(double raO, double decO) {
		System.out.println("Inside TpkPoc: Received Offset");
		exampleJNI.TpkPoc_offset(swigCPtr, this, raO, decO);
	}

}