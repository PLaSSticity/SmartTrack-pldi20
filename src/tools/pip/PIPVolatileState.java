package tools.pip;

import acme.util.Util;
import rr.state.ShadowVolatile;
import tools.util.VectorClock;

public class PIPVolatileState extends VectorClock {

	private final ShadowVolatile peer;
	
	public VectorClock readsJoined;
	
	public PIPVolatileState(ShadowVolatile peer, int size) {
		super(size);
		this.peer = peer;
		this.readsJoined = new VectorClock(size);
	}
	
	public ShadowVolatile getPeer() {
		return peer;
	}
	
	@Override
	public synchronized String toString() {
		return String.format("[peer %s: %s]", Util.objectToIdentityString(peer), super.toString());
	}
}
