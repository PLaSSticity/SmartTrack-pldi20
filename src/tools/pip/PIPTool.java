package tools.pip;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

import acme.util.Assert;
import acme.util.Util;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.identityhash.WeakIdentityHashMap;
import acme.util.option.CommandLine;
import rr.annotations.Abbrev;
import rr.barrier.BarrierEvent;
import rr.barrier.BarrierListener;
import rr.barrier.BarrierMonitor;
import rr.error.ErrorMessage;
import rr.error.ErrorMessages;
import rr.event.AccessEvent;
import rr.event.AccessEvent.Kind;
import rr.event.AcquireEvent;
import rr.event.ArrayAccessEvent;
import rr.event.ClassAccessedEvent;
import rr.event.ClassInitializedEvent;
import rr.event.FieldAccessEvent;
import rr.event.JoinEvent;
import rr.event.NewThreadEvent;
import rr.event.ReleaseEvent;
import rr.event.StartEvent;
import rr.event.VolatileAccessEvent;
import rr.event.WaitEvent;
import rr.instrument.classes.ArrayAllocSiteTracker;
import rr.meta.ArrayAccessInfo;
import rr.meta.ClassInfo;
import rr.meta.FieldInfo;
import rr.meta.MetaDataInfoMaps;
import rr.meta.OperationInfo;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;
import tools.util.Epoch;
import tools.util.VectorClock;
import tools.wdc.PerThreadQueue;

@Abbrev("PIP")
public class PIPTool extends Tool implements BarrierListener<PIPBarrierState> {

	private static final boolean COUNT_EVENTS = RR.countEventOption.get();
	private static final boolean COUNT_RACES = RR.countRaceOption.get();
	private static final boolean PRINT_EVENTS = RR.printEventOption.get();
	private static final int INIT_VECTOR_CLOCK_SIZE = 4;
	
	// Relations/Analyses
	private static final boolean HB = RR.pipHBOption.get();
	private static final boolean WCP = RR.pipWCPOption.get();
	private static final boolean DC = RR.pipDCOption.get();
	private static final boolean WDC = RR.pipCAPOOption.get();
	
	// Optimizations
	private static final boolean FTO = RR.pipFTOOption.get();
	private static final boolean ST = RR.pipREOption.get();
	
	private static final boolean DEBUG = RR.debugOption.get();
	
	// Counters for relative frequencies of each access type
	private static final ThreadLocalCounter readSameEpoch = new ThreadLocalCounter("PIP", "Read Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedSameEpoch = new ThreadLocalCounter("PIP", "Read Shared Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter readExclusive = new ThreadLocalCounter("PIP", "Read Exclusive", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOwned = new ThreadLocalCounter("PIP", "Read Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShare = new ThreadLocalCounter("PIP", "Read Share", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShared = new ThreadLocalCounter("PIP", "Read Shared", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedOwned = new ThreadLocalCounter("PIP", "Read Shared Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeReadError = new ThreadLocalCounter("PIP", "Write-Read Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSameEpoch = new ThreadLocalCounter("PIP", "Write Same Epoch", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeExclusive = new ThreadLocalCounter("PIP", "Write Exclusive", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOwned = new ThreadLocalCounter("PIP", "Write Owned", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeShared = new ThreadLocalCounter("PIP", "Write Shared", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeWriteError = new ThreadLocalCounter("PIP", "Write-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter readWriteError = new ThreadLocalCounter("PIP", "Read-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter sharedWriteError = new ThreadLocalCounter("PIP", "Shared-Write Error", RR.maxTidOption.get());
	private static final ThreadLocalCounter acquire = new ThreadLocalCounter("PIP", "Acquire", RR.maxTidOption.get());
	private static final ThreadLocalCounter release = new ThreadLocalCounter("PIP", "Release", RR.maxTidOption.get());
	private static final ThreadLocalCounter fork = new ThreadLocalCounter("PIP", "Fork", RR.maxTidOption.get());
	private static final ThreadLocalCounter join = new ThreadLocalCounter("PIP", "Join", RR.maxTidOption.get());
	private static final ThreadLocalCounter barrier = new ThreadLocalCounter("PIP", "Barrier", RR.maxTidOption.get());
	private static final ThreadLocalCounter preWait = new ThreadLocalCounter("PIP", "Pre Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter postWait = new ThreadLocalCounter("PIP", "Post Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter classInit = new ThreadLocalCounter("PIP", "Class Initialized", RR.maxTidOption.get());
	private static final ThreadLocalCounter classAccess = new ThreadLocalCounter("PIP", "Class Accessed", RR.maxTidOption.get());
	private static final ThreadLocalCounter vol = new ThreadLocalCounter("PIP", "Volatile", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter readFP = new ThreadLocalCounter("PIP", "Read Fast Path Taken", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeFP = new ThreadLocalCounter("PIP", "Write Fast Path Taken", RR.maxTidOption.get());
	
	// Counters for relative frequencies of each access type at fast paths
	private static final ThreadLocalCounter readSameEpochFP = new ThreadLocalCounter("PIP", "Read Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedSameEpochFP = new ThreadLocalCounter("PIP", "Read Shared Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readExclusiveFP = new ThreadLocalCounter("PIP", "Read Exclusive FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOwnedFP = new ThreadLocalCounter("PIP", "Read Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readShareFP = new ThreadLocalCounter("PIP", "Read Share FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedFP = new ThreadLocalCounter("PIP", "Read Shared FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter readSharedOwnedFP = new ThreadLocalCounter("PIP", "Read Shared Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSameEpochFP = new ThreadLocalCounter("PIP", "Write Same Epoch FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeExclusiveFP = new ThreadLocalCounter("PIP", "Write Exclusive FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOwnedFP = new ThreadLocalCounter("PIP", "Write Owned FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeSharedFP = new ThreadLocalCounter("PIP", "Write Shared FP", RR.maxTidOption.get());
	
	// Counters for relative frequencies of accesses while lock is held and not held
	private static final ThreadLocalCounter writeIN = new ThreadLocalCounter("PIP", "Write accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeINFP = new ThreadLocalCounter("PIP", "Write accesses Inside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOUT = new ThreadLocalCounter("PIP", "Write accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter writeOUTFP = new ThreadLocalCounter("PIP", "Write accesses Outside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter readIN = new ThreadLocalCounter("PIP", "Read accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter readINFP = new ThreadLocalCounter("PIP", "Read accesses Inside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOUT = new ThreadLocalCounter("PIP", "Read accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter readOUTFP = new ThreadLocalCounter("PIP", "Read accesses Outside Critical Sections succeeding Fast Path", RR.maxTidOption.get());
	
	// Counters for relative frequencies of ``extra'' metadata 
	private static final ThreadLocalCounter extraWriteSetFP = new ThreadLocalCounter("PIP", "Extra Write Set FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter extraWriteCheckFP = new ThreadLocalCounter("PIP", "Extra Write Check FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter extraWriteUpdateFP = new ThreadLocalCounter("PIP", "Extra Write Update FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter extraReadCheckFP = new ThreadLocalCounter("PIP", "Extra Read Check FP", RR.maxTidOption.get());
	private static final ThreadLocalCounter extraReadUpdateFP = new ThreadLocalCounter("PIP", "Extra Read Update FP", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter other = new ThreadLocalCounter("PIP", "Other", RR.maxTidOption.get());
	
	// Counters for relative frequencies of nested locks held during Rd/Wr events
	private static final ThreadLocalCounter holdLocks = new ThreadLocalCounter("PIP", "Holding Lock during Access Event", RR.maxTidOption.get());
	private static final ThreadLocalCounter oneLockHeld = new ThreadLocalCounter("PIP", "One Lock Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter twoNestedLocksHeld = new ThreadLocalCounter("PIP", "Two Nested Locks Held", RR.maxTidOption.get());
	private static final ThreadLocalCounter threeNestedLocksHeld = new ThreadLocalCounter("PIP", "Three Nested Locks Held", RR.maxTidOption.get());
	
	static {
		AggregateCounter reads = new AggregateCounter("PIP", "Total Reads", readSameEpoch, readSharedSameEpoch, readExclusive, readShare, readShared, writeReadError);
		AggregateCounter writes = new AggregateCounter("PIP", "Total Writes", writeSameEpoch, writeExclusive, writeShared, writeWriteError, readWriteError, sharedWriteError);
		AggregateCounter accesses = new AggregateCounter("PIP", "Total Access Ops", reads, writes);
		new AggregateCounter("PIP", "Total Ops", accesses, acquire, release, fork, join, barrier, preWait, postWait, classInit, classAccess, vol, other);
		new AggregateCounter("PIP", "Total Fast Path Taken", readFP, writeFP);
	}
	
	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage("PIP");
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage("PIP");
	private final VectorClock maxEpochPerTid = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
	
	public static final Decoration<ClassInfo, VectorClock> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("PIP:ClassInitTime", Type.MULTIPLE,
			new DefaultValue<ClassInfo, VectorClock>() {
		public VectorClock get(ClassInfo st) {
			return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
		}
	});
	
	public static final Decoration<ClassInfo, VectorClock> classInitTimeWCPHB = MetaDataInfoMaps.getClasses().makeDecoration("PIPwcphb:ClassInitTime", Type.MULTIPLE,
			new DefaultValue<ClassInfo, VectorClock>() {
		public VectorClock get(ClassInfo st) {
			return new VectorClock(INIT_VECTOR_CLOCK_SIZE);
		}
	});
	
	public PIPTool(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<PIPBarrierState>(this, new DefaultValue<Object,PIPBarrierState>() {
			public PIPBarrierState get(Object k) {
				return new PIPBarrierState(k, INIT_VECTOR_CLOCK_SIZE);
			}
		});
		//Remove error reporting limit for comparison with PIP tools
		fieldErrors.setMax(Integer.MAX_VALUE);
		arrayErrors.setMax(Integer.MAX_VALUE);
	}
	
	//HB
	protected static int/*epoch*/ ts_get_eHB(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eHB(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vHB(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vHB(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	//WCP
	protected static int/*epoch*/ ts_get_eWCP(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eWCP(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vWCP(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vWCP(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsWCPST(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsWCPST(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }
	
	//DC
	protected static int/*epoch*/ ts_get_eDC(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eDC(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vDC(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vDC(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsDCST(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsDCST(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }
	
	//WDC
	protected static int/*epoch*/ ts_get_eWDC(ShadowThread st) { Assert.panic("Bad"); return -1; }
	protected static void ts_set_eWDC(ShadowThread st, int/*epoch*/ e) { Assert.panic("Bad"); }
	
	protected static VectorClock ts_get_vWDC(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_vWDC(ShadowThread st, VectorClock V) { Assert.panic("Bad"); }
	
	protected static HeldLS ts_get_heldlsWDCST(ShadowThread st) { Assert.panic("Bad"); return null; }
	protected static void ts_set_heldlsWDCST(ShadowThread st, HeldLS heldLS) { Assert.panic("Bad"); }
	
	static final HeldLS getHLS(final ShadowThread td) {
		if (WCP && ST) {
			return ts_get_heldlsWCPST(td);
		}
		if (DC && ST) {
			return ts_get_heldlsDCST(td);
		}
		if (WDC && ST) {
			return ts_get_heldlsWDCST(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return null;
	}
	
	static final void setHLS(final ShadowThread td, final HeldLS heldLS) {
		if (WCP && ST) {
			ts_set_heldlsWCPST(td, heldLS);
		}
		if (DC && ST) {
			ts_set_heldlsDCST(td, heldLS);
		}
		if (WDC && ST) {
			ts_set_heldlsWDCST(td, heldLS);
		}
	}
	
	static final VectorClock getV(final ShadowThread td) {
		if (HB) {
			return ts_get_vHB(td);
		}
		if (WCP) {
			return ts_get_vWCP(td);
		}
		if (DC) {
			return ts_get_vDC(td);
		}
		if (WDC) {
			return ts_get_vWDC(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return null;
	}
	
	static final void setV(final ShadowThread td, final VectorClock V) {
		if (HB) {
			ts_set_vHB(td, V);
		}
		if (WCP) {
			ts_set_vWCP(td, V);
		}
		if (DC) {
			ts_set_vDC(td, V);
		}
		if (WDC) {
			ts_set_vWDC(td, V);
		}
	}
	
	static final int/*epoch*/ getE(final ShadowThread td) {
		if (HB) {
			return ts_get_eHB(td);
		}
		if (WCP) {
			return ts_get_eWCP(td);
		}
		if (DC) {
			return ts_get_eDC(td);
		}
		if (WDC) {
			return ts_get_eWDC(td);
		}
		Assert.assertTrue(false); //Should never reach here
		return -1;
	}
	
	static final void setE(final ShadowThread td, final int/*epoch*/ e) {
		if (HB) {
			ts_set_eHB(td, e);
		}
		if (WCP) {
			ts_set_eWCP(td, e);
		}
		if (DC) {
			ts_set_eDC(td, e);
		}
		if (WDC) {
			ts_set_eWDC(td, e);
		}
	}
	
	static final Decoration<ShadowLock,LockState> lockVhb = ShadowLock.makeDecoration("PIPhb:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,LockState>() { public LockState get(final ShadowLock lock) { return new LockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WCPLockState> lockVwcp = ShadowLock.makeDecoration("PIPwcp:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WCPLockState>() { public WCPLockState get(final ShadowLock lock) { return new WCPLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WCPSTLockState> lockVwcpST = ShadowLock.makeDecoration("PIPwcpST:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WCPSTLockState>() { public WCPSTLockState get(final ShadowLock lock) { return new WCPSTLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,DCLockState> lockVdc = ShadowLock.makeDecoration("PIPdc:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,DCLockState>() { public DCLockState get(final ShadowLock lock) { return new DCLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,DCSTLockState> lockVdcST = ShadowLock.makeDecoration("PIPdcST:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,DCSTLockState>() { public DCSTLockState get(final ShadowLock lock) { return new DCSTLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WDCLockState> lockVwdc = ShadowLock.makeDecoration("PIPWDC:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WDCLockState>() { public WDCLockState get(final ShadowLock lock) { return new WDCLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowLock,WDCSTLockState> lockVwdcST = ShadowLock.makeDecoration("PIPWDCST:ShadowLock", DecorationFactory.Type.MULTIPLE, 
			new DefaultValue<ShadowLock,WDCSTLockState>() { public WDCSTLockState get(final ShadowLock lock) { return new WDCSTLockState(lock, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final LockState getV(final ShadowLock ld) {
		if (HB) {
			return lockVhb.get(ld);
		}
		if (WCP) {
			if (ST)	return lockVwcpST.get(ld);
			if (!ST) return lockVwcp.get(ld);
		}
		if (DC) {
			if (ST) return lockVdcST.get(ld);
			if (!ST) return lockVdc.get(ld);
		}
		if (WDC) {
			if (ST) return lockVwdcST.get(ld);
			if (!ST) return lockVwdc.get(ld);
		}
		Assert.assertTrue(false); //Should never get here
		return null;
	}
	
	static final Decoration<ShadowVolatile,PIPVolatileState> volatileV = ShadowVolatile.makeDecoration("PIP:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,PIPVolatileState>() { public PIPVolatileState get(final ShadowVolatile vol) { return new PIPVolatileState(vol, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final Decoration<ShadowVolatile,PIPVolatileState> volatileVwcpHB = ShadowVolatile.makeDecoration("PIPwcpHB:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,PIPVolatileState>() { public PIPVolatileState get(final ShadowVolatile vol) { return new PIPVolatileState(vol, INIT_VECTOR_CLOCK_SIZE); }});
	
	static final PIPVolatileState getV(final ShadowVolatile ld) {
		return volatileV.get(ld);
	}
	
	@Override
	public ShadowVar makeShadowVar(final AccessEvent event) {
		if (event.getKind() == Kind.VOLATILE) {
			final ShadowThread st = event.getThread();
			final VectorClock volV = getV(((VolatileAccessEvent)event).getShadowVolatile());
			volV.max(getV(st));
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(((VolatileAccessEvent)event).getShadowVolatile());
				volVhb.max(ts_get_vHB(st));
			}
			return super.makeShadowVar(event);
		} else {
			if ((WCP && ST) || (DC && ST) || (WDC && ST)) {
				STVarState x;
				if (WCP) {
					x = new STVarState(event.isWrite(), ts_get_eHB(event.getThread()), FTO);
				} else {
					x = new STVarState(event.isWrite(), getE(event.getThread()), FTO);
				}
				//Update Rule(a) metadata
				final ShadowThread st = event.getThread();
				if (event.isWrite()) x.Wm = getHLS(st);
				x.Rm = getHLS(st);
				return x;
			} else {
				PIPVarState sx;
				if (WCP) {
					sx = new PIPVarState(event.isWrite(), ts_get_eHB(event.getThread()), FTO);
				} else {
					sx = new PIPVarState(event.isWrite(), getE(event.getThread()), FTO);
				}
				if (FTO && !HB) { //Rule (a) [so HB should skip this]
					if (DEBUG) Assert.assertTrue(!ST);
					ShadowThread td = event.getThread();
					for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (WDC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WDCLockState);
							WDCLockState lockData = (WDCLockState)getV(lock);
							//Update write/read Vars
							if (event.isWrite()) lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
					}
				}
				return sx;
			}
		}
	}
	
	@Override
	public void create(NewThreadEvent event) {
		final ShadowThread td = event.getThread();
			if (getV(td) == null) {
			final int tid = td.getTid();
			final VectorClock tV = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			setV(td, tV);
			synchronized(maxEpochPerTid) {
				final int/*epoch*/ epoch = maxEpochPerTid.get(tid) + 1;
				tV.set(tid, epoch);
				setE(td, epoch);
			}
			if (!WCP) incEpochAndCV(td);
			if (PRINT_EVENTS) Util.log("Initial Epoch for " + tid + ": " + Epoch.toString(getE(td)));
		}
		setHLS(td, null);
		if (WCP) { //WCP needs to track HB for left and right composition
			if (ts_get_vHB(td) == null) {
				final int tid = td.getTid();
				final VectorClock tVHB = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				ts_set_vHB(td, tVHB);
				synchronized(maxEpochPerTid) {
					final int/*epoch*/ epoch = maxEpochPerTid.get(tid) + 1;
					tVHB.set(tid, epoch);
					ts_set_eHB(td, epoch);
				}
				tVHB.tick(tid);
				ts_set_eHB(td, tVHB.get(tid));
				if (PRINT_EVENTS) Util.log("Initial Epoch for " + tid + ": " + Epoch.toString(ts_get_eHB(td)));
			}
		}
		super.create(event);
	}
	
	@Override
	public void init() {
		if (COUNT_EVENTS) {
			Util.log("HB analysis: " + HB);
			Util.log("WCP analysis: " + WCP);
			Util.log("DC analysis: " + DC);
			Util.log("WDC analysis: " + WDC);
			Util.log("FTO enabled: " + FTO);
			Util.log("ST enabled: " + ST);
		}
		if (ST) {
			Assert.assertTrue(FTO);
		}
	}
	
	@Override
	public void fini() {
		StaticRace.reportRaces();
	}
	
	protected void maxAndIncEpochAndCV(ShadowThread st, VectorClock other) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.max(other);
		tV.tick(tid);
		setE(st, tV.get(tid));
	}
	
	protected static void maxEpochAndCV(ShadowThread st, VectorClock other) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.max(other);
		setE(st, tV.get(tid));
	}
	
	protected void incEpochAndCV(ShadowThread st) {
		final int tid = st.getTid();
		final VectorClock tV = getV(st);
		tV.tick(tid);
		setE(st, tV.get(tid));
	}
	
	@Override
	public void acquire(final AcquireEvent event) {
		if (COUNT_EVENTS) acquire.inc(event.getThread().getTid());
		
		final ShadowThread td = event.getThread();
		
		if (HB || WCP || DC || (WDC && ST)) {
			final ShadowLock lock = event.getLock();
			
			handleAcquire(td, lock, event.getInfo());
		} else {
			incAtAcquire(td);
		}
		
		if (PRINT_EVENTS) Util.log("acq("+event.getLock()+") by T"+event.getThread().getTid());
		super.acquire(event);
	}
	
	public void incAtAcquire(ShadowThread td) {
		// Increment first to distinguish accesses outside critical sections from accesses inside critical sections
		if (!HB && !WCP) {
			incEpochAndCV(td);
		}
		//Usually HB does not increment at acquires, but to prevent accesses
		//before and after acquires from being same-epoch HB increments here and then wcp union po
		//should not take the fast path.
		if (WCP) {
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
	}
	
	public void handleAcquire(ShadowThread td, ShadowLock lock, OperationInfo info) {
		final LockState lockV = getV(lock);
		
		if (HB) {
			maxEpochAndCV(td, lockV);
		}
		if (WCP) {
			if (ST) {
				ts_get_vHB(td).max(((WCPSTLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, lockV);
			}
			if (!ST) {
				ts_get_vHB(td).max(((WCPLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, lockV);
			}
		}
		
		if (WCP) {
			if (ST) {
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPSTLockState);
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPSTLockState lockData = (WCPSTLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				WCPSTLockState outerMostLock = (WCPSTLockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					WCPSTLockState heldLockData = (WCPSTLockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!ST) {
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPLockState lockData = (WCPLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (DC) {
			if (ST) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCSTLockState);
				DCSTLockState lockData = (DCSTLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				DCSTLockState outerMostLock = (DCSTLockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					DCSTLockState heldLockData = (DCSTLockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!ST) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				DCLockState lockData = (DCLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (WDC && ST) {
			//create a new vector clock C_m
			if (DEBUG) Assert.assertTrue(lockV instanceof WDCSTLockState);
			WDCSTLockState lockData = (WDCSTLockState) lockV;
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString() + "|tid: " + td.getTid() + "|c at t: " + Epoch.clock(lockData.Cm.get(td.getTid())));
			//Update last Rule(a) metadata
			//Build a new list of held locks
			WDCSTLockState outerMostLock = (WDCSTLockState)getV(td.getHeldLock(0));
			HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
			setHLS(td, thrLock);
			for (int i = 1; i < td.getNumLocksHeld(); i++) {
				ShadowLock heldLock = td.getHeldLock(i);
				WDCSTLockState heldLockData = (WDCSTLockState)getV(heldLock);
				//Store a shallow reference to Cm: from outer most to inner most lock held
				thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
				thrLock = thrLock.next;
			}
		}
		
		if (!HB) {
			incAtAcquire(td);
		}
	}
	
	public void handleAcquireHardEdge(ShadowThread td, ShadowLock lock, OperationInfo info) {
		final LockState lockV = getV(lock);
		
		if (WCP) {
			if (ST) {
				ts_get_vHB(td).max(((WCPSTLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				
				getV(td).max(((WCPSTLockState)lockV).hb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid()));
			}
			if (!ST) {
				ts_get_vHB(td).max(((WCPLockState)lockV).hb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				
				getV(td).max(((WCPLockState)lockV).hb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid()));
			}
		}
		if (!WCP) {
			maxEpochAndCV(td, lockV);
		}
		
		if (WCP) {
			if (ST) {
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPSTLockState);
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPSTLockState lockData = (WCPSTLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				WCPSTLockState outerMostLock = (WCPSTLockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					WCPSTLockState heldLockData = (WCPSTLockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!ST) {
				VectorClock wcpUnionPO = new VectorClock(getV(td));
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				WCPLockState lockData = (WCPLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					ArrayDeque<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(wcpUnionPO);
					}
				}
				lockData.AcqQueueGlobal.addLast(wcpUnionPO);
				ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (DC) {
			if (ST) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCSTLockState);
				DCSTLockState lockData = (DCSTLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
				//Rule(a)
				//create a new vector clock C_m
				lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
				lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				DCSTLockState outerMostLock = (DCSTLockState)getV(td.getHeldLock(0));
				HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
				setHLS(td, thrLock);
				for (int i = 1; i < td.getNumLocksHeld(); i++) {
					ShadowLock heldLock = td.getHeldLock(i);
					DCSTLockState heldLockData = (DCSTLockState)getV(heldLock);
					//Store a shallow reference to Cm: from outer most to inner most lock held
					thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
					thrLock = thrLock.next;
				}
			}
			if (!ST) {
				//Rule (b)
				VectorClock copyDC = new VectorClock(getV(td));
				DCLockState lockData = (DCLockState) lockV;
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					PerThreadQueue<VectorClock> queue = lockData.AcqQueueMap.get(tdOther);
					if (queue == null) {
						queue = lockData.AcqQueueGlobal.clone();
						lockData.AcqQueueMap.put(tdOther, queue);
					}
					if (tdOther != td) {
						queue.addLast(td, copyDC);
					}
				}
				lockData.AcqQueueGlobal.addLast(td, copyDC);
				PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(td);
				if (queue == null) {
					queue = lockData.RelQueueGlobal.clone();
					lockData.RelQueueMap.put(td, queue);
				}
			}
		}
		if (WDC && ST) {
			//create a new vector clock C_m
			if (DEBUG) Assert.assertTrue(lockV instanceof WDCSTLockState);
			WDCSTLockState lockData = (WDCSTLockState) lockV;
			lockData.Cm = new VectorClock(INIT_VECTOR_CLOCK_SIZE);
			lockData.Cm.set(td.getTid(), Epoch.make(td.getTid(), Epoch.MAX_CLOCK)); //To indicate shallow copy Cm has not been set by release event yet
			if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
			//Update last Rule(a) metadata
			//Build a new list of held locks
			WDCSTLockState outerMostLock = (WDCSTLockState)getV(td.getHeldLock(0));
			HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
			setHLS(td, thrLock);
			for (int i = 1; i < td.getNumLocksHeld(); i++) {
				ShadowLock heldLock = td.getHeldLock(i);
				WDCSTLockState heldLockData = (WDCSTLockState)getV(heldLock);
				//Store a shallow reference to Cm: from outer most to inner most lock held
				thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
				thrLock = thrLock.next;
			}
		}
		
		incAtAcquire(td);
	}
	
	@Override
	public void release(final ReleaseEvent event) {
		final ShadowThread td = event.getThread();
		final LockState lockV = getV(event.getLock());
		
		if (COUNT_EVENTS) release.inc(td.getTid());
		
		handleRelease(td, lockV, event.getInfo());
		
		if (PRINT_EVENTS) Util.log("rel("+event.getLock()+") by T"+td.getTid()); //Util.log("rel("+Util.objectToIdentityString(event.getLock())+") by T"+td.getTid());
		super.release(event);
	}
	
	public void handleRelease(ShadowThread td, LockState lockV, OperationInfo info) {
		final VectorClock tV = getV(td);
		
		if (WCP) {
			if (ST) {
				VectorClock wcpUnionPO = new VectorClock(tV);
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPSTLockState);
				WCPSTLockState lockData = (WCPSTLockState) lockV;
				ArrayDeque<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				ArrayDeque<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
					acqQueue.removeFirst();
					maxEpochAndCV(td, relQueue.removeFirst());
				}
				//Rule (a)
				//Update the vector clock that was shallow-copied during the current critical section
				lockData.Cm.copy(ts_get_vHB(td));
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					WCPSTLockState outerMostLock = (WCPSTLockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						WCPSTLockState heldLockData = (WCPSTLockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!ST) {
				VectorClock wcpUnionPO = new VectorClock(tV);
				wcpUnionPO.set(td.getTid(), ts_get_eHB(td));
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPLockState);
				WCPLockState lockData = (WCPLockState) lockV;
				ArrayDeque<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				ArrayDeque<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
					acqQueue.removeFirst();
					maxEpochAndCV(td, relQueue.removeFirst());
				}
				//Rule (a)
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(ts_get_vHB(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(ts_get_vHB(td));
				}
			}
		}
		
		if (DC) {
			if (ST) {
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof DCSTLockState);
				DCSTLockState lockData = (DCSTLockState) lockV;
				PerThreadQueue<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				PerThreadQueue<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						while (!acqQueue.isEmpty(tdOther) && !acqQueue.peekFirst(tdOther).anyGt(getV(td))) {
							acqQueue.removeFirst(tdOther);
							maxEpochAndCV(td, relQueue.removeFirst(tdOther));
						}
					}
				}
				//Rule (a)
				//Update the vector clock that was shallow-copied during the current critical section
				lockData.Cm.copy(getV(td));
				if (PRINT_EVENTS) Util.log(lockData.Cm.toString());
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					DCSTLockState outerMostLock = (DCSTLockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						DCSTLockState heldLockData = (DCSTLockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!ST) {
				//Rule (b)
				if (DEBUG) Assert.assertTrue(lockV instanceof DCLockState);
				DCLockState lockData = (DCLockState) lockV;
				PerThreadQueue<VectorClock> acqQueue = lockData.AcqQueueMap.get(td);
				PerThreadQueue<VectorClock> relQueue = lockData.RelQueueMap.get(td);
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						while (!acqQueue.isEmpty(tdOther) && !acqQueue.peekFirst(tdOther).anyGt(getV(td))) {
							acqQueue.removeFirst(tdOther);
							maxEpochAndCV(td, relQueue.removeFirst(tdOther));
						}
					}
				}
				//Rule (a)
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(getV(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(getV(td));
				}
			}
		}
		
		if (WDC) {
			if (ST) {
				//Update the vector clock that was shallow-copied during the current critical section
				if (DEBUG) Assert.assertTrue(lockV instanceof WDCSTLockState);
				WDCSTLockState lockData = (WDCSTLockState) lockV;
				lockData.Cm.copy(getV(td));
				if (PRINT_EVENTS) Util.log("AFTER: " + lockData.Cm.toString() + " |max epoch: " + Epoch.clock(Epoch.MAX_CLOCK));
				//Update last Rule(a) metadata
				//Build a new list of held locks
				if (td.getNumLocksHeld() == 0) {
					setHLS(td, null);
				} else {
					WDCSTLockState outerMostLock = (WDCSTLockState)getV(td.getHeldLock(0));
					HeldLS thrLock = new HeldLS(td.getHeldLock(0), outerMostLock.Cm); //Outer most lock held
					setHLS(td, thrLock);
					for (int i = 1; i < td.getNumLocksHeld(); i++) {
						ShadowLock heldLock = td.getHeldLock(i);
						WDCSTLockState heldLockData = (WDCSTLockState)getV(heldLock);
						//Store a shallow reference to Cm: from outer most to inner most lock held
						thrLock.next = new HeldLS(heldLock, heldLockData.Cm);
						thrLock = thrLock.next;
					}
				}
			}
			if (!ST) {
				//Rule (a)
				if (DEBUG) Assert.assertTrue(lockV instanceof WDCLockState);
				WDCLockState lockData = (WDCLockState) lockV;
				for (ShadowVar var : lockData.readVars) {
					VectorClock cv = lockData.ReadMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.ReadMap.put(var, cv);
					}
					cv.max(getV(td));
				}
				for (ShadowVar var : lockData.writeVars) {
					VectorClock cv = lockData.WriteMap.get(var);
					if (cv == null) {
						cv = new VectorClock(PIPTool.INIT_VECTOR_CLOCK_SIZE);
						lockData.WriteMap.put(var, cv);
					}
					cv.max(getV(td));
				}
			}
		}
		
		//Assign to lock
		if (WCP) {
			if (ST) ((WCPSTLockState)lockV).hb.max(ts_get_vHB(td));
			if (!ST) ((WCPLockState)lockV).hb.max(ts_get_vHB(td));
		}
		lockV.max(tV); // Used for hard notify -> wait edge
		
		if (WCP) {
			if (ST) {
				VectorClock copyHB = new VectorClock(ts_get_vHB(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPSTLockState);
				WCPSTLockState lockData = (WCPSTLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(copyHB);
					}
				}
				lockData.RelQueueGlobal.addLast(copyHB);
			}
			if (!ST) {
				VectorClock copyHB = new VectorClock(ts_get_vHB(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof WCPLockState);
				WCPLockState lockData = (WCPLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						ArrayDeque<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(copyHB);
					}
				}
				lockData.RelQueueGlobal.addLast(copyHB);
				//Clear
				lockData.readVars = new HashSet<ShadowVar>();
				lockData.writeVars = new HashSet<ShadowVar>();
				lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
				lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}
		
		if (DC) {
			if (ST) {
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCSTLockState);
				DCSTLockState lockData = (DCSTLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(td, copyDC);
					}
				}
				lockData.RelQueueGlobal.addLast(td, copyDC);
			}
			if (!ST) {
				VectorClock copyDC = new VectorClock(getV(td));
				if (DEBUG) Assert.assertTrue(lockV instanceof DCLockState);
				DCLockState lockData = (DCLockState) lockV;
				//Rule (b)
				for (ShadowThread tdOther : ShadowThread.getThreads()) {
					if (tdOther != td) {
						PerThreadQueue<VectorClock> queue = lockData.RelQueueMap.get(tdOther);
						if (queue == null) {
							queue = lockData.RelQueueGlobal.clone();
							lockData.RelQueueMap.put(tdOther, queue);
						}
						queue.addLast(td, copyDC);
					}
				}
				lockData.RelQueueGlobal.addLast(td, copyDC);
				//Clear
				lockData.readVars = new HashSet<ShadowVar>();
				lockData.writeVars = new HashSet<ShadowVar>();
				lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
				lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}
		
		if (WDC) {
			if (!ST) {
				if (DEBUG) Assert.assertTrue(lockV instanceof WDCLockState);
				WDCLockState lockData = (WDCLockState) lockV;
				lockData.ReadMap = getPotentiallyShrunkMap(lockData.ReadMap);
				lockData.WriteMap = getPotentiallyShrunkMap(lockData.WriteMap);
			}
		}

		//Increments last
		if (WCP) {
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			incEpochAndCV(td);
		}
	}
	
	static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K,V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}
	
	static PIPVarState ts_get_badVarState(ShadowThread st) { Assert.panic("Bad");	return null;	}
	static void ts_set_badVarState(ShadowThread st, PIPVarState v) { Assert.panic("Bad");  }

	protected static ShadowVar getOriginalOrBad(ShadowVar original, ShadowThread st) {
		final PIPVarState savedState = ts_get_badVarState(st);
		if (savedState != null) {
			ts_set_badVarState(st, null);
			return savedState;
		} else {
			return original;
		}
	}
	
	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		final PIPVarState sx = ((PIPVarState)orig);

		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}

		/* optional */ {
			final int/*epoch*/ r = sx.R;
			if (r == e) {
				if (COUNT_EVENTS) readSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				return true;
			} else if (r == Epoch.READ_SHARED && sx.get(td.getTid()) == e) {
				if (COUNT_EVENTS) readSharedSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				return true;
			}
		}

		if (HB || ST || FTO || td.getNumLocksHeld() == 0) {
			synchronized(sx) {
				final int tid = td.getTid();
				final VectorClock tV = getV(td);
				if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
				final int/*epoch*/ r = sx.R;
				final int/*epoch*/ w = sx.W;
				final int wTid = Epoch.tid(w);
				
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() > 0) {
						holdLocks.inc(tid);
						if (td.getNumLocksHeld() == 1) {
							oneLockHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 2) {
							twoNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 3) {
							threeNestedLocksHeld.inc(tid);
						}
					}
				}
				
				//([WCP/DC/WDC] + ST) + FTO
				if (ST) {
					STVarState xSTCount = (STVarState)sx;
					if (xSTCount.Ew != null && !xSTCount.Ew.isEmpty()) {
						if (COUNT_EVENTS) {
							extraReadCheckFP.inc(td.getTid());
							//Check if any update occurs at least once
							boolean update = false;
							for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
								ShadowLock lock = td.getHeldLock(i);
								for (int prevTid : xSTCount.Ew.keySet()) {
									if (prevTid != tid && xSTCount.Ew.get(prevTid).containsKey(lock)) {
										extraReadUpdateFP.inc(td.getTid());
										update = true;
										break;
									}
								}
								if (update) break;
							}
						}
							
						for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
							ShadowLock lock = td.getHeldLock(i);
							for (int prevTid : xSTCount.Ew.keySet()) {
								if (prevTid != tid && xSTCount.Ew.get(prevTid).containsKey(lock)) {
									maxEpochAndCV(td, xSTCount.Ew.get(prevTid).get(lock));
								}
							}
						}
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid) { //Read-Owned, Rule(a) Check is unneeded for read-owned case since the prior write access was on the same thread
							//Update last Rule(a) metadata
							STVarState xST = (STVarState)sx;
							xST.Rm = getHLS(td);
							//Update last access metadata
							sx.R = e; //readOwned
							if (COUNT_EVENTS) readOwnedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd owned FP");
						} else {
							STVarState xST = (STVarState)sx;
							HeldLS rdLock = xST.Rm;
							//If prior access was not protected by a lock and the prior access is not ordered to the current access
							//Or if the outer most lock protecting the prior access is not ordered to the current access then read-share
							//Otherwise, read-exclusive
							if ((rdLock == null && !Epoch.leq(r, tV.get(rTid))) || (rdLock != null && !Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)))) {
								//Rule(a) Check
								HeldLS wrLock = xST.Wm;
								while (wrLock != null) {
									if (wTid != tid) {
										if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
											break; //Outer most lock already ordered to the current access
										} else if (td.equals(wrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
											//Establish Rule(a) and avoid checking for write-read race
											if (PRINT_EVENTS) Util.log("wr to rd-share (FP) Rule a: " + wrLock.vc.toString());
											if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
											maxEpochAndCV(td, wrLock.vc);
											if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
											break;
										}
									}
									wrLock = wrLock.next;
								}
								if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established. wTid != tid is guaranteed since rTid != tid
									ts_set_badVarState(td, sx);
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									return false;
								} //Read-Share
								//Update last Rule(a) metadata
								int initSharedHeldLSSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								xST.makeSharedHeldLS(initSharedHeldLSSize);
								xST.setSharedHeldLS(rTid, xST.Rm);
								xST.setSharedHeldLS(tid, getHLS(td));
								//Update last access metadata
								int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								sx.makeCV(initSize);
								sx.set(rTid, r);
								sx.set(tid, e);
								sx.R = Epoch.READ_SHARED; //readShare
								if (COUNT_EVENTS) readShareFP.inc(tid);
								if (PRINT_EVENTS) Util.log("rd share FP");
							} else { //Read-Exclusive
								//Rule(a) Check is unneeded for read-exclusive case since the prior write access is ordered to the current read access
								//Update last Rule(a) metadata
								xST.Rm = getHLS(td);
								//Update last access metadata
								sx.R = e; //readExclusive
								if (COUNT_EVENTS) readExclusiveFP.inc(tid);
								if (PRINT_EVENTS) Util.log("rd exclusive FP");
							}
						}
					} else { //read vector
						if (Epoch.clock(sx.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
							//Rule(a) Check is unneeded for read-shared-owned case since the prior write access is ordered to the current read access
							//Update last Rule(a) metadata
							STVarState xST = (STVarState)sx;
							xST.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							sx.set(tid, e); //readSharedOwnedq
							if (COUNT_EVENTS) readSharedOwnedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd shared owned FP");
						} else {
							//Rule(a) Check
							STVarState xST = (STVarState)sx;
							HeldLS wrLock = xST.Wm;
							while (wrLock != null) {
								if (wTid != tid) {
									if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										break; //Outer most lock already ordered to the current access
									} else if (td.equals(wrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
										//Establish Rule(a) and avoid checking for write-read race
										if (PRINT_EVENTS) Util.log("wr to rd-share (FP) Rule a: " + wrLock.vc.toString());
										if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
										maxEpochAndCV(td, wrLock.vc);
										if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
										break;
									}
								}
								wrLock = wrLock.next;
							}
							if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(td.getTid(), getE(td)); //revert WCP union PO
								return false;
							} //Read-Shared
							//Update last Rule(a) metadata
							xST.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							sx.set(tid, e); //readShared
							if (COUNT_EVENTS) readSharedFP.inc(tid);
							if (PRINT_EVENTS) Util.log("rd shared FP");
						}
					}
				}
				
				//FTO but not [WCP/DC/WDC] + ST
				if (!ST && FTO) {
					if (!HB) {
						//Establish Rule(a)
						for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
							ShadowLock lock = td.getHeldLock(i);
							if (WCP) {
								if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
								WCPLockState lockData = (WCPLockState)getV(lock);
								//Establish Rule(a)
								VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
								if (priorCSAfterAccess != null) {
									tV.set(tid, getE(td)); //revert WCP union PO
									maxEpochAndCV(td, priorCSAfterAccess);
									tV.set(tid, ts_get_eHB(td)); //WCP union PO
								}
								//Update write/read Vars
								lockData.readVars.add(sx);
							}
							if (DC) {
								if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
								DCLockState lockData = (DCLockState)getV(lock);
								//Establish Rule(a)
								VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
								//Update write/read Vars
								lockData.readVars.add(sx);
							}
							if (WDC) {
								if (DEBUG) Assert.assertTrue(getV(lock) instanceof WDCLockState);
								WDCLockState lockData = (WDCLockState)getV(lock);
								//Establish Rule(a)
								VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
								//Update write/read Vars
								lockData.readVars.add(sx);
							}
						}
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid) { //Read-Owned
							//Update last access metadata
							sx.R = e; //readOwned
							if (COUNT_EVENTS) readOwnedFP.inc(tid);
						} else {
							if (!Epoch.leq(r, tV.get(rTid))) { //Read-Share
								if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
									ts_set_badVarState(td, sx);
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									return false;
								} //Read-Share
								//Update last access metadata
								int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
								sx.makeCV(initSize);
								sx.set(rTid, r);
								sx.set(tid, e);
								sx.R = Epoch.READ_SHARED; //readShare
								if (COUNT_EVENTS) readShareFP.inc(tid);
							} else { //Read-Exclusive
								//Update last access metadata
								sx.R = e; //readExclusive
								if (COUNT_EVENTS) readExclusiveFP.inc(tid);
							}
						}
					} else { //read vector
						if (Epoch.clock(sx.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
							//Update last access metadata
							sx.set(tid, e); //readSharedOwned
							if (COUNT_EVENTS) readSharedOwnedFP.inc(tid);
						} else {
							if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Read-Shared
							//Update last access metadata
							sx.set(tid, e); //readShared
							if (COUNT_EVENTS) readSharedFP.inc(tid);
						}
					}
				}
				
				//Not FTO nor [WCP/DC/WDC] + ST
				if (!ST && !FTO) {
					//Write-Read Race Check.
					if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
						ts_set_badVarState(td, sx);
						if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
						return false;
					}
					
					if (r != Epoch.READ_SHARED) { //read epoch
						final int rTid = Epoch.tid(r);
						if (rTid == tid || Epoch.leq(r, tV.get(rTid))) { //Read-Exclusive
							//Update last access metadata
							sx.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusiveFP.inc(tid);
						} else { //Read-Share
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							sx.makeCV(initSize);
							sx.set(rTid, r);
							sx.set(tid, e);
							sx.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShareFP.inc(tid);
						}
					} else { //read vector
						//Update last access metadata
						sx.set(tid, e); //readShared
						if (COUNT_EVENTS) readSharedFP.inc(tid);
					}
				}
				
				//Counting and WCP update
				if (COUNT_EVENTS) readFP.inc(td.getTid());
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() == 0) {
						readOUT.inc(td.getTid());
						readOUTFP.inc(td.getTid());
					} else {
						readIN.inc(td.getTid());
						readINFP.inc(td.getTid());
					}
				}
				if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
				return true;
			}
		} else {
			return false;
		}
	}
	
	protected void read(final AccessEvent event, final ShadowThread td, final PIPVarState x) {
		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}
		
		if (COUNT_EVENTS) {
			if (td.getNumLocksHeld() == 0) {
				readOUT.inc(td.getTid());
			} else {
				readIN.inc(td.getTid());
			}
		}
		
		/* optional */ {
			final int/*epoch*/ r = x.R;
			if (r == e) {
				if (COUNT_EVENTS) readSameEpoch.inc(td.getTid());
				return;
			} else if (r == Epoch.READ_SHARED && x.get(td.getTid()) == e) {
				if (COUNT_EVENTS) readSharedSameEpoch.inc(td.getTid());
				return;
			}
		}
		
		synchronized(x) {
			final VectorClock tV = getV(td);
			final int/*epoch*/ r = x.R;
			final int/*epoch*/ w = x.W;
			final int wTid = Epoch.tid(w);
			final int tid = td.getTid();
			if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
			
			if (COUNT_EVENTS) {
				if (td.getNumLocksHeld() > 0) {
					holdLocks.inc(tid);
					if (td.getNumLocksHeld() == 1) {
						oneLockHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 2) {
						twoNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 3) {
						threeNestedLocksHeld.inc(tid);
					}
				}
			}
			
			//([WCP/DC/WDC] + ST) + FTO
			if (ST) {	
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid) { //Read-Owned, Rule(a) Check is unneeded for read-owned case since the prior write access was on the same thread
						//Update last Rule(a) metadata
						STVarState xST = (STVarState)x;
						xST.Rm = getHLS(td);
						//Update last access metadata
						x.R = e; //readOwned
						if (COUNT_EVENTS) readOwned.inc(tid);
					} else {
						STVarState xST = (STVarState)x;
						HeldLS rdLock = xST.Rm;
						//If prior access was not protected by a lock and the prior access is not ordered to the current access
						//Or if the outer most lock protecting the prior access is not ordered to the current access then read-share
						//Otherwise, read-exclusive
						if ((rdLock == null && !Epoch.leq(r, tV.get(rTid))) || (rdLock != null && !Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)))) {
							//Rule(a) Check
							HeldLS wrLock = xST.Wm;
							while (wrLock != null) {
								if (wTid != tid && !Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid)) && td.equals(wrLock.lock.getHoldingThread())) {
									//Establish Rule(a) and avoid checking for write-read race
									if (PRINT_EVENTS) Util.log("wr to rd-share Rule a: " + wrLock.vc.toString());
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									maxEpochAndCV(td, wrLock.vc);
									if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
									break;
								}
								wrLock = wrLock.next;
							}
							if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
								error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
								if (COUNT_EVENTS) writeReadError.inc(tid);
							} //Read-Share
							//Update last Rule(a) metadata
							int initSharedHeldLSSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							xST.makeSharedHeldLS(initSharedHeldLSSize);
							xST.setSharedHeldLS(rTid, xST.Rm);
							xST.setSharedHeldLS(tid, getHLS(td));
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							x.makeCV(initSize);
							x.set(rTid, r);
							x.set(tid, e);
							x.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShare.inc(tid);
						} else { //Read-Exclusive, Rule(a) Check is unneeded for read-exclusive case since the prior write access is ordered to the current read access
							//Update last Rule(a) metadata
							xST.Rm = getHLS(td);
							//Update last access metadata
							x.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusive.inc(tid);
						}
					}
				} else { //read vector
					if (Epoch.clock(x.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned, Rule(a) Check is unneeded for read-shared-owned case since the prior write access is ordered to the current read access
						//Update last Rule(a) metadata
						STVarState xST = (STVarState)x;
						xST.setSharedHeldLS(tid, getHLS(td));
						//Update last access metadata
						x.set(tid, e); //readSharedOwned
						if (COUNT_EVENTS) readSharedOwned.inc(tid);
					} else {
						//Rule(a) Check
						STVarState xST = (STVarState)x;
						HeldLS wrLock = xST.Wm;
						while (wrLock != null) {
							if (wTid != tid && !Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid)) && td.equals(wrLock.lock.getHoldingThread())) {
								//Establish Rule(a) and avoid check for write-read race
								if (PRINT_EVENTS) Util.log("wr to rd-shared Rule a: " + wrLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, wrLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							wrLock = wrLock.next;
						}
						if (wrLock == null && wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race. wrLock is null if Rule(a) is not established.
							error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
							if (COUNT_EVENTS) writeReadError.inc(tid);
						} //Read-Shared
						//Update last Rule(a) metadata
						xST.setSharedHeldLS(tid, getHLS(td));
						//Update last access metadata
						x.set(tid, e); //readShared
						if (COUNT_EVENTS) readShared.inc(tid);
					}
				}
			}
			
			//FTO but not [WCP/DC/WDC] + ST
			if (!ST && FTO) {
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid) { //Read-Owned
						//Update last access metadata
						x.R = e; //readOwned
						if (COUNT_EVENTS) readOwned.inc(tid);
					} else {
						if (!Epoch.leq(r, tV.get(rTid))) { //Read-Share
							if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
								if (PRINT_EVENTS) Util.log("wr-rd share error");
								error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
								if (COUNT_EVENTS) writeReadError.inc(tid);
							} //Read-Share
							//Update last access metadata
							int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
							x.makeCV(initSize);
							x.set(rTid, r);
							x.set(tid, e);
							x.R = Epoch.READ_SHARED; //readShare
							if (COUNT_EVENTS) readShare.inc(tid);
						} else { //Read-Exclusive
							//Update last access metadata
							x.R = e; //readExclusive
							if (COUNT_EVENTS) readExclusive.inc(tid);
						}
					}
				} else { //read vector
					if (Epoch.clock(x.get(tid)) != Epoch.ZERO) { //Read-Shared-Owned
						//Update last access metadata
						x.set(tid, e); //readSharedOwned
						if (COUNT_EVENTS) readSharedOwned.inc(tid);
					} else {
						if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) { //Write-Read Race.
							if (PRINT_EVENTS) Util.log("wr-rd shared error");
							error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
							if (COUNT_EVENTS) writeReadError.inc(tid);
						} //Read-Shared
						//Update last access metadata
						x.set(tid, e); //readShared
						if (COUNT_EVENTS) readShared.inc(tid);
					}
				}
			}
			
			//Not FTO nor [WCP/DC/WDC] + ST
			if (!ST && !FTO) {
				//Write-Read Race Check.
				if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
					error(event, x, "Write-Read Race", "Write by ", wTid, "Read by ", tid);
					if (COUNT_EVENTS) writeReadError.inc(tid);
				}
				
				if (r != Epoch.READ_SHARED) { //read epoch
					final int rTid = Epoch.tid(r);
					if (rTid == tid || Epoch.leq(r, tV.get(rTid))) { //Read-Exclusive
						//Update last access metadata
						x.R = e; //readExclusive
						if (COUNT_EVENTS) readExclusive.inc(tid);
					} else { //Read-Share
						//Update last access metadata
						int initSize = Math.max(Math.max(rTid, tid)+1, INIT_VECTOR_CLOCK_SIZE);
						x.makeCV(initSize);
						x.set(rTid, r);
						x.set(tid, e);
						x.R = Epoch.READ_SHARED; //readShare
						if (COUNT_EVENTS) readShare.inc(tid);
					}
				} else { //read vector
					//Update last access metadata
					x.set(tid, e); //readShared
					if (COUNT_EVENTS) readShared.inc(tid);
				}
			}
			
			if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
		}
	}
	
	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		final PIPVarState sx = ((PIPVarState)orig);

		int/*epoch*/ E;
		if (WCP) {
			E = ts_get_eHB(td);
		} else {
			E = getE(td);
		}

		/* optional */ {
			final int/*epoch*/ w = sx.W;
			if (w == E) {
				if (COUNT_EVENTS) writeSameEpochFP.inc(td.getTid());
				if (COUNT_EVENTS) writeFP.inc(td.getTid());
				return true;
			}
		}

		if (HB || ST || FTO || td.getNumLocksHeld() == 0) {
			synchronized(sx) {
				final int tid = td.getTid();
				final int/*epoch*/ w = sx.W;
				final int wTid = Epoch.tid(w);
				final VectorClock tV = getV(td);
				if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
				
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() > 0) {
						holdLocks.inc(tid);
						if (td.getNumLocksHeld() == 1) {
							oneLockHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 2) {
							twoNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 3) {
							threeNestedLocksHeld.inc(tid);
						}
					}
				}
				
				//([WCP/DC/WDC] + ST) + FTO
				if (ST) {
					STVarState xSTCount = (STVarState)sx;
					if (xSTCount.Er != null && !xSTCount.Er.isEmpty()) {
						if (COUNT_EVENTS) {
							extraWriteCheckFP.inc(td.getTid());
							//Check if any update occurs at least once
							boolean update = false;
							for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
								ShadowLock lock = td.getHeldLock(i);
								for (int prevTid : xSTCount.Er.keySet()) {
									if (prevTid != tid && xSTCount.Er.get(prevTid).containsKey(lock)) {
										extraWriteUpdateFP.inc(td.getTid());
										update = true;
										break;
									}
								}
								if (update) break;
							}
						}
							
						for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
							ShadowLock lock = td.getHeldLock(i);
							for (int prevTid : xSTCount.Er.keySet()) {
								if (prevTid != tid && xSTCount.Er.get(prevTid).containsKey(lock)) {
									maxEpochAndCV(td, xSTCount.Er.get(prevTid).get(lock));
									xSTCount.Er.get(prevTid).remove(lock);
									if (xSTCount.Er.get(prevTid).isEmpty()) xSTCount.Er.remove(prevTid);
										
									if (xSTCount.Ew != null && xSTCount.Ew.get(prevTid) != null) xSTCount.Ew.get(prevTid).remove(lock);
									if (xSTCount.Ew != null && xSTCount.Ew.get(prevTid) != null && xSTCount.Ew.get(prevTid).isEmpty()) xSTCount.Ew.remove(prevTid);
								}
							}
						}
						xSTCount.Er.remove(tid);
						if (xSTCount.Er.isEmpty()) xSTCount.Er = null;
						if (xSTCount.Ew != null) {
							xSTCount.Ew.remove(tid);
							if (xSTCount.Ew.isEmpty()) xSTCount.Ew = null;
						}
					}
					
					final int/*epoch*/ r = sx.R;
					final int rTid = Epoch.tid(r);
					if (r != Epoch.READ_SHARED) { //read epoch
						if (rTid == tid) { //Write-Owned. Rule(a) Check is unneeded for write-owned case since the prior read and write access is ordered to the current write access
							//Update last Rule(a) metadata
							STVarState xST = (STVarState)orig;
							xST.Wm = getHLS(td);
							xST.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeOwnedFP.inc(tid);
						} else {
							//Check Rule(a)
							STVarState xST = (STVarState)sx;
							if (COUNT_EVENTS) {
								boolean set = false;
								HeldLS rdLock = xST.Rm;
								if (rdLock != null) {
									if (Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid))) { //Outer most lock already ordered to the current access
									} else if (td.equals(rdLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
									} else {
										extraWriteSetFP.inc(td.getTid());
										set = true;
									}
								}
								if (!set) {
									HeldLS wrLock = xST.Wm;
									if (wrLock != null) {
										if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										} else if (td.equals(wrLock.lock.getHoldingThread())) {
										} else {
											extraWriteSetFP.inc(td.getTid());
										}
									}
								}
							}
							
							HeldLS rdLock = xST.Rm;
							while (rdLock != null) {
								if (Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid))) {
									break; //Outer most lock already ordered to the current access
								} else if (td.equals(rdLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
									//Establish Rule(a) and avoid checking for read-write race
									if (PRINT_EVENTS) Util.log("rd to wr-exclusive (FP) Rule a: " + rdLock.vc.toString());
									if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
									maxEpochAndCV(td, rdLock.vc);
									if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
									break;
								} else {
									if (xST.Er == null)  xST.Er = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
									HashMap<ShadowLock, VectorClock> rEL = xST.Er.get(rTid);
									if (rEL == null) {
										rEL = new HashMap<ShadowLock, VectorClock>();
									}
									rEL.put(rdLock.lock, rdLock.vc);
									xST.Er.put(rTid, rEL);
								}
								rdLock = rdLock.next;
							}
							
							//Is Write
							HeldLS wrLock = xST.Wm;
							while (wrLock != null) {
								if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
									break;
								} else if (td.equals(wrLock.lock.getHoldingThread())) {
									break;
								} else {
									if (xST.Ew == null)  xST.Ew = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
									HashMap<ShadowLock, VectorClock> wEL = xST.Ew.get(wTid);
									if (wEL == null) {
										wEL = new HashMap<ShadowLock, VectorClock>();
									}
									wEL.put(wrLock.lock, wrLock.vc);
									xST.Ew.put(wTid, wEL);
								}
								wrLock = wrLock.next;
							}
							
							if (rdLock == null && !Epoch.leq(r, tV.get(rTid))) {
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Write-Exclusive
							//Update last Rule(a) metadata
							xST.Wm = getHLS(td);
							xST.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
						}
					} else { //read vector
						//Rule(a) Check is pushed to slow path for all threads if a read by any thread races with the current write access
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						} else { //Write-Shared
							//Rule(a) Check
							STVarState xST = (STVarState)sx;
							if (COUNT_EVENTS) {
								boolean set = false;
								for (int prevRdTid = 0; prevRdTid < xST.SharedRm.length; prevRdTid++) {
									HeldLS rdShrLock = xST.getSharedHeldLS(prevRdTid);
									if (rdShrLock != null && prevRdTid != tid) {
										if (Epoch.leq(rdShrLock.vc.get(prevRdTid), tV.get(prevRdTid))) {
										} else if (td.equals(rdShrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
										} else {
											extraWriteSetFP.inc(td.getTid());
											set = true;
										}
									}
									if (set) break;
								}
								//Is Write
								if (!set) {
									HeldLS wrLock = xST.Wm;
									if (wrLock != null) {
										if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										} else if (td.equals(wrLock.lock.getHoldingThread())) {
										} else {
											extraWriteSetFP.inc(td.getTid());
										}
									}
								}
							}
							
							for (int prevRdTid = 0; prevRdTid < xST.SharedRm.length; prevRdTid++) {
								HeldLS rdShrLock = xST.getSharedHeldLS(prevRdTid);
								while (rdShrLock != null) {
									if (prevRdTid != tid) {
										if (Epoch.leq(rdShrLock.vc.get(prevRdTid), tV.get(prevRdTid))) {
											break; //Outer most lock already ordered to the current access
										} else if (td.equals(rdShrLock.lock.getHoldingThread())) { //Outer most lock conflicts with current access
											//Establish Rule(a); Race Check already done
											if (PRINT_EVENTS) Util.log("rd to wr-shared (FP) Rule a: " + rdShrLock.vc.toString());
											if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
											maxEpochAndCV(td, rdShrLock.vc);
											if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
											break;
										} else {
											if (xST.Er == null)  xST.Er = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
											HashMap<ShadowLock, VectorClock> rShrEL = xST.Er.get(prevRdTid);
											if (rShrEL == null) {
												rShrEL = new HashMap<ShadowLock, VectorClock>();
											}
											rShrEL.put(rdShrLock.lock, rdShrLock.vc);
											xST.Er.put(prevRdTid, rShrEL);
										}
									}
									rdShrLock = rdShrLock.next;
								}
								
								//Is Write
								HeldLS wrLock = xST.Wm;
								while (wrLock != null) {
									if (Epoch.leq(wrLock.vc.get(wTid), tV.get(wTid))) {
										break;
									} else if (td.equals(wrLock.lock.getHoldingThread())) {
										break;
									} else {
										if (xST.Ew == null)  xST.Ew = new HashMap<Integer/*tid*/, HashMap<ShadowLock, VectorClock>>();
										HashMap<ShadowLock, VectorClock> wEL = xST.Ew.get(wTid);
										if (wEL == null) {
											wEL = new HashMap<ShadowLock, VectorClock>();
										}
										wEL.put(wrLock.lock, wrLock.vc);
										xST.Ew.put(wTid, wEL);
									}
									wrLock = wrLock.next;
								}
							}
							//Update last Rule(a) metadata
							xST.Wm = getHLS(td);
							xST.clearSharedHeldLS();
							xST.Rm = getHLS(td);
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeSharedFP.inc(tid);
						}
					}
				}
				
				//FTO but not [WCP/DC/WDC] + ST
				if (!ST && FTO) {
					if (!HB) {
					//Establish Rule(a)
					for (int i = 0; i < td.getNumLocksHeld(); i++) { //outer most to inner most
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							//Establish Rule(a)
							tV.set(tid, getE(td)); //revert WCP union PO
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							tV.set(tid, ts_get_eHB(td)); //WCP union PO
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
						if (WDC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WDCLockState);
							WDCLockState lockData = (WDCLockState)getV(lock);
							//Establish Rule(a)
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							priorCSAfterAccess = lockData.ReadMap.get(sx);
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							//Update write/read Vars
							lockData.writeVars.add(sx);
							lockData.readVars.add(sx);
						}
					}
					}
					
					final int/*epoch*/ r = sx.R;
					final int rTid = Epoch.tid(r);
					if (r != Epoch.READ_SHARED) { //read epoch
						if (rTid == tid) { //Write-Owned
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeOwnedFP.inc(tid);
						} else {
							if (!Epoch.leq(r, tV.get(rTid))) {
								ts_set_badVarState(td, sx);
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								return false;
							} //Write-Exclusive
							//Update last access metadata
							sx.W = E;
							sx.R = E;
							if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
						}
					} else { //read vector
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						} //Write-Shared
						//Update last access metadata
						sx.W = E;
						sx.R = E;
						if (COUNT_EVENTS) writeSharedFP.inc(tid);
					}
				}
				
				//Not FTO nor [WCP/DC/WDC] + ST
				if (!ST && !FTO) {
					//Write-Write Race Check.
					if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
						ts_set_badVarState(td, sx);
						if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
						return false;
					}
					
					final int/*epoch*/ r = sx.R;
					if (r != Epoch.READ_SHARED) {	
						//Read-Write Race Check.
						final int rTid = Epoch.tid(r);
						if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						}
						if (COUNT_EVENTS) writeExclusiveFP.inc(tid);
					} else {	
						//Read(Shr)-Write Race Check.
						if (sx.anyGt(tV)) {
							ts_set_badVarState(td, sx);
							if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
							return false;
						}
						if (COUNT_EVENTS) writeSharedFP.inc(tid);
					}
					
					//Update last access metadata
					sx.W = E; //Write-Exclusive; -Shared
				}
				
				//Counting and WCP update
				if (COUNT_EVENTS) writeFP.inc(td.getTid());
				if (COUNT_EVENTS) {
					if (td.getNumLocksHeld() == 0) {
						writeOUT.inc(td.getTid());
						writeOUTFP.inc(td.getTid());
					} else {
						writeIN.inc(td.getTid());
						writeINFP.inc(td.getTid());
					}
				}
				if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
				return true;
			}
		} else {
			return false;
		}
	}
	
	protected void write(final AccessEvent event, final ShadowThread td, final PIPVarState x) {
		int/*epoch*/ e;
		if (WCP) {
			e = ts_get_eHB(td);
		} else {
			e = getE(td);
		}
		
		if (COUNT_EVENTS) {
			if (td.getNumLocksHeld() == 0) {
				writeOUT.inc(td.getTid());
			} else {
				writeIN.inc(td.getTid());
			}
		}
		
		/* optional */ {
			final int/*epoch*/ w = x.W;
			if (w == e) {
				if (COUNT_EVENTS) writeSameEpoch.inc(td.getTid());
				return;
			}
		}
		
		synchronized(x) {
			final int/*epoch*/ w = x.W;
			final int wTid = Epoch.tid(w);
			final int tid = td.getTid();
			final VectorClock tV = getV(td);
			if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
			
			if (COUNT_EVENTS) {
				if (td.getNumLocksHeld() > 0) {
					holdLocks.inc(tid);
					if (td.getNumLocksHeld() == 1) {
						oneLockHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 2) {
						twoNestedLocksHeld.inc(tid);
					} else if (td.getNumLocksHeld() == 3) {
						threeNestedLocksHeld.inc(tid);
					}
				}
			}
			
			// Find the shortest race 
			int shortestRaceTid = -1;
			boolean shortestRaceIsWrite = false;
			String shortestRaceType = "";
			
			//([WCP/DC/WDC] + ST) + FTO
			if (ST) {
				final int/*epoch*/ r = x.R;
				final int rTid = Epoch.tid(r);
				if (r != Epoch.READ_SHARED) { //read epoch
					if (rTid == tid) { //Write-Owned. Rule(a) Check is unneeded for write-owned case since the prior read and write access is ordered to the current write access
						//Update last Rule(a) metadata
						STVarState xST = (STVarState)x;
						xST.Wm = getHLS(td);
						xST.Rm = getHLS(td);
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeOwned.inc(tid);
					} else {
						//Check Rule(a)
						STVarState xST = (STVarState)x;
						HeldLS rdLock = xST.Rm;
						while (rdLock != null) {
							if (!Epoch.leq(rdLock.vc.get(rTid), tV.get(rTid)) && td.equals(rdLock.lock.getHoldingThread())) {
								//Establish Rule(a) and avoid checking for read-write race
								if (PRINT_EVENTS) Util.log("rd to wr-exclusive (FP) Rule a: " + rdLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, rdLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							rdLock = rdLock.next;
						}
						if (rdLock == null && !Epoch.leq(r, tV.get(rTid))) {
							shortestRaceTid = rTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read-Write Race";
						}
						// Report shortest race
						if (shortestRaceTid >= 0) {
							error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
							if (COUNT_EVENTS) readWriteError.inc(tid);
						} //Write-Exclusive
						//Update last Rule(a) metadata
						xST.Wm = getHLS(td);
						xST.Rm = getHLS(td);
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeExclusive.inc(tid);
					}
				} else { //read vector
					//Rule(a) Check
					STVarState xST = (STVarState)x;
					for (int prevRdTid = 0; prevRdTid < xST.SharedRm.length; prevRdTid++) {
						HeldLS rdShrLock = xST.getSharedHeldLS(prevRdTid);
						while (rdShrLock != null) {
							if (prevRdTid != tid && !Epoch.leq(rdShrLock.vc.get(prevRdTid), tV.get(prevRdTid)) && td.equals(rdShrLock.lock.getHoldingThread())) {
								//Establish Rule(a); Race Check already done
								if (PRINT_EVENTS) Util.log("rd to wr-shared (FP) Rule a: " + rdShrLock.vc.toString());
								if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
								maxEpochAndCV(td, rdShrLock.vc);
								if (WCP) tV.set(tid, ts_get_eHB(td)); //WCP union PO
								break;
							}
							rdShrLock = rdShrLock.next;
						}
						if (rdShrLock == null && prevRdTid != tid && !Epoch.leq(x.get(prevRdTid), tV.get(prevRdTid))) {
							shortestRaceTid = prevRdTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
						}
					}
					// Report shortest race
					if (shortestRaceTid >= 0) {
						error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
						if (COUNT_EVENTS) sharedWriteError.inc(tid);
					} //Write-Shared
					//Update last Rule(a) metadata
					xST.Wm = getHLS(td);
					xST.clearSharedHeldLS();
					xST.Rm = getHLS(td);
					//Update last access metadata
					x.W = e;
					x.R = e;
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
			}
			
			//FTO but not [WCP/DC/WDC] + ST
			if (!ST && FTO) {
				final int/*epoch*/ r = x.R;
				final int rTid = Epoch.tid(r);
				if (r != Epoch.READ_SHARED) { //read epoch
					if (rTid == tid) { //Write-Owned
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeOwned.inc(tid);
					} else {
						if (!Epoch.leq(r, tV.get(rTid))) {
							if (PRINT_EVENTS) Util.log("rd-wr exclusive error");
							shortestRaceTid = rTid;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read-Write Race";
						}
						// Report shortest race
						if (shortestRaceTid >= 0) {
							error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
							if (COUNT_EVENTS) readWriteError.inc(tid);
						} //Write-Exclusive
						//Update last access metadata
						x.W = e;
						x.R = e;
						if (COUNT_EVENTS) writeExclusive.inc(tid);
					}
				} else { //read vector
					if (x.anyGt(tV)) {
						//Check for Read-Write race
						for (int prevReader = x.nextGt(tV, 0); prevReader > -1; prevReader = x.nextGt(tV, prevReader + 1)) {
							if (PRINT_EVENTS) Util.log("rd-wr share error");
							shortestRaceTid = prevReader;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
						}
					}
					// Report shortest race
					if (shortestRaceTid >= 0) {
						error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
						if (COUNT_EVENTS) sharedWriteError.inc(tid);
					} //Write-Shared
					//Update last access metadata
					x.W = e;
					x.R = e;
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
			}
			
			//Not FTO nor [WCP/DC/WDC] + ST
			if (!ST && !FTO) {
				//Write-Write Race Check.
				if (wTid != tid && !Epoch.leq(w, tV.get(wTid))) {
					shortestRaceTid = wTid;
					shortestRaceIsWrite = true;
					shortestRaceType = "Write-Write Race";
					if (COUNT_EVENTS) writeWriteError.inc(tid);
				}
				
				final int/*epoch*/ r = x.R;
				if (r != Epoch.READ_SHARED) {	
					//Read-Write Race Check.
					final int rTid = Epoch.tid(r);
					if (rTid != tid && !Epoch.leq(r, tV.get(rTid))) {
						shortestRaceTid = rTid;
						shortestRaceIsWrite = false;
						shortestRaceType = "Read-Write Race";
						if (COUNT_EVENTS) readWriteError.inc(tid);
					}
					if (COUNT_EVENTS) writeExclusive.inc(tid);
				} else {	
					//Read(Shr)-Write Race Check.
					if (x.anyGt(tV)) {
						for (int prevReader = x.nextGt(tV, 0); prevReader > -1; prevReader = x.nextGt(tV, prevReader + 1)) {
							shortestRaceTid = prevReader;
							shortestRaceIsWrite = false;
							shortestRaceType = "Read(Shared)-Write Race";
						}
						if (COUNT_EVENTS) sharedWriteError.inc(tid);
					}
					if (COUNT_EVENTS) writeShared.inc(tid);
				}
				
				//Update vector clocks to make execution race free
				if (shortestRaceTid >= 0) {
					error(event, x, shortestRaceType, shortestRaceIsWrite ? "Write by " : "Read by ", shortestRaceTid, "Write by ", tid);
				}
				
				//Update last access metadata
				x.W = e; //Write-Exclusive; -Shared
			}
			
			if (WCP) tV.set(tid, getE(td)); //revert WCP union PO
		}
	}
	
	@Override
	public void access(final AccessEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowVar orig = getOriginalOrBad(event.getOriginalShadow(), td);
		
		if (orig instanceof PIPVarState) {
			Object target = event.getTarget();
			
			if (target == null) {
				
				synchronized(classInitTime) {
					ClassInfo owner = ((FieldAccessEvent)event).getInfo().getField().getOwner();
					if (WCP) {
						VectorClock initTimehb = classInitTimeWCPHB.get(owner);
						ts_get_vHB(td).max(initTimehb);
						ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
						
						maxEpochAndCV(td, initTimehb);
					}
					if (!WCP) {
						VectorClock initTime = classInitTime.get(owner);
						maxEpochAndCV(td, initTime);
					}
				}
			}
			
			if (COUNT_EVENTS) {
				if (!ST && !FTO) {
					final int tid = td.getTid();
					if (td.getNumLocksHeld() > 0) {
						holdLocks.inc(tid);
						if (td.getNumLocksHeld() == 1) {
							oneLockHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 2) {
							twoNestedLocksHeld.inc(tid);
						} else if (td.getNumLocksHeld() == 3) {
							threeNestedLocksHeld.inc(tid);
						}
					}
				}
			}
			
			//FTO establishes Rule(a) during [read/write]FastPath events
			//ST establishes Rule(a) based on [read/write] cases at read[readFP]/write[writeFP] events
			if (!FTO && !ST) {
				if (!HB) {
					PIPVarState x = (PIPVarState)orig;
					for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
						ShadowLock lock = td.getHeldLock(i);
						if (WCP) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WCPLockState);
							WCPLockState lockData = (WCPLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
						if (DC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof DCLockState);
							DCLockState lockData = (DCLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if(priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
						if (WDC) {
							if (DEBUG) Assert.assertTrue(getV(lock) instanceof WDCLockState);
							WDCLockState lockData = (WDCLockState)getV(lock);
							VectorClock priorCSAfterAccess = lockData.WriteMap.get(x);
							//Establish Rule(a)
							if (priorCSAfterAccess != null) {
								maxEpochAndCV(td, priorCSAfterAccess);
							}
							if (event.isWrite()) {
								priorCSAfterAccess = lockData.ReadMap.get(x);
								if (priorCSAfterAccess != null) {
									maxEpochAndCV(td, priorCSAfterAccess);
								}
							}
							//Update write/read Vars
							if (event.isWrite()) {
								lockData.writeVars.add(x);
							} else {
								lockData.readVars.add(x);
							}
						}
					}
				}
			}
			
			if (event.isWrite()) {
				PIPVarState x = (PIPVarState)orig;
				write(event, td, x);
			} else {
				PIPVarState x = (PIPVarState)orig;
				read(event, td, x);
			}
			
			if (PRINT_EVENTS) {
				String fieldName = "";
				if (event instanceof FieldAccessEvent) {
					fieldName = ((FieldAccessEvent)event).getInfo().getField().getName();						
				} else if (event instanceof ArrayAccessEvent) {
					fieldName = Util.objectToIdentityString(event.getTarget()) + "[" + ((ArrayAccessEvent)event).getIndex() + "]";
				}
				PIPVarState x = (PIPVarState)orig;
				if (event.isWrite()) {
					Util.log("wr("+ fieldName +") by T"+td.getTid() + " | epoch: " + x.toString());
				} else {
					Util.log("rd("+ fieldName +") by T"+td.getTid() + " | epoch: " + x.toString());
				}
			}
		} else {
			Util.log("Not expecting to reach here for access event: " + event.getClass() + " | original shadow: " + event.getOriginalShadow());
			Assert.assertTrue(false); //Not expecting to reach here
			super.access(event);
		}
	}
	
	@Override
	public void volatileAccess(final VolatileAccessEvent event) {
		final ShadowThread td = event.getThread();
		final PIPVolatileState vd = getV(event.getShadowVolatile());
		final VectorClock volV = getV(event.getShadowVolatile());
		
		if (COUNT_EVENTS) vol.inc(td.getTid());
		
		//Vindicator synchronizes on volV, but FT2 does not.
		if(event.isWrite()) {
			final VectorClock tV = getV(td);
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(event.getShadowVolatile());
				final PIPVolatileState vdhb = volatileVwcpHB.get(event.getShadowVolatile());
				//incomming rd-wr edge
				ts_get_vHB(td).max(vdhb.readsJoined);
				ts_get_vWCP(td).max(vdhb.readsJoined);
				//incomming wr-wr edge
				ts_get_vHB(td).max(volVhb);
				ts_get_vWCP(td).max(volVhb);
				//outgoing wr-wr and wr-rd edge
				volV.max(ts_get_vHB(td));
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid()));
				
				volVhb.max(ts_get_vHB(td));
				ts_get_vHB(td).tick(td.getTid());
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				//incomming rd-wr edge
				tV.max(vd.readsJoined);
				//incomming wr-wr edge
				tV.max(volV);//volV -> vd.write
				//outgoing wr-wr and wr-rd edge
				volV.max(tV);
				incEpochAndCV(td);
			}
		} else {
			final VectorClock tV = getV(td);
			if (WCP) {
				final VectorClock volVhb = volatileVwcpHB.get(event.getShadowVolatile());
				final PIPVolatileState vdhb = volatileVwcpHB.get(event.getShadowVolatile());
				//incomming wr-rd edge
				ts_get_vHB(td).max(volVhb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				maxEpochAndCV(td, volVhb);
				//outgoing rd-wr edge
				vdhb.readsJoined.max(ts_get_vHB(td));
				vd.readsJoined.max(ts_get_vHB(td));
				ts_get_vHB(td).tick(td.getTid());
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				//incomming wr-rd edge
				maxEpochAndCV(td, volV);//volV -> vd.write
				//outgoing rd-wr edge
				vd.readsJoined.max(tV);
				incEpochAndCV(td);
			}
		}
		
		if (PRINT_EVENTS) {
			String fieldName = event.getInfo().getField().getName();
			if (event.isWrite()) {
				Util.log("volatile wr("+ fieldName +") by T"+td.getTid());
			} else {
				Util.log("volatile rd("+ fieldName +") by T"+td.getTid());
			}
		}
		super.volatileAccess(event);
	}
	
	@Override
	public void preStart(final StartEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowThread forked = event.getNewThread();
		final VectorClock tV = getV(td);
		
		if (COUNT_EVENTS) fork.inc(td.getTid());
		
		//FT2 inc, Vindicator claims not needed since create() does an increment
		if (WCP) {
			getV(forked).max(ts_get_vHB(td));
			ts_set_eWCP(forked, ts_get_vWCP(forked).get(forked.getTid()));
			
			ts_get_vHB(forked).max(ts_get_vHB(td));
			ts_get_vHB(td).tick(td.getTid());
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
		}
		if (!WCP) {
			maxEpochAndCV(forked, tV);
			incEpochAndCV(td);
		}
		
		if (PRINT_EVENTS) Util.log("preStart by T"+td.getTid());
		super.preStart(event);
	}
	
	@Override
	public void postJoin(final JoinEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowThread joining = event.getJoiningThread();
		
		if (COUNT_EVENTS) join.inc(td.getTid());
		
		if (WCP) {
			ts_get_vHB(td).max(ts_get_vHB(joining));
			ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			
			getV(td).max(ts_get_vHB(joining));
			ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid()));
		}
		if (!WCP) {
			maxEpochAndCV(td, getV(joining));
		}
		
		if (PRINT_EVENTS) Util.log("postJoin by T"+td.getTid());
		super.postJoin(event);
	}
	
	@Override
	public void preWait(WaitEvent event) {
		final ShadowThread td = event.getThread();
		
		if (COUNT_EVENTS) preWait.inc(td.getTid());
		
		if (HB) {
			final VectorClock lockV = getV(event.getLock());
			lockV.max(getV(td)); // we hold lock, so no need to sync here...
			incEpochAndCV(td);
		}
		if (!HB) {
			handleRelease(td, getV(event.getLock()), event.getInfo());
		}
		
		if (PRINT_EVENTS) Util.log("preWait by T"+td.getTid());
		super.preWait(event);
	}
	
	@Override
	public void postWait(WaitEvent event) {
		final ShadowThread td = event.getThread();
		final ShadowLock lock = event.getLock();
		
		if (COUNT_EVENTS) postWait.inc(td.getTid());
		
		if (HB) {
			maxEpochAndCV(td, getV(lock)); // we hold lock here
		}
		if (!HB) {
			handleAcquireHardEdge(td, lock, event.getInfo());
		}
		
		if (PRINT_EVENTS) Util.log("postWait by T"+td.getTid());
		super.postWait(event);
	}
	
	@Override
	public void preDoBarrier(BarrierEvent<PIPBarrierState> be) {
		// TODO Auto-generated method stub
		Assert.assertTrue(false);
		
	}

	@Override
	public void postDoBarrier(BarrierEvent<PIPBarrierState> be) {
		// TODO Auto-generated method stub
		Assert.assertTrue(false);
		
	}
	
	@Override
	public void classInitialized(ClassInitializedEvent event) {
		final ShadowThread td = event.getThread();
		final VectorClock tV = getV(td);
		
		if (COUNT_EVENTS) classInit.inc(td.getTid());
		
		synchronized(classInitTime) {
			VectorClock initTime = classInitTime.get(event.getRRClass());
			initTime.max(tV);
			if (WCP) {
				VectorClock initTimehb = classInitTimeWCPHB.get(event.getRRClass());
				initTimehb.max(ts_get_vHB(td));
			}
		
			if (WCP) {			
				ts_get_vHB(td).tick(td.getTid());
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
			}
			if (!WCP) {
				incEpochAndCV(td);
			}
		}
		
		if (PRINT_EVENTS) Util.log("classInitialized by T"+td.getTid());
		super.classInitialized(event);
	}
	
	@Override
	public void classAccessed(ClassAccessedEvent event) {
		final ShadowThread td = event.getThread();
		
		if (COUNT_EVENTS) classAccess.inc(td.getTid());
		
		synchronized(classInitTime) {
			final VectorClock initTime = classInitTime.get(event.getRRClass());
			if (WCP) {
				final VectorClock initTimehb = classInitTimeWCPHB.get(event.getRRClass());
				ts_get_vHB(td).max(initTimehb);
				ts_set_eHB(td, ts_get_vHB(td).get(td.getTid()));
				
				getV(td).max(initTimehb);
				ts_set_eWCP(td, ts_get_vWCP(td).get(td.getTid()));
			}
			if (!WCP) {
				maxEpochAndCV(td, initTime);
			}
		}
		
		if (PRINT_EVENTS) Util.log("classAccessed by T"+td.getTid());
	}
	
	public static String toString(final ShadowThread td) {
		return String.format("[tid=%-2d C=%s E=%s]", td.getTid(), getV(td), Epoch.toString(getE(td)));
	}
	
	protected void recordRace(final AccessEvent event) {
		StaticRace staticRace = new StaticRace(event.getAccessInfo().getLoc());
		StaticRace.addRace(staticRace);
	}
	
	protected void error(final AccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		if (COUNT_RACES) {
			recordRace(event);
			// Don't bother printing error during performance execution. All race information is collected using StaticRace now.
			//Update: ErrorMessage has added errorQuite so that race counting prints nothing during execution.
			if (COUNT_EVENTS) {
				if (event instanceof FieldAccessEvent) {
					fieldError((FieldAccessEvent) event, x, description, prevOp, prevTid, curOp, curTid);
				} else {
					arrayError((ArrayAccessEvent) event, x, description, prevOp, prevTid, curOp, curTid);
				}
			}
		}
	}
	
	protected void fieldError(final FieldAccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		final FieldInfo fd = event.getInfo().getField();
		final ShadowThread td = event.getThread();
		final Object target = event.getTarget();
		
		fieldErrors.errorQuite(td,
				fd, 
				"Shadow State",		x,
				"Current Thread",	toString(td),
				"Class",			(target==null?fd.getOwner():target.getClass()),
				"Field",			Util.objectToIdentityString(target) + "." + fd,
				"Message",			description,
				"Previous Op",		prevOp + " " + ShadowThread.get(prevTid),
				"Current Op",		curOp + " " + ShadowThread.get(curTid),
				"Stack",			ShadowThread.stackDumpForErrorMessage(td));
		
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
	}
	
	protected void arrayError(final ArrayAccessEvent event, final PIPVarState x, final String description, final String prevOp, final int prevTid, final String curOp, final int curTid) {
		final ShadowThread td = event.getThread();
		final Object target = event.getTarget();
		
		arrayErrors.errorQuite(td,
				event.getInfo(),
				"Alloc Site",		ArrayAllocSiteTracker.get(target),
				"Shadow State",		x,
				"Current Thread",	toString(td),
				"Array",			Util.objectToIdentityString(target) + "[" + event.getIndex() + "]",
				"Message",			description,
				"Previous Op",		prevOp + " " + ShadowThread.get(prevTid),
				"Current Op",		curOp + " " + ShadowThread.get(curTid),
				"Stack",			ShadowThread.stackDumpForErrorMessage(td));
		
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
		
		event.getArrayState().specialize();
	}
}
