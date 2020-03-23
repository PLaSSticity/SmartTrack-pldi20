package tools.pip;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

import acme.util.Util;
import acme.util.identityhash.WeakIdentityHashMap;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import tools.util.VectorClock;
import tools.wdc.PerThreadQueue;

public class LockState extends VectorClock {

	private final ShadowLock peer;
	
	public LockState(ShadowLock peer, int size) {
		super(size);
		this.peer = peer;
	}
	
	public ShadowLock getPeer() {
		return peer;
	}
	
	@Override
	public synchronized String toString() {
		return String.format("[peer %s: %s]", Util.objectToIdentityString(peer), super.toString());
	}
}

class WCPLockState extends LockState {
	//Rule(c)
	public final VectorClock hb;
	
	//Rule(a)
	public HashSet<ShadowVar> readVars = new HashSet<ShadowVar>();
	public HashSet<ShadowVar> writeVars = new HashSet<ShadowVar>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> WriteMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> ReadMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	
	//Rule(b)
	public final HashMap<ShadowThread,ArrayDeque<VectorClock>> AcqQueueMap = new HashMap<ShadowThread,ArrayDeque<VectorClock>>();
	public final HashMap<ShadowThread,ArrayDeque<VectorClock>> RelQueueMap = new HashMap<ShadowThread,ArrayDeque<VectorClock>>();
	public final ArrayDeque<VectorClock> AcqQueueGlobal = new ArrayDeque<VectorClock>();
	public final ArrayDeque<VectorClock> RelQueueGlobal = new ArrayDeque<VectorClock>();
	
	public WCPLockState(ShadowLock peer, int size) {
		super(peer, size);
		hb = new VectorClock(size);
	}
}

class WCPSTLockState extends LockState {
	//Rule(c)
	public final VectorClock hb;
	
	//Rule(a)
	public VectorClock Cm;
	
	//Rule(b)
	public final HashMap<ShadowThread,ArrayDeque<VectorClock>> AcqQueueMap = new HashMap<ShadowThread,ArrayDeque<VectorClock>>();
	public final HashMap<ShadowThread,ArrayDeque<VectorClock>> RelQueueMap = new HashMap<ShadowThread,ArrayDeque<VectorClock>>();
	public final ArrayDeque<VectorClock> AcqQueueGlobal = new ArrayDeque<VectorClock>();
	public final ArrayDeque<VectorClock> RelQueueGlobal = new ArrayDeque<VectorClock>();
	
	public WCPSTLockState(ShadowLock peer, int size) {
		super(peer, size);
		this.Cm = new VectorClock(size);
		hb = new VectorClock(size);
	}
}

class DCLockState extends LockState {
	//Rule(a)
	public HashSet<ShadowVar> readVars = new HashSet<ShadowVar>();
	public HashSet<ShadowVar> writeVars = new HashSet<ShadowVar>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> WriteMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> ReadMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	
	//Rule(b)
	public final HashMap<ShadowThread,PerThreadQueue<VectorClock>> AcqQueueMap = new HashMap<ShadowThread,PerThreadQueue<VectorClock>>();
	public final HashMap<ShadowThread,PerThreadQueue<VectorClock>> RelQueueMap = new HashMap<ShadowThread,PerThreadQueue<VectorClock>>();
	public final PerThreadQueue<VectorClock> AcqQueueGlobal = new PerThreadQueue<VectorClock>();
	public final PerThreadQueue<VectorClock> RelQueueGlobal = new PerThreadQueue<VectorClock>();
	
	public DCLockState(ShadowLock peer, int size) {
		super(peer, size);
	}
}

class DCSTLockState extends LockState {
	//Rule(a)
	public VectorClock Cm;
	
	//Rule(b)
	public final HashMap<ShadowThread,PerThreadQueue<VectorClock>> AcqQueueMap = new HashMap<ShadowThread,PerThreadQueue<VectorClock>>();
	public final HashMap<ShadowThread,PerThreadQueue<VectorClock>> RelQueueMap = new HashMap<ShadowThread,PerThreadQueue<VectorClock>>();
	public final PerThreadQueue<VectorClock> AcqQueueGlobal = new PerThreadQueue<VectorClock>();
	public final PerThreadQueue<VectorClock> RelQueueGlobal = new PerThreadQueue<VectorClock>();
	
	public DCSTLockState(ShadowLock peer, int size) {
		super(peer, size);
		this.Cm = new VectorClock(size);
	}
}

class WDCLockState extends LockState {
	//Rule(a)
	public HashSet<ShadowVar> readVars = new HashSet<ShadowVar>();
	public HashSet<ShadowVar> writeVars = new HashSet<ShadowVar>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> WriteMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	public WeakIdentityHashMap<ShadowVar,VectorClock> ReadMap = new WeakIdentityHashMap<ShadowVar,VectorClock>();
	
	public WDCLockState(ShadowLock peer, int size) {
		super(peer, size);
	}
}

class WDCSTLockState extends LockState {
	//Rule(a)
	public VectorClock Cm;
	
	public WDCSTLockState(ShadowLock peer, int size) {
		super(peer, size);
		this.Cm = new VectorClock(size);
	}
}