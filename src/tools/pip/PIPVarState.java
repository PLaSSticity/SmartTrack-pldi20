package tools.pip;

import java.util.HashMap;

import rr.state.ShadowLock;
import rr.state.ShadowVar;
import tools.util.Epoch;
import tools.util.VectorClock;

public class PIPVarState extends VectorClock implements ShadowVar {

	public volatile int/*epoch*/ W;
	
	public volatile int/*epoch*/ R;
	
	protected PIPVarState() {}
	
	public PIPVarState(boolean isWrite, int/*epoch*/ epoch, boolean isOwned) {
		if (isWrite) {
			if (isOwned) {
				R = epoch;
				W = epoch;
			} else {
				R = Epoch.ZERO;
				W = epoch;
			}
		} else {
			W = Epoch.ZERO;
			R = epoch;
		}
	}
	
	@Override
	public synchronized void makeCV(int len) {
		super.makeCV(len);
	}
	
	@Override
	public synchronized String toString() {
		return String.format("[W=%s R=%s V=%s]", Epoch.toString(W), Epoch.toString(R), super.toString());
	}
}

class HeldLS {
	ShadowLock lock;
	VectorClock vc;
	HeldLS next = null;
	
	public HeldLS (ShadowLock lock, VectorClock vc) {
		this.lock = lock;
		this.vc = vc;
	}
}

class STVarState extends PIPVarState {	
	HeldLS Wm = null;
	HeldLS Rm = null;
	HeldLS[] SharedRm = null;
	
	HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>> Ew = null;
	HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>> Er = null;
	
	public STVarState(boolean isWrite, int epoch, boolean isOwned) {
		super(isWrite, epoch, isOwned);
	}
	
	//makeSharedHeldLS does not need to be synchronized since the VarState is locked on
	//SharedRm does not need to be volatile since the lock elements being added are already held
	//Input should be similar to: int initSize = Math.max(Math.max(rTid,tid), INIT_VECTOR_CLOCK_SIZE);
	public void makeSharedHeldLS(int len) {
		this.SharedRm = new HeldLS[len];
	}
	
	public void setSharedHeldLS(int tid, HeldLS heldLock) {
		ensureCapacitySharedHeldLS(tid + 1);
		this.SharedRm[tid] = heldLock;
	}
	
	private void ensureCapacitySharedHeldLS(int len) {
		int curLength = this.SharedRm.length;
		if (curLength < len) {
			HeldLS[] b = new HeldLS[len];
			for (int i = 0; i < curLength; i++) {
				b[i] = this.SharedRm[i];
			}
			this.SharedRm = b;
		}
	}
	
	public HeldLS getSharedHeldLS(int tid) {
		HeldLS[] mySharedRm = this.SharedRm;
		if (tid < mySharedRm.length) {
			return this.SharedRm[tid];
		} else {
			return null;
		}
	}
	
	public void clearSharedHeldLS() {
		this.SharedRm = null;
	}
}