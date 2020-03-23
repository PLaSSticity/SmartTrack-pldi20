package tools.pip;

import acme.util.Util;
import tools.util.VectorClock;

public class PIPBarrierState {

	private final Object barrier;
	
	private VectorClock clock;
	
	public PIPBarrierState(Object k, int size) {
		clock = new VectorClock(size);
		barrier = k;
	}
	
	public synchronized void stopUsingOldVectorClock(VectorClock old) {
		if (clock == old) {
			clock = new VectorClock(old.size());
		}
	}
	
	public synchronized VectorClock enterBarrier() {
		return clock;
	}
	
	public synchronized String toString() {
		return "[peer " + Util.objectToIdentityString(barrier) + ": " + clock + "]";
	}
}
