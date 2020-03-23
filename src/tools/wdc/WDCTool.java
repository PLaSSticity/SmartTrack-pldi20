/******************************************************************************

Copyright (c) 2010, Cormac Flanagan (University of California, Santa Cruz)
                    and Stephen Freund (Williams College) 

All rights reserved.  

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

 * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

 * Neither the names of the University of California, Santa Cruz
      and Williams College nor the names of its contributors may be
      used to endorse or promote products derived from this software
      without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 ******************************************************************************/


package tools.wdc;


import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Stack;
import java.util.concurrent.Callable;

import acme.util.Assert;
import acme.util.Util;
import acme.util.Yikes;
import acme.util.count.AggregateCounter;
import acme.util.count.ThreadLocalCounter;
import acme.util.decorations.Decoration;
import acme.util.decorations.DecorationFactory;
import acme.util.decorations.DecorationFactory.Type;
import acme.util.decorations.DefaultValue;
import acme.util.decorations.NullDefault;
import acme.util.identityhash.WeakIdentityHashMap;
import acme.util.io.XMLWriter;
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
import rr.event.Event;
import rr.event.FieldAccessEvent;
import rr.event.JoinEvent;
import rr.event.MethodEvent;
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
import rr.org.objectweb.asm.Opcodes;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;
import rr.state.ShadowVolatile;
import rr.tool.RR;
import rr.tool.Tool;

@Abbrev("WDC")
public class WDCTool extends Tool implements BarrierListener<WDCBarrierState>, Opcodes {

	private static final boolean COUNT_EVENT = true;
	private static final boolean PRINT_EVENT = RR.printEventOption.get();
	private static final boolean VERBOSE = RR.verboseOption.get();
	private static final boolean DEBUG = RR.debugOption.get();
	static final int INIT_CV_SIZE = 4;
	
	// Relations/Analyses
	public static final boolean HB = RR.dcHBOption.get();
	public static final boolean WCP = RR.dcWCPOption.get();
	public static final boolean DC = RR.dcDCOption.get();
	public static final boolean WDC = RR.dcCAPOOption.get();
	
	// Multiple Relations/Analyses on the same trace
	public static final boolean HB_WCP_DC = RR.dcWCP_DCOption.get();
	public static final boolean HB_WCP_WDC = RR.dcWCP_CAPOOption.get();
	public static final boolean HB_WCP_DC_WDC = RR.dcCAPOFullOption.get();
	
	// Enable/Disable Event Graph G Generation
	private static final boolean DISABLE_EVENT_GRAPH = RR.disableEventGraph.get();
	private static final boolean DISABLE_MERGING = RR.disableMerging.get();
	
	// Race Counting 
	private static final boolean UNORDERED_PAIRS = RR.unorderedPairs.get();
	private static final boolean SHORTEST_RACEEDGE = RR.shortestRaceEdge.get();
	
	// Counters for relative frequencies of each access type
	private static final ThreadLocalCounter read = new ThreadLocalCounter("DC", "Read", RR.maxTidOption.get());
	private static final ThreadLocalCounter write = new ThreadLocalCounter("DC", "Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter acquire = new ThreadLocalCounter("DC", "Acquire", RR.maxTidOption.get());
	private static final ThreadLocalCounter release = new ThreadLocalCounter("DC", "Release", RR.maxTidOption.get());
	private static final ThreadLocalCounter fork = new ThreadLocalCounter("DC", "Fork", RR.maxTidOption.get());
	private static final ThreadLocalCounter join = new ThreadLocalCounter("DC", "Join", RR.maxTidOption.get());
	private static final ThreadLocalCounter barrier = new ThreadLocalCounter("DC", "Barrier", RR.maxTidOption.get());
	private static final ThreadLocalCounter preWait = new ThreadLocalCounter("DC", "Pre Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter postWait = new ThreadLocalCounter("DC", "Post Wait", RR.maxTidOption.get());
	private static final ThreadLocalCounter classInit = new ThreadLocalCounter("DC", "Class Initialized", RR.maxTidOption.get());
	private static final ThreadLocalCounter classAccess = new ThreadLocalCounter("DC", "Class Accessed", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_write = new ThreadLocalCounter("DC", "Volatile Write", RR.maxTidOption.get());
	private static final ThreadLocalCounter volatile_read = new ThreadLocalCounter("DC", "Volatile Read", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter exit = new ThreadLocalCounter("DC", "Exit", RR.maxTidOption.get());
	private static final ThreadLocalCounter dummy = new ThreadLocalCounter("DC", "Dummy", RR.maxTidOption.get());
	private static final ThreadLocalCounter fake_fork = new ThreadLocalCounter("DC", "Fake Fork", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter writeFP = new ThreadLocalCounter("DC", "WriteFastPath", RR.maxTidOption.get());
	private static final ThreadLocalCounter readFP = new ThreadLocalCounter("DC", "ReadFastPath", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter access_inside = new ThreadLocalCounter("DC", "Accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter access_outside = new ThreadLocalCounter("DC", "Accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter write_inside = new ThreadLocalCounter("DC", "Write accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter write_outside = new ThreadLocalCounter("DC", "Write accesses Outside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter read_inside = new ThreadLocalCounter("DC", "Read accesses Inside Critical Sections", RR.maxTidOption.get());
	private static final ThreadLocalCounter read_outside = new ThreadLocalCounter("DC", "Read accesses Outside Critical Sections", RR.maxTidOption.get());
	
	private static final ThreadLocalCounter other = new ThreadLocalCounter("DC", "Other", RR.maxTidOption.get());
	
	static {
		AggregateCounter reads = new AggregateCounter("DC", "Total Reads", read);
		AggregateCounter writes = new AggregateCounter("DC", "Total Writes", write);
		AggregateCounter vol = new AggregateCounter("DC", "Total Volatiles", volatile_write, volatile_read);
		AggregateCounter accesses = new AggregateCounter("DC", "Total Access Ops", reads, writes);
		new AggregateCounter("DC", "Total Ops", accesses, acquire, release, fork, join, barrier, preWait, postWait, classInit, classAccess, vol, other);
		new AggregateCounter("DC", "Total Fast Path Taken", readFP, writeFP);
	}
	
	public final ErrorMessage<FieldInfo> fieldErrors = ErrorMessages.makeFieldErrorMessage("WDC");
	public final ErrorMessage<ArrayAccessInfo> arrayErrors = ErrorMessages.makeArrayErrorMessage("WDC");
	
	// Can use the same data for class initialization synchronization as for volatiles
	public static final Decoration<ClassInfo,WDCVolatileData> classInitTime = MetaDataInfoMaps.getClasses().makeDecoration("WDC:InitTime", Type.MULTIPLE, 
			new DefaultValue<ClassInfo,WDCVolatileData>() {
		public WDCVolatileData get(ClassInfo t) {
			return new WDCVolatileData(null);
		}
	});

	public WDCTool(final String name, final Tool next, CommandLine commandLine) {
		super(name, next, commandLine);
		new BarrierMonitor<WDCBarrierState>(this, new DefaultValue<Object,WDCBarrierState>() {
			public WDCBarrierState get(Object k) {
				return new WDCBarrierState(ShadowLock.get(k));
			}
		});
		//Remove error reporting limit for comparison with PIP tools
		fieldErrors.setMax(Integer.MAX_VALUE);
		arrayErrors.setMax(Integer.MAX_VALUE);
	}

	static CV ts_get_hb(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_hb(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static CV ts_get_wcp(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_wcp(ShadowThread ts, CV cv) { Assert.panic("Bad");  }

	static CV ts_get_dc(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_dc(ShadowThread ts, CV cv) { Assert.panic("Bad");  }
	
	static CV ts_get_wdc(ShadowThread ts) { Assert.panic("Bad");	return null; }
	static void ts_set_wdc(ShadowThread ts, CV cv) { Assert.panic("Bad");	}
	
	// We only maintain the "last event" if BUILD_EVENT_GRAPH == true
	static EventNode ts_get_lastEventNode(ShadowThread ts) { Assert.panic("Bad"); return null; }
	static void ts_set_lastEventNode(ShadowThread ts, EventNode eventNode) { Assert.panic("Bad"); }
	
	// Maintain the a stack of current held critical sections per thread
	static Stack<AcqRelNode> ts_get_holdingLocks(ShadowThread ts) { Assert.panic("Bad"); return null; }
	static void ts_set_holdingLocks(ShadowThread ts, Stack<AcqRelNode> heldLocks) { Assert.panic("Bad"); }
	
	// Handle FastPaths and race edges
	static int/*epoch*/ ts_get_eTd(ShadowThread ts) { Assert.panic("Bad");	return -1; }
	static void ts_set_eTd(ShadowThread ts, int/*epoch*/ e) { Assert.panic("Bad");  }

	static final Decoration<ShadowLock,WDCLockData> dcLockData = ShadowLock.makeDecoration("WDC:ShadowLock", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowLock,WDCLockData>() { public WDCLockData get(final ShadowLock ld) { return new WDCLockData(ld); }});

	static final WDCLockData get(final ShadowLock ld) {
		return dcLockData.get(ld);
	}

	static final Decoration<ShadowVolatile,WDCVolatileData> dcVolatileData = ShadowVolatile.makeDecoration("WDC:shadowVolatile", DecorationFactory.Type.MULTIPLE,
			new DefaultValue<ShadowVolatile,WDCVolatileData>() { public WDCVolatileData get(final ShadowVolatile ld) { return new WDCVolatileData(ld); }});

	static final WDCVolatileData get(final ShadowVolatile ld) {
		return dcVolatileData.get(ld);
	}

	@Override
	final public ShadowVar makeShadowVar(final AccessEvent fae) {
		if (fae.getKind() == Kind.VOLATILE) {
			WDCVolatileData vd = get(((VolatileAccessEvent)fae).getShadowVolatile());
			return super.makeShadowVar(fae);
		} else {
			ShadowThread td = fae.getThread();
			int tid = td.getTid();
			if (HB) return new WDCGuardState(ts_get_hb(td));
			if (WCP) {
				CV wcpUnionPO = new CV(ts_get_wcp(td));
				wcpUnionPO.set(tid, ts_get_hb(td).get(tid));
				return new WDCGuardState(ts_get_hb(td), wcpUnionPO);
			}
			if (DC) return new WDCGuardState(ts_get_dc(td));
			if (WDC) return new WDCGuardState(ts_get_wdc(td));
			if (HB_WCP_DC) {
				CV wcpUnionPO = new CV(ts_get_wcp(td));
				wcpUnionPO.set(tid, ts_get_hb(td).get(tid));
				return new WDCGuardState(ts_get_hb(td), wcpUnionPO, ts_get_dc(td));
			}
			if (HB_WCP_WDC) {
				CV wcpUnionPO = new CV(ts_get_wcp(td));
				wcpUnionPO.set(tid, ts_get_hb(td).get(tid));
				return new WDCGuardState(ts_get_hb(td), wcpUnionPO, ts_get_wdc(td));
			}
			if (HB_WCP_DC_WDC) {
				CV wcpUnionPO = new CV(ts_get_wcp(td));
				wcpUnionPO.set(tid, ts_get_hb(td).get(tid));
				return new WDCGuardState(ts_get_hb(td), wcpUnionPO, ts_get_dc(td), ts_get_wdc(td));
			}
		}
		Assert.assertTrue(false); //Should never reach here
		return null;
	}

	@Override
	public void create(NewThreadEvent e) {
		ShadowThread currentThread = e.getThread();
		synchronized(currentThread) {
			final int tid = currentThread.getTid();
			if (HB) {
				CV hb = ts_get_hb(currentThread);
				if (hb == null) {
					hb = new CV(INIT_CV_SIZE);
					ts_set_hb(currentThread, hb);
					hb.inc(tid);
				}
			}
			if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				CV hb = ts_get_hb(currentThread);
				if (hb == null) {
					hb = new CV(INIT_CV_SIZE);
					ts_set_hb(currentThread, hb);
					hb.inc(tid);
					
					CV wcp = new CV(INIT_CV_SIZE);
					ts_set_wcp(currentThread, wcp);
				}
			}
			if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
				CV dc = ts_get_dc(currentThread);
				if (dc == null) {
					dc = new CV(INIT_CV_SIZE);
					ts_set_dc(currentThread, dc);
					dc.inc(tid);
				}
			}
			if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				CV wdc = ts_get_wdc(currentThread);
				if (wdc == null) {
					wdc = new CV(INIT_CV_SIZE);
					ts_set_wdc(currentThread, wdc);
					wdc.inc(tid);
				}
			}
			
			// Handle race edges
			if (DC) {
				ts_set_eTd(currentThread, ts_get_dc(currentThread).get(tid));
			} else if (WDC) {
				ts_set_eTd(currentThread, ts_get_wdc(currentThread).get(tid));
			} else {
				ts_set_eTd(currentThread, ts_get_hb(currentThread).get(tid));
			}
		}
		super.create(e);

	}
	
	@Override
	public void exit(MethodEvent me) {
		ShadowThread td = me.getThread();
		if (td.getParent() == null && td.getTid() != 0 /*not the main thread*/ && !td.getThread().getName().equals("Finalizer")) {
			String methodName = me.getInfo().getName();
			Object target = me.getTarget();
			if ((methodName.equals("call") && target instanceof Callable) ||
			    (methodName.equals("run")  && target instanceof Runnable)) {
				synchronized(td) {
					final int tid = td.getTid();
					
					if (COUNT_EVENT) other.inc(tid);
					if (COUNT_EVENT) exit.inc(tid);
					
					//Get the main thread
					ShadowThread main = ShadowThread.get(0);
					synchronized(main) {
						if (DEBUG) Assert.assertTrue(main != null);
	
						final int main_tid = main.getTid();
						
						EventNode dummyEventNode = null;
						EventNode thisEventNode = null;
						if (!DISABLE_EVENT_GRAPH) {
							//Build this thread's event node
							AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
							thisEventNode = new EventNode(-2, td.getTid(), currentCriticalSection, "join [exit join]");
							handleEvent(me, thisEventNode);
							if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
							//Build dummy eventNode
							if (COUNT_EVENT) other.inc(td.getTid());
							if (COUNT_EVENT) dummy.inc(td.getTid());
							currentCriticalSection = getCurrentCriticalSection(main);
							dummyEventNode = new EventNode(ts_get_lastEventNode(main).eventNumber+1, main_tid, currentCriticalSection, "exit [dummy event]");
							//PO last event node in main to dummy node
							EventNode priorMainNode = ts_get_lastEventNode(main);
							EventNode.addEdge(priorMainNode, dummyEventNode);
							ts_set_lastEventNode(main, dummyEventNode);
							//Create a hard edge from thisEventNode to dummy node
							EventNode.addEdge(thisEventNode, dummyEventNode);
						} else {
							//PO last event node in this thread to this thread's current event node
							//Set this event node to this thread's latest event node
							handleEvent(me, thisEventNode);
							if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
						}
						
						if (PRINT_EVENT) Util.log("exit "+me.getInfo().toSimpleName()+" by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
						
						//Update main's vector clocks with that of the joining thread
						if (HB) {	
							ts_get_hb(main).max(ts_get_hb(td));
						}
						if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_hb(main).max(ts_get_hb(td));
							ts_get_wcp(main).max(ts_get_hb(td));
						}
						if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
							ts_get_dc(main).max(ts_get_dc(td));
						}
						if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_wdc(main).max(ts_get_wdc(td));
						}
						
						//Increment joining thread since it is an outgoing edge
						if (HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
						}
						if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
							ts_get_dc(td).inc(tid);
						}
						if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_wdc(td).inc(tid);
						}
					}
				}
			}
		}
		super.exit(me);
	}
	
	@Override
	public void init() {
		Util.log("disable event graph: " + DISABLE_EVENT_GRAPH);
		Util.log("print events: " + PRINT_EVENT);
		Util.log("count events: " + COUNT_EVENT);
		Util.log("HB analysis: " + HB);
		Util.log("WCP analysis: " + WCP);
		Util.log("DC analysis: " + DC);
		Util.log("WDC analysis: " + WDC);
		Util.log("WCP + DC analysis: " + HB_WCP_DC);
		Util.log("WCP + WDC analysis: " + HB_WCP_WDC);
		Util.log("WCP + DC + WDC analysis: " + HB_WCP_DC_WDC);
		
		//Disable event graph generation for HB and WCP configurations
		if (HB || WCP) {
			Assert.assertTrue(DISABLE_EVENT_GRAPH);
		}
	}
	
	@Override
	public void fini() {
		Util.log("Single Second Site Race Counts");
		StaticRace.reportRaces(StaticRace.static_second_site_RaceMap); //Second Site
		if (UNORDERED_PAIRS) {
			Util.log("Unordered Pairs Race Counts"); 
			StaticRace.reportRaces(StaticRace.static_unordered_pairs_RaceMap); //Unordered Pairs
		}
		//With no constraint graph races can not be checked
		//If this is changed, HB_WCP_ONLY configuration should not check races since DC constraint graph is not tracked
		
		if (!DISABLE_EVENT_GRAPH) {
			// Store Reordered Traces
			File commandDir = storeReorderedTraces();
			
			// Races (based on an identifying string) that we've verified have no cycle.
			// Other dynamic instances of the same static race might have a cycle, but we've already demonstrated that this static race is predictable, so who cares?
			HashSet<StaticRace> verifiedRaces = new HashSet<StaticRace>();
			LinkedList<StaticRace> staticOnlyCheck = new LinkedList<StaticRace>();
			long start = System.currentTimeMillis();
			// Only Vindicate non HB- or WCP-races
			HashMap<RaceType,HashMap<StaticRace,Integer>> staticRaceMap;
			if (UNORDERED_PAIRS) { //unordered pairs (UP)
				staticRaceMap = StaticRace.static_unordered_pairs_RaceMap;
			} else { //second site (SS)
				staticRaceMap = StaticRace.static_second_site_RaceMap;
			}
			for (StaticRace dcRace : StaticRace.races) {
				if (!dcRace.raceType.isWCPRace() && 
						(staticRaceMap.get(RaceType.WCPRace) == null || (staticRaceMap.get(RaceType.WCPRace) != null && !staticRaceMap.get(RaceType.WCPRace).containsKey(dcRace))) &&
						(staticRaceMap.get(RaceType.HBRace) == null || (staticRaceMap.get(RaceType.HBRace) != null && !staticRaceMap.get(RaceType.HBRace).containsKey(dcRace)))) {
					vindicateRace(dcRace, verifiedRaces, staticOnlyCheck, true, true, commandDir);
				}
			}
			Util.log("Static DC Race Check Time: " + (System.currentTimeMillis() - start));
			for (StaticRace singleStaticRace : staticOnlyCheck) {
				StaticRace.races.remove(singleStaticRace);
			}
		}
	}
	
	public void vindicateRace(StaticRace DCrace, HashSet<StaticRace> verifiedRaces, LinkedList<StaticRace> staticOnlyCheck, boolean staticDCRacesOnly, boolean vindicateRace, File commandDir) {
		RdWrNode startNode = DCrace.firstNode;
		RdWrNode endNode = DCrace.secondNode;
		String desc = DCrace.raceType + " " + DCrace.description();
		if (vindicateRace) {
			// Remove edge between conflicting accesses of current race
			if (RR.wdcRemoveRaceEdge.get()) {
				// Remove edge between conflicting accesses of current race
				Util.println("removing conflicting access edge");
				EventNode.removeEdge(startNode, endNode);
			} else {
				Util.println("NOT removing conflicting access edge");
			}
			if ((staticDCRacesOnly && !verifiedRaces.contains(DCrace)) || !staticDCRacesOnly) {
				Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode + " | distance: " + (endNode.eventNumber - startNode.eventNumber));
				Util.println("Next trying with traverseFromAllEdges = true and precision = true");
				boolean detectedCycle = false;
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					detectedCycle = EventNode.crazyNewEdges(startNode, endNode, true, true, true, commandDir);
				} else {
					detectedCycle = EventNode.crazyNewEdges(startNode, endNode, true, true, false, commandDir);
				}
				 
				if (staticDCRacesOnly) {
					if (!detectedCycle) {
						verifiedRaces.add(DCrace);
					}
					if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC){
						boolean checkDCOrder = EventNode.addRuleB(startNode, endNode, true, true, commandDir);
						Util.println("Race pair " + desc + " is" + (checkDCOrder ? " " : " NOT ") + "DC ordered.");
					}
					staticOnlyCheck.add(DCrace);
				}
			}
			//Add edge between conflicting accesses of current race back to WDC-B graph
			if (RR.wdcRemoveRaceEdge.get()) {
				// If it was removed, add edge between conflicting accesses of current race back to WDC-B graph
				EventNode.addEdge(startNode, endNode);
			}
		} else {
			Util.println("Checking " + desc + " for event pair " + startNode + " -> " + endNode + " | distance: " + (endNode.eventNumber - startNode.eventNumber));
		}
	}
	
	//Tid -> Stack of ARNode
	AcqRelNode getCurrentCriticalSection(ShadowThread td) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		if (locksHeld == null) {
			locksHeld = new Stack<AcqRelNode>();
			ts_set_holdingLocks(td, locksHeld);
		}
		AcqRelNode currentCriticalSection = locksHeld.isEmpty() ? null : locksHeld.peek();
		return currentCriticalSection;
	}
	
	void updateCurrentCriticalSectionAtAcquire(ShadowThread td, AcqRelNode acqNode) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		locksHeld.push(acqNode);
	}
	
	AcqRelNode updateCurrentCriticalSectionAtRelease(ShadowThread td, AcqRelNode relNode) {
		Stack<AcqRelNode> locksHeld = ts_get_holdingLocks(td);
		AcqRelNode poppedNode = locksHeld.pop();
		return poppedNode;
	}
	
	void handleEvent(Event e, EventNode thisEventNode) {
		final ShadowThread td = e.getThread();
		final int tid = td.getTid();
        
		if (!DISABLE_EVENT_GRAPH) {
			EventNode priorPOEventNode = ts_get_lastEventNode(td);
			if (priorPOEventNode == null) {
				// This is the first event of the thread
				if (DEBUG && (DC || HB_WCP_DC)) Assert.assertTrue((td.getParent() != null) == (ts_get_dc(td) instanceof CVE));
				if (DEBUG && (WDC || HB_WCP_WDC || HB_WCP_DC_WDC)) Assert.assertTrue((td.getParent() != null) == (ts_get_wdc(td) instanceof CVE));
				if (td.getParent() != null) {
					EventNode forkEventNode = null;
					if (HB) forkEventNode = ((CVE)ts_get_hb(td)).eventNode;
					if (WCP) forkEventNode = ((CVE)ts_get_wcp(td)).eventNode;
					if (DC || HB_WCP_DC) forkEventNode = ((CVE)ts_get_dc(td)).eventNode;
					if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) forkEventNode = ((CVE)ts_get_wdc(td)).eventNode;
					EventNode.addEdge(forkEventNode, thisEventNode);
				} else {
					if (tid != 0 
							&& !td.getThread().getName().equals("Finalizer")) {
						if (PRINT_EVENT) Util.log("parentless fork to T"+tid);
						if (COUNT_EVENT) fake_fork.inc(tid);
						//Get the main thread
						final ShadowThread main = ShadowThread.get(0);
						synchronized(main) { //Will this deadlock? Same as exit, I don't think main will lock on this thread.
							if (DEBUG) Assert.assertTrue(main != null);//, "The main thread can not be found.");
	
							//Create a hard edge from the last event in the main thread to the parentless first event in this thread
							final int main_tid = main.getTid();
						
							// Handle race edges
							if (DC) {
								ts_set_eTd(main, ts_get_dc(main).get(main_tid));
							} else if (WDC) {
								ts_set_eTd(main, ts_get_wdc(main).get(main_tid));
							} else {
								ts_set_eTd(main, ts_get_dc(main).get(main_tid));//ts_get_hb(main).get(main_tid));
								if (DEBUG) Assert.assertTrue(ts_get_dc(main).get(tid) == ts_get_hb(main).get(tid));
							}
							
							if (HB) {
								final CV main_hb = ts_get_hb(main);
								final CV hb = ts_get_hb(td);
								hb.max(main_hb);
								main_hb.inc(main_tid);
							}
							if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								final CV main_hb = ts_get_hb(main);
								final CV hb = ts_get_hb(td);
								final CV wcp = ts_get_wcp(td);
								// Compute WCP before modifying HB
								wcp.max(main_hb); // Use HB here because this is a hard WCP edge
								hb.max(main_hb);
								main_hb.inc(main_tid);
							}
							if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
								final CV main_dc = ts_get_dc(main);
								final CV dc = ts_get_dc(td);
								dc.max(main_dc);
								main_dc.inc(main_tid);
							}
							if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								final CV main_wdc = ts_get_wdc(main);
								final CV wdc = ts_get_wdc(td);
								wdc.max(main_wdc);
								main_wdc.inc(main_tid);
							}
							
							//For generating event node graph
							if (HB) {
								ts_set_hb(td, new CVE(ts_get_hb(td), ts_get_lastEventNode(main)));
							} else if (WCP) {
								ts_set_wcp(td, new CVE(ts_get_wcp(td), ts_get_lastEventNode(main)));
							} else if (DC || HB_WCP_DC) {
								ts_set_dc(td, new CVE(ts_get_dc(td), ts_get_lastEventNode(main)));
							} else if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								ts_set_wdc(td, new CVE(ts_get_wdc(td), ts_get_lastEventNode(main)));
							}
							
							if (DEBUG) Assert.assertTrue(td.getTid() != 0);
							if (DEBUG) Assert.assertTrue(ts_get_lastEventNode(main) != null);
							
							//Add edge from main to first event in the thread
							if (DEBUG && (DC || HB_WCP_DC)) Assert.assertTrue(((CVE)ts_get_dc(td)).eventNode == ts_get_lastEventNode(main));
							if (DEBUG && (WDC || HB_WCP_WDC || HB_WCP_DC_WDC)) Assert.assertTrue(((CVE)ts_get_wdc(td)).eventNode == ts_get_lastEventNode(main));
							
							EventNode forkEventNode = null;
							if (HB) forkEventNode = ((CVE)ts_get_hb(td)).eventNode;
							if (DC || HB_WCP_DC) forkEventNode = ((CVE)ts_get_dc(td)).eventNode;
							if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) forkEventNode = ((CVE)ts_get_wdc(td)).eventNode;
							EventNode.addEdge(forkEventNode, thisEventNode);
							
							if (DEBUG && EventNode.DEBUG_GRAPH) {
								EventNode eventOne = EventNode.threadToFirstEventMap.get(0);
								eventOne = EventNode.threadToFirstEventMap.get(0);
								Assert.assertTrue(eventOne.eventNumber == 1);//, "eventOne.eventNumber: " + eventOne.eventNumber);
								Assert.assertTrue(EventNode.bfsTraversal(forkEventNode, eventOne, null, Long.MIN_VALUE, Long.MAX_VALUE, true));//, "main T" + main.getTid() + " does not reach event 1. What: " + eventOne.getNodeLabel());
								Assert.assertTrue(EventNode.bfsTraversal(thisEventNode, eventOne, null, Long.MIN_VALUE, Long.MAX_VALUE, true));//, "Thread T" + td.getTid() + " does not reach event 1.");
							}
						}
					} else {
						// If the current event is the first event, give it an eventNumber of 1
						if (tid == 0 && thisEventNode.eventNumber < 0) {
							if (DEBUG) Assert.assertTrue(thisEventNode.threadID == 0);
							thisEventNode.eventNumber = 1;
							if (EventNode.DEBUG_GRAPH) EventNode.addEventToThreadToItsFirstEventsMap(thisEventNode);
						}
						if (DEBUG) Assert.assertTrue(thisEventNode.eventNumber == 1 || td.getThread().getName().equals("Finalizer"));//, "Event Number: " + thisEventNode.eventNumber + " | Thread Name: " + td.getThread().getName());
					}
				}
			} else {
				EventNode.addEdge(priorPOEventNode, thisEventNode);
			}
			ts_set_lastEventNode(td, thisEventNode);
		} else if (td.getParent() == null && tid != 0 /*not main thread*/ 
				&& !td.getThread().getName().equals("Finalizer")) {
			//Path for DISABLED_EVENT_GRAPH
			//If this is the first event in the parentless thread
			if ((!DC && !WDC && ts_get_hb(td).get(0) == 0) 
					|| (DC && ts_get_dc(td).get(0) == 0) 
					|| (WDC && ts_get_wdc(td).get(0) == 0)) {
				if (PRINT_EVENT) Util.log("parentless fork to T"+tid);
				if (COUNT_EVENT) fake_fork.inc(tid);
				//Get the main thread
				final ShadowThread main = ShadowThread.get(0);
				synchronized(main) {
					if (DEBUG) Assert.assertTrue(main != null);
					
					//Create a hard edge from the last event in the main thread to the parentless first event in this thread
					final int main_tid = main.getTid();
					
					if (HB) {
						final CV main_hb = ts_get_hb(main);
						final CV hb = ts_get_hb(td);
						hb.max(main_hb);
						main_hb.inc(main_tid);
					}
					if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						final CV main_hb = ts_get_hb(main);
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						// Compute WCP before modifying HB
						wcp.max(main_hb); // Use HB here because this is a hard WCP edge
						hb.max(main_hb);
						main_hb.inc(main_tid);
					}
					if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
						final CV main_dc = ts_get_dc(main);
						final CV dc = ts_get_dc(td);
						dc.max(main_dc);
						main_dc.inc(main_tid);
					}
					if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						final CV main_wdc = ts_get_wdc(main);
						final CV wdc = ts_get_wdc(td);
						wdc.max(main_wdc);
						main_wdc.inc(main_tid);
					}
					
					// Handle race edges
					if (DC) {
						ts_set_eTd(main, ts_get_dc(main).get(main_tid));
					} else if (WDC) {
						ts_set_eTd(main, ts_get_wdc(main).get(main_tid));
					} else {
						ts_set_eTd(main, ts_get_hb(main).get(main_tid));
					}
				}
			}
		}
	}
	
	@Override
	public void acquire(final AcquireEvent ae) {
		final ShadowThread td = ae.getThread();
		synchronized(td) {
			final ShadowLock shadowLock = ae.getLock();
			final int tid = td.getTid();
			
			if (COUNT_EVENT) acquire.inc(tid);
	
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				if (currentCriticalSection != null && DEBUG) Assert.assertTrue(currentCriticalSection.shadowLock != shadowLock);
				thisEventNode = new AcqRelNode(-2/*ts_get_lastEventNode(td).eventNumber+1*/, shadowLock, tid, true, currentCriticalSection);
				updateCurrentCriticalSectionAtAcquire(td, thisEventNode);
			}
			handleEvent(ae, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			handleAcquire(td, shadowLock, thisEventNode, false);
			
			//Inc at acquire
			if (HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
			}
			if (DC || HB_WCP_DC) {
				ts_get_dc(td).inc(tid);
			}
			if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				ts_get_wdc(td).inc(tid);
			}
			
			// Handle race edges
			if (DC) {
				ts_set_eTd(td, ts_get_dc(td).get(tid));
			} else if (WDC) {
				ts_set_eTd(td, ts_get_wdc(td).get(tid));
			} else {
				ts_set_eTd(td, ts_get_hb(td).get(tid));
			}
			
			if (PRINT_EVENT) Util.log("acq("+Util.objectToIdentityString(shadowLock)+") by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
		}

		super.acquire(ae);
	}

	void handleAcquire(ShadowThread td, ShadowLock shadowLock, AcqRelNode thisEventNode, boolean isHardEdge) {
		// lockData is protected by the lock corresponding to the shadowLock being currently held
		final WDCLockData lockData = get(shadowLock);
		final int tid = td.getTid();
		
		if (!DISABLE_EVENT_GRAPH) {
			thisEventNode.eventNumber = lockData.latestRelNode == null ? thisEventNode.eventNumber : Math.max(thisEventNode.eventNumber, lockData.latestRelNode.eventNumber+1);
		}
		
		if (HB) {
			final CV hb = ts_get_hb(td);
			hb.max(lockData.hb);
		}
		
		// WCP, DC, WDC
		if (DEBUG) Assert.assertTrue(lockData.readVars.isEmpty() && lockData.writeVars.isEmpty());
		
		if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			final CV hb = ts_get_hb(td);
			final CV wcp = ts_get_wcp(td);
			hb.max(lockData.hb);
			if (isHardEdge) {
				wcp.max(lockData.hb); // If a hard edge, union WCP with HB
			} else {
				wcp.max(lockData.wcp);
			}
			
			final CV wcpUnionPO = new CV(wcp);
			wcpUnionPO.set(tid, hb.get(tid));
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.wcpAcqQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wcpAcqQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wcpAcqQueueMap.put(otherTD, queue);
					}
					queue.addLast(wcpUnionPO);
				}
			}
			
			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			ArrayDeque<CV> acqQueue = lockData.wcpAcqQueueMap.get(td);
			if (acqQueue == null) {
				acqQueue = lockData.wcpAcqQueueGlobal.clone();
				lockData.wcpAcqQueueMap.put(td, acqQueue);
			}
			ArrayDeque<CV> relQueue = lockData.wcpRelQueueMap.get(td);
			if (relQueue == null) {
				relQueue = lockData.wcpRelQueueGlobal.clone();
				lockData.wcpRelQueueMap.put(td, relQueue);
			}
			lockData.wcpAcqQueueGlobal.addLast(wcpUnionPO);
		}
		if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
			final CV dc = ts_get_dc(td);
			if (isHardEdge) {
				dc.max(lockData.dc);
			} // Don't max otherwise for DC, since it's not transitive with HB
			
			CVE dcCVE = new CVE(dc, thisEventNode);
			// acqQueueMap is protected by the lock corresponding to the shadowLock being currently held
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> ptQueue = lockData.dcAcqQueueMap.get(otherTD);
					if (ptQueue == null) {
						ptQueue = lockData.dcAcqQueueGlobal.clone();
						lockData.dcAcqQueueMap.put(otherTD, ptQueue);
					}
					ptQueue.addLast(td, dcCVE);
				}
			}
			
			// Also add to the queue that we'll use for any threads that haven't been created yet.
			// But before doing that, be sure to initialize *this thread's* queues for the lock using the global queues.
			PerThreadQueue<CVE> acqPTQueue = lockData.dcAcqQueueMap.get(td);
			if (acqPTQueue == null) {
				acqPTQueue = lockData.dcAcqQueueGlobal.clone();
				lockData.dcAcqQueueMap.put(td, acqPTQueue);
			}
			PerThreadQueue<CVE> relPTQueue = lockData.dcRelQueueMap.get(td);
			if (relPTQueue == null) {
				relPTQueue = lockData.dcRelQueueGlobal.clone();
				lockData.dcRelQueueMap.put(td, relPTQueue);
			}
			lockData.dcAcqQueueGlobal.addLast(td, dcCVE);
		}
		if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			final CV wdc = ts_get_wdc(td);
			if (isHardEdge) {
				wdc.max(lockData.wdc);
			} // Don't max otherwise for WDC, since it's not transitive with HB
		}
	}

	@Override
	public void release(final ReleaseEvent re) {
		final ShadowThread td = re.getThread();
		synchronized(td) {
			final ShadowLock shadowLock = re.getLock();
			
			if (COUNT_EVENT) release.inc(td.getTid());

			AcqRelNode thisEventNode = null;
			AcqRelNode matchingAcqNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				if (DEBUG) Assert.assertTrue(currentCriticalSection != null);
				thisEventNode = new AcqRelNode(-2, shadowLock, td.getTid(), false, currentCriticalSection);
				matchingAcqNode = updateCurrentCriticalSectionAtRelease(td, thisEventNode);
			}
			handleEvent(re, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) Util.log("rel("+Util.objectToIdentityString(shadowLock.getLock())+") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));

			handleRelease(td, shadowLock, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) {
				Assert.assertTrue(matchingAcqNode == thisEventNode.otherCriticalSectionNode);
			}
		}

		super.release(re);

	}
	
	void handleRelease(ShadowThread td, ShadowLock shadowLock, AcqRelNode thisEventNode) {
		final WDCLockData lockData = get(shadowLock);
		int tid = td.getTid();

		// Handle race edges
		if (DC) {
			ts_set_eTd(td, ts_get_dc(td).get(tid));
		} else if (WDC) {
			ts_set_eTd(td, ts_get_wdc(td).get(tid));
		} else {
			ts_set_eTd(td, ts_get_hb(td).get(tid));
		}
		
		if (!DISABLE_EVENT_GRAPH) {
			AcqRelNode myAcqNode = thisEventNode.surroundingCriticalSection;
			if (DEBUG) Assert.assertTrue(myAcqNode.isAcquire());
			// This release's corresponding acquire node should not be touched while the lock is currently held
			thisEventNode.otherCriticalSectionNode = myAcqNode;
			myAcqNode.otherCriticalSectionNode = thisEventNode;
		}
		
		if (HB) {
			final CV hb = ts_get_hb(td);
			
			// Assign to lock
			lockData.hb.assignWithResize(hb);
		}
		if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			final CV hb = ts_get_hb(td);
			final CV wcp = ts_get_wcp(td);
			final CV wcpUnionPO = new CV(wcp);
			wcpUnionPO.set(tid, hb.get(tid));
			
			// Process queue elements
			ArrayDeque<CV> acqQueue = lockData.wcpAcqQueueMap.get(td);
			ArrayDeque<CV> relQueue = lockData.wcpRelQueueMap.get(td);
			while (!acqQueue.isEmpty() && !acqQueue.peekFirst().anyGt(wcpUnionPO)) {
				acqQueue.removeFirst();
				wcp.max(relQueue.removeFirst());
			}
			
			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CV cv = lockData.wcpReadMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.wcpReadMap.put(var, cv);
				}
				cv.max(hb);
			}
			for (ShadowVar var : lockData.writeVars) {
				CV cv = lockData.wcpWriteMap.get(var);
				if (cv == null) {
					cv = new CV(WDCTool.INIT_CV_SIZE);
					lockData.wcpWriteMap.put(var, cv);
				}
				cv.max(hb);
			}
			
			// Assign to lock
			lockData.hb.assignWithResize(hb);
			lockData.wcp.assignWithResize(wcp);
			
			// Add to release queues
			CV hbCopy = new CV(hb);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					ArrayDeque<CV> queue = lockData.wcpRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.wcpRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.wcpRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(hbCopy);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.wcpRelQueueGlobal.addLast(hbCopy);
			
			// Clear read/write maps
			lockData.wcpReadMap = getPotentiallyShrunkMap(lockData.wcpReadMap);
			lockData.wcpWriteMap = getPotentiallyShrunkMap(lockData.wcpWriteMap);
		}
		if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
			final CV dc = ts_get_dc(td);
			
			// Process queue elements
			PerThreadQueue<CVE> acqPTQueue = lockData.dcAcqQueueMap.get(td);
			if (DEBUG) Assert.assertTrue(acqPTQueue.isEmpty(td));
			PerThreadQueue<CVE> relPTQueue = lockData.dcRelQueueMap.get(td);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					while (!acqPTQueue.isEmpty(otherTD) && !acqPTQueue.peekFirst(otherTD).anyGt(dc)) {
						acqPTQueue.removeFirst(otherTD);
						CVE prevRel = relPTQueue.removeFirst(otherTD);
						
						if (!DISABLE_EVENT_GRAPH && (DC || HB_WCP_DC)) {
							// If local VC is up-to-date w.r.t. prevRel, then no need to add a new edge
							// Protected by the fact that the lock is currently held
							if (prevRel.anyGt(dc)) {
								EventNode.addEdge(prevRel.eventNode, thisEventNode);
							}
						}
						
						dc.max(prevRel);
					}
				}
			}
			
			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CVE cv = lockData.dcReadMap.get(var);
				if (cv == null) {
					cv = new CVE(new CV(WDCTool.INIT_CV_SIZE), thisEventNode);
					lockData.dcReadMap.put(var, cv);
				} else {
					cv.setEventNode(thisEventNode);
				}
				cv.max(dc);
			}
			for (ShadowVar var : lockData.writeVars) {
				CVE cv = lockData.dcWriteMap.get(var);
				if (cv == null) {
					cv = new CVE(new CV(WDCTool.INIT_CV_SIZE), thisEventNode);
					lockData.dcWriteMap.put(var, cv);
				} else {
					cv.setEventNode(thisEventNode);
				}
				cv.max(dc);
			}
			
			// Assign to lock
			lockData.dc.assignWithResize(dc); // Used for hard notify -> wait edge
			
			// Add to release queues
			CVE dcCVE = new CVE(dc, thisEventNode);
			for (ShadowThread otherTD : ShadowThread.getThreads()) {
				if (otherTD != td) {
					PerThreadQueue<CVE> queue = lockData.dcRelQueueMap.get(otherTD);
					if (queue == null) {
						queue = lockData.dcRelQueueGlobal.clone(); // Include any stuff that didn't get added because otherTD hadn't been created yet
						lockData.dcRelQueueMap.put(otherTD, queue);
					}
					queue.addLast(td, dcCVE);
				}
			}
			// Also add to the queue that we'll use for any threads that haven't been created yet
			lockData.dcRelQueueGlobal.addLast(td, dcCVE);
			
			// Clear read/write maps
			lockData.dcReadMap = getPotentiallyShrunkMap(lockData.dcReadMap);
			lockData.dcWriteMap = getPotentiallyShrunkMap(lockData.dcWriteMap);
		}
		if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			final CV wdc = ts_get_wdc(td);
			
			// Rule (a)
			for (ShadowVar var : lockData.readVars) {
				CVE cv = lockData.wdcReadMap.get(var);
				if (cv == null) {
					cv = new CVE(new CV(WDCTool.INIT_CV_SIZE), thisEventNode);
					lockData.wdcReadMap.put(var, cv);
				} else {
					cv.setEventNode(thisEventNode);
				}
				cv.max(wdc);
			}
			for (ShadowVar var : lockData.writeVars) {
				CVE cv = lockData.wdcWriteMap.get(var);
				if (cv == null) {
					cv = new CVE(new CV(WDCTool.INIT_CV_SIZE), thisEventNode);
					lockData.wdcWriteMap.put(var, cv);
				} else {
					cv.setEventNode(thisEventNode);
				}
				cv.max(wdc);
			}
			
			// Assign to lock
			lockData.wdc.assignWithResize(wdc); // Used for hard notify -> wait edge
			
			// Clear read/write maps
			lockData.wdcReadMap = getPotentiallyShrunkMap(lockData.wdcReadMap);
			lockData.wdcWriteMap = getPotentiallyShrunkMap(lockData.wdcWriteMap);
		}

		// Clear read/write Vars. HB and PIP configuration does not keep track of read/write Vars
		if (!HB) {
			lockData.readVars = new HashSet<ShadowVar>();
			lockData.writeVars = new HashSet<ShadowVar>();
		}
		
		// Do the increments last
		// Safe since accessed by only this thread
		if (HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
		}
		if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
			ts_get_dc(td).inc(tid);
		}
		if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			ts_get_wdc(td).inc(tid);
		}
		
		//Set latest Release node for lockData
		lockData.latestRelNode = thisEventNode;
	}
	
	static <K,V> WeakIdentityHashMap<K,V>getPotentiallyShrunkMap(WeakIdentityHashMap<K,V> map) {
		if (map.tableSize() > 16 &&
		    10 * map.size() < map.tableSize() * map.loadFactorSize()) {
			return new WeakIdentityHashMap<K,V>(2 * (int)(map.size() / map.loadFactorSize()), map);
		}
		return map;
	}
	
	public static boolean readFastPath(final ShadowVar orig, final ShadowThread td) {
		WDCGuardState x = (WDCGuardState)orig;
		
		synchronized(td) {
			if (HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				final CV hb = ts_get_hb(td);
				if (x.hbRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
			if (DC) {
				final CV dc = ts_get_dc(td);
				if (x.dcRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
			if (WDC) {
				final CV wdc = ts_get_wdc(td);
				if (x.wdcRead.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) readFP.inc(td.getTid());
					return true;
				}
			}
		}
		return false;
	}
	
	public static boolean writeFastPath(final ShadowVar orig, final ShadowThread td) {
		WDCGuardState x = (WDCGuardState)orig;
			
		synchronized(td) {
			if (HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				final CV hb = ts_get_hb(td);
				if (x.hbWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
			if (DC) {
				final CV dc = ts_get_dc(td);
				if (x.dcWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
			if (WDC) {
				final CV wdc = ts_get_wdc(td);
				if (x.wdcWrite.get(td.getTid()) >= ts_get_eTd(td)) {
					if (COUNT_EVENT) writeFP.inc(td.getTid());
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void access(final AccessEvent fae) {
		final ShadowVar orig = fae.getOriginalShadow();
		final ShadowThread td = fae.getThread();

		if (orig instanceof WDCGuardState) {

			synchronized(td) {
				if (COUNT_EVENT) {
					if (fae.isWrite()) {
						if (COUNT_EVENT) write.inc(td.getTid());
						if (COUNT_EVENT) {
							if (getCurrentCriticalSection(td) != null) {
								write_inside.inc(td.getTid());
							} else {
								write_outside.inc(td.getTid());
							}
						}
					} else {
						if (COUNT_EVENT) read.inc(td.getTid());
						if (COUNT_EVENT) {
							if (getCurrentCriticalSection(td) != null) {
								read_inside.inc(td.getTid());
							} else {
								read_outside.inc(td.getTid());
							}
						}
					}
				}
				if (COUNT_EVENT) {
					if (getCurrentCriticalSection(td) != null) {
						access_inside.inc(td.getTid());
					} else {
						access_outside.inc(td.getTid());
					}
				}

				WDCGuardState x = (WDCGuardState)orig;
				int tid = td.getTid();
				
				String fieldName = "";
				if (PRINT_EVENT || !DISABLE_EVENT_GRAPH) {
					if (EventNode.DEBUG_ACCESS_INFO) {
						if (fae instanceof FieldAccessEvent) {
							fieldName = ((FieldAccessEvent)fae).getInfo().getField().getName();						
						} else if (fae instanceof ArrayAccessEvent) {
							fieldName = Util.objectToIdentityString(fae.getTarget()) + "[" + ((ArrayAccessEvent)fae).getIndex() + "]";						
						}
					}
				}
				
				RdWrNode thisEventNode = null;
				if (!DISABLE_EVENT_GRAPH) {
					AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
					if (EventNode.DEBUG_ACCESS_INFO) {
						thisEventNode = new RdWrDebugNode(-2, fae.isWrite(), fieldName, x, td.getTid(), currentCriticalSection);
					} else {
						thisEventNode = new RdWrNode(-2, td.getTid(), currentCriticalSection);
					}
				}
				
				handleEvent(fae, thisEventNode);
				if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

				// Even though we capture clinit edges via classAccessed(), it doesn't seem to capture quite everything.
				// In any case, FT2 also does the following in access() in addition to classAccessed().
				Object target = fae.getTarget();
				if (target == null) {
					synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
						WDCVolatileData initTime = classInitTime.get(((FieldAccessEvent)fae).getInfo().getField().getOwner());
						if (HB) ts_get_hb(td).max(initTime.hbWrite);
						if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_hb(td).max(initTime.hbWrite);
							ts_get_wcp(td).max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
						}
						if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
							ts_get_dc(td).max(initTime.dcWrite);
						}
						if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_wdc(td).max(initTime.wdcWrite);
						}
						
						if (!DISABLE_EVENT_GRAPH) {
							//No need to add edges to an event graph HB and WCP since these relations are sound, only the eventNumber is needed.
							if (DC || HB_WCP_DC) {
								if (initTime.dcWrite.anyGt(ts_get_dc(td))) {
									EventNode.addEdge(initTime.dcWrite.eventNode, thisEventNode);
								}
							} else if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								if (initTime.wdcWrite.anyGt(ts_get_wdc(td))) {
									EventNode.addEdge(initTime.wdcWrite.eventNode, thisEventNode);
								}
							}
						}
					}
				}
				

				// Update variables accessed in critical sections for rule (a)
				for (int i = td.getNumLocksHeld() - 1; i >= 0; i--) {
					ShadowLock lock = td.getHeldLock(i);
					WDCLockData lockData = get(lock);

					// Account for conflicts with prior critical section instances
					if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						final CV wcp = ts_get_wcp(td);
						final CV priorCriticalSectionAfterWrite = lockData.wcpWriteMap.get(x);
						if (priorCriticalSectionAfterWrite != null) {
							wcp.max(priorCriticalSectionAfterWrite);
						}
						if (fae.isWrite()) {
							CV priorCriticalSectionAfterRead = lockData.wcpReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
								wcp.max(priorCriticalSectionAfterRead);
							}
						}
					}
					if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
						final CV dc = ts_get_dc(td);
						final CV priorCriticalSectionAfterWrite = lockData.dcWriteMap.get(x);
						if (priorCriticalSectionAfterWrite != null) {
							
							if (!DISABLE_EVENT_GRAPH && (DC || HB_WCP_DC)) {
								if (priorCriticalSectionAfterWrite.anyGt(dc)) {
									EventNode.addEdge(((CVE)priorCriticalSectionAfterWrite).eventNode, thisEventNode);
								}
							}
							
							dc.max(priorCriticalSectionAfterWrite);
						}
						if (fae.isWrite()) {
							CVE priorCriticalSectionAfterRead = lockData.dcReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
	
								if (!DISABLE_EVENT_GRAPH && (DC || HB_WCP_DC)) {
									if (priorCriticalSectionAfterRead.anyGt(dc)) {
										//Changed
										EventNode.addEdge(((CVE)priorCriticalSectionAfterRead).eventNode, thisEventNode);
									}
								}
	
								dc.max(priorCriticalSectionAfterRead);
							}
						}
					}
					if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						final CV wdc = ts_get_wdc(td);
						final CV priorCriticalSectionAfterWrite = lockData.wdcWriteMap.get(x);
						if (priorCriticalSectionAfterWrite != null) {
							
							if (!DISABLE_EVENT_GRAPH && (WDC || HB_WCP_WDC || HB_WCP_DC_WDC)) {
								if (priorCriticalSectionAfterWrite.anyGt(wdc)) {
									EventNode.addEdge(((CVE)priorCriticalSectionAfterWrite).eventNode, thisEventNode);
								}
							}
							
							wdc.max(priorCriticalSectionAfterWrite);
						}
						if (fae.isWrite()) {
							CVE priorCriticalSectionAfterRead = lockData.wdcReadMap.get(x);
							if (priorCriticalSectionAfterRead != null) {
	
								if (!DISABLE_EVENT_GRAPH && (WDC || HB_WCP_WDC || HB_WCP_DC_WDC)) {
									if (priorCriticalSectionAfterRead.anyGt(wdc)) {
										//Changed
										EventNode.addEdge(((CVE)priorCriticalSectionAfterRead).eventNode, thisEventNode);
									}
								}
	
								wdc.max(priorCriticalSectionAfterRead);
							}
						}
					}
					
					// Keep track of accesses within ongoing critical section
					if (!HB) { // HB analysis does not use read/write Vars
						if (fae.isWrite()) {
							lockData.writeVars.add(x);
						} else {
							lockData.readVars.add(x);
						}
					}
				}
				
				// Have to lock on variable x here until the end of the access event
				synchronized(x) {
					boolean foundRace = false;
					// Check for races: HB ⊆ WCP ⊆ DC ⊆ WDC, HB ⊆ DC
					if (HB) {
						final CV hb = ts_get_hb(td);
						foundRace = checkForRacesHB(fae.isWrite(), x, fae, tid, hb, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							hb.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
								}
							}
						}
					}
					if (WCP) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						foundRace = checkForRacesWCP(fae.isWrite(), x, fae, tid, hb, wcp, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV wcpUnionPO = new CV(wcp);
								wcpUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
									Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
								}
							}
						}
					}
					if (DC) {
						final CV dc = ts_get_dc(td);
						foundRace = checkForRacesDC(fae.isWrite(), x, fae, tid, dc, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							dc.max(x.dcWrite);
							if (fae.isWrite()) {
								dc.max(x.dcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.dcWrite.anyGt(dc));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.dcReadsJoined.anyGt(dc));
								}
							}
						}
					}
					if (HB_WCP_DC) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV dc = ts_get_dc(td);
						foundRace = checkForRacesDC(fae.isWrite(), x, fae, tid, hb, wcp, dc, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							dc.max(x.dcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								dc.max(x.dcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV wcpUnionPO = new CV(wcp);
								wcpUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
								Assert.assertTrue(!x.dcWrite.anyGt(dc));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
									Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
									Assert.assertTrue(!x.dcReadsJoined.anyGt(dc));
								}
							}
						}
					}
					if (WDC) {
						final CV wdc = ts_get_wdc(td);
						foundRace = checkForRacesWDC(fae.isWrite(), x, fae, tid, wdc, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
								}
							}
						}
					}
					if (HB_WCP_WDC) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV wdc = ts_get_wdc(td);
						foundRace = checkForRacesWDC(fae.isWrite(), x, fae, tid, hb, wcp, wdc, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV wcpUnionPO = new CV(wcp);
								wcpUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
								Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
									Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
									Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
								}
							}
						}
					}
					if (HB_WCP_DC_WDC) {
						final CV hb = ts_get_hb(td);
						final CV wcp = ts_get_wcp(td);
						final CV dc = ts_get_dc(td);
						final CV wdc = ts_get_wdc(td);
						foundRace = checkForRacesWDC(fae.isWrite(), x, fae, tid, hb, wcp, dc, wdc, thisEventNode);
						// Update thread VCs if race detected (to correspond with edge being added)
						if (foundRace && !DISABLE_EVENT_GRAPH) {
							hb.max(x.hbWrite);
							wcp.max(x.hbWrite);
							dc.max(x.dcWrite);
							wdc.max(x.wdcWrite);
							if (fae.isWrite()) {
								hb.max(x.hbReadsJoined);
								wcp.max(x.hbReadsJoined);
								dc.max(x.dcReadsJoined);
								wdc.max(x.wdcReadsJoined);
							}
						} else { // Check that we don't need to update CVs if there was no race)
							if (DEBUG) {
								Assert.assertTrue(!x.hbWrite.anyGt(hb));
								final CV wcpUnionPO = new CV(wcp);
								wcpUnionPO.set(tid, hb.get(tid));
								Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
								Assert.assertTrue(!x.dcWrite.anyGt(dc));
								Assert.assertTrue(!x.wdcWrite.anyGt(wdc));
								if (fae.isWrite()) {
									Assert.assertTrue(!x.hbReadsJoined.anyGt(hb));
									Assert.assertTrue(!x.wcpReadsJoined.anyGt(wcpUnionPO));
									Assert.assertTrue(!x.dcReadsJoined.anyGt(dc));
									Assert.assertTrue(!x.wdcReadsJoined.anyGt(wdc));
								}
							}
						}
					}					
	
					if (!DISABLE_EVENT_GRAPH && !DISABLE_MERGING) {
						// Can combine two consecutive write/read nodes that have the same VC.
						// We might later add an outgoing edge from the prior node, but that's okay.
						EventNode oldThisEventNode = thisEventNode;
						thisEventNode = thisEventNode.tryToMergeWithPrior();
						// If merged, undo prior increment
						if (thisEventNode != oldThisEventNode) {
							if (HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								ts_get_hb(td).inc(tid, -1);
							}
							if (DC || HB_WCP_DC) {
								ts_get_dc(td).inc(tid, -1);
							}
							if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
								ts_get_wdc(td).inc(tid, -1);
							}
							ts_set_lastEventNode(td, thisEventNode);
						}
					}
					
						// Update vector clocks
						if (HB) {
							final CV hb = ts_get_hb(td);
							if (fae.isWrite()) {
								x.hbWrite.assignWithResize(hb);
							} else {
								x.hbRead.set(tid, hb.get(tid));
								x.hbReadsJoined.max(hb);
							}
						}
						if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							final CV hb = ts_get_hb(td);
							final CV wcp = ts_get_wcp(td);
							final CV wcpUnionPO = new CV(wcp);
							wcpUnionPO.set(tid, hb.get(tid));
							
							if (fae.isWrite()) {
								x.hbWrite.assignWithResize(hb);
								x.wcpWrite.assignWithResize(wcpUnionPO);
							} else {
								x.hbRead.set(tid, hb.get(tid));
								x.hbReadsJoined.max(hb);
								x.wcpRead.set(tid, hb.get(tid));
								x.wcpReadsJoined.max(wcpUnionPO);
							}
						}
						if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
							final CV dc = ts_get_dc(td);
							if (fae.isWrite()) {
								x.dcWrite.assignWithResize(dc);
							} else {
								x.dcRead.set(tid, dc.get(tid));
								x.dcReadsJoined.max(dc);
							}
						}
						if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							final CV wdc = ts_get_wdc(td);
							if (fae.isWrite()) {
								x.wdcWrite.assignWithResize(wdc);
							} else {
								x.wdcRead.set(tid, wdc.get(tid));
								x.wdcReadsJoined.max(wdc);
							}
						}
					
						// Update last event
						final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
						DynamicSourceLocation dl = new DynamicSourceLocation(fae, thisEventNode, (me == null ? null : me.getInfo()));
						
						if (fae.isWrite()) {
							x.lastWriteTid = tid;
							x.lastWriteEvent = dl;
						} else {
							x.lastReadEvents[tid] = dl;
						}
						
						// These increments are needed because we might end up creating an outgoing WDC edge from this access event
						// (if it turns out to be involved in a WDC-race).
						// Only if there is an outgoing WDC edge should the thread's CV be updated.
						if (HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_hb(td).inc(tid); // Don't increment WCP since it doesn't include PO
						}
						if (DC || HB_WCP_DC) {
							ts_get_dc(td).inc(tid);
						}
						if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
							ts_get_wdc(td).inc(tid);
						}
				}
				
				if (PRINT_EVENT) {		
					if (fae.isWrite()) {
						Util.log("wr("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
					} else {
						Util.log("rd("+ fieldName +") by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
					}
				}
			}
		} else {
			if (VERBOSE) Util.log("Not expecting to reach here for access event: " + fae.getClass() + " | original shadow: " + fae.getOriginalShadow());
			if (DEBUG) Assert.assertTrue(false); // Not expecting to reach here
			super.access(fae);
		}
	}

	public boolean recordRace(WDCGuardState x, AccessEvent ae, int tid, int shortestRaceTid, boolean shortestRaceIsWrite, RaceType shortestRaceType, RdWrNode thisEventNode) {
		if (shortestRaceTid >= 0) {
			DynamicSourceLocation priorDL = (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]);
			// Report race
			error(ae, shortestRaceType.relation(), shortestRaceIsWrite ? "write by T" : "read by T", shortestRaceTid, priorDL,
			      ae.isWrite() ? "write by T" : "read by T", tid);

			StaticRace static_second_site_Race;
			StaticRace static_unordered_pairs_Race = null;
			// Record the WDC-race for later processing
			if (!DISABLE_EVENT_GRAPH) {
				// Check assertions:
				//This assertion is checked in crazyNewEdges() when adding back/forward edges. It fails here, but not when checked in crazyNewEdges().
				//Both traversals below are forward traversals, so sinkOrSinks is accessed during the bfsTraversal. The bfsTraversal is not executed atomically which causes the assertion to fail at different event counts.
				Assert.assertTrue(thisEventNode != null);

				ShadowThread td = ae.getThread();
				final MethodEvent me = td.getBlockDepth() <= 0 ? null : td.getBlock(td.getBlockDepth()-1); //This is how RREventGenerator retrieves a method event
				static_second_site_Race = new StaticRace(null, ae.getAccessInfo().getLoc(), (RdWrNode)priorDL.eventNode, thisEventNode, shortestRaceType, priorDL.eventMI, me.getInfo());
				if (UNORDERED_PAIRS) {
					static_unordered_pairs_Race = new StaticRace(priorDL.loc, ae.getAccessInfo().getLoc(), (RdWrNode)priorDL.eventNode, thisEventNode, shortestRaceType, priorDL.eventMI, me.getInfo());
					StaticRace.races.add(static_unordered_pairs_Race);
				} else {
					StaticRace.races.add(static_second_site_Race);
				}
			} else {
				static_second_site_Race = new StaticRace(null, ae.getAccessInfo().getLoc());
				if (UNORDERED_PAIRS) static_unordered_pairs_Race = new StaticRace(priorDL.loc, ae.getAccessInfo().getLoc());
			}
			
			// Record the static race for statistics
			StaticRace.addRace(static_second_site_Race, shortestRaceType, StaticRace.static_second_site_RaceMap);
			if (UNORDERED_PAIRS) StaticRace.addRace(static_unordered_pairs_Race, shortestRaceType, StaticRace.static_unordered_pairs_RaceMap);
			
			return true;
		}
		return false;
	}
	
	// NOTE: This should be protected by the lock on variable x in the access event
	boolean checkForRacesHB(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, RdWrNode thisEventNode) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.hbWrite.anyGt(hb)) {
			RaceType type = RaceType.HBRace;
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.hbRead.anyGt(hb)) {
				int index = -1;
				while ((index = x.hbRead.nextGt(hb, index + 1)) != -1) {
					RaceType type = RaceType.HBRace;
					//Update the latest race with the current race since we only want to report one race per access event
					if (SHORTEST_RACEEDGE) {
						DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
						if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
							shortestRaceTid = index;
							shortestRaceIsWrite = false;
							shortestRaceType = type;
						}
					} else {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
				}
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesWCP(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wcpWrite.anyGt(wcpUnionPO)) {
			RaceType type = RaceType.WCPRace;
			if (x.hbWrite.anyGt(hb)) {
				type = RaceType.HBRace;
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
		} else {
			if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wcpRead.anyGt(wcpUnionPO)) {
				int index = -1;
				while ((index = x.wcpRead.nextGt(wcpUnionPO, index + 1)) != -1) {
					RaceType type = RaceType.WCPRace;
					if (x.hbRead.get(index) > hb.get(index)) {
						type = RaceType.HBRace;
					}
					//Update the latest race with the current race since we only want to report one race per access event
					if (SHORTEST_RACEEDGE) {
						DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
						if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
							shortestRaceTid = index;
							shortestRaceIsWrite = false;
							shortestRaceType = type;
						}
					} else {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV dc, RdWrNode thisEventNode) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.dcWrite.anyGt(dc)) {
			RaceType type = RaceType.DCRace;
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
			// Add event node edge
			if (!DISABLE_EVENT_GRAPH) {
				EventNode.addEdge(x.lastWriteEvent.eventNode, thisEventNode);
			}
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.dcRead.anyGt(dc)) {
				int index = -1;
				while ((index = x.dcRead.nextGt(dc, index + 1)) != -1) {
					RaceType type = RaceType.DCRace;
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (DEBUG) Assert.assertTrue(x.lastReadEvents[index] != null);
					if (!DISABLE_EVENT_GRAPH && DEBUG) Assert.assertTrue(x.lastReadEvents[index].eventNode != null);
					if (!DISABLE_EVENT_GRAPH) {
						// This thread's last reader node might be same as the last writer node, due to merging 
						if (x.lastWriteEvent != null && x.lastReadEvents[index].eventNode == x.lastWriteEvent.eventNode) {
							if (DEBUG) Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvent.eventNode, thisEventNode));
						} else {
							EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
						}
					}
				}
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp, CV dc, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.dcWrite.anyGt(dc)) {
			RaceType type = RaceType.DCRace;
			if (x.wcpWrite.anyGt(wcpUnionPO)) {
				type = RaceType.WCPRace;
				if (x.hbWrite.anyGt(hb)) {
					type = RaceType.HBRace;
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
			// Add event node edge
			if (!DISABLE_EVENT_GRAPH) {
				EventNode.addEdge(x.lastWriteEvent.eventNode, thisEventNode);
			}
		} else {
			if (DEBUG) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
			if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.dcRead.anyGt(dc)) {
				int index = -1;
				while ((index = x.dcRead.nextGt(dc, index + 1)) != -1) {
					RaceType type = RaceType.DCRace;
					if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
						type = RaceType.WCPRace;
						if (x.hbRead.get(index) > hb.get(index)) {
							type = RaceType.HBRace;
						}
					} else {
						if (DEBUG) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
					}
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (DEBUG) Assert.assertTrue(x.lastReadEvents[index] != null);
					if (!DISABLE_EVENT_GRAPH && DEBUG) Assert.assertTrue(x.lastReadEvents[index].eventNode != null);
					if (!DISABLE_EVENT_GRAPH) {
						// This thread's last reader node might be same as the last writer node, due to merging
						if (x.lastWriteEvent != null && x.lastReadEvents[index].eventNode == x.lastWriteEvent.eventNode) {
							if (DEBUG) Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvent.eventNode, thisEventNode));
						} else {
							EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
						}
					}
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));
				if (DEBUG) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesWDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV wdc, RdWrNode thisEventNode) {
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			RaceType type = RaceType.WDCRace;
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
			// Add event node edge
			if (!DISABLE_EVENT_GRAPH) {
				EventNode.addEdge(x.lastWriteEvent.eventNode, thisEventNode);
			}
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (DEBUG) Assert.assertTrue(x.lastReadEvents[index] != null);
					if (!DISABLE_EVENT_GRAPH && DEBUG) Assert.assertTrue(x.lastReadEvents[index].eventNode != null);
					if (!DISABLE_EVENT_GRAPH) {
						// This thread's last reader node might be same as the last writer node, due to merging
						if (x.lastWriteEvent != null && x.lastReadEvents[index].eventNode == x.lastWriteEvent.eventNode) {
							if (DEBUG) Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvent.eventNode, thisEventNode));
						} else {
							EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
						}
					}
				}
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesWDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp, CV wdc, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			RaceType type = RaceType.WDCRace;
			if (x.wcpWrite.anyGt(wcpUnionPO)) {
				type = RaceType.WCPRace;
				if (x.hbWrite.anyGt(hb)) {
					type = RaceType.HBRace;
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
			// Add event node edge
			if (!DISABLE_EVENT_GRAPH) {
				EventNode.addEdge(x.lastWriteEvent.eventNode, thisEventNode);
			}
		} else {
			if (DEBUG) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
			if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
						type = RaceType.WCPRace;
						if (x.hbRead.get(index) > hb.get(index)) {
							type = RaceType.HBRace;
						}
					} else {
						if (DEBUG) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
					}
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (DEBUG) Assert.assertTrue(x.lastReadEvents[index] != null);
					if (!DISABLE_EVENT_GRAPH && DEBUG) Assert.assertTrue(x.lastReadEvents[index].eventNode != null);
					if (!DISABLE_EVENT_GRAPH) {
						// This thread's last reader node might be same as the last writer node, due to merging
						if (x.lastWriteEvent != null && x.lastReadEvents[index].eventNode == x.lastWriteEvent.eventNode) {
							if (DEBUG) Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvent.eventNode, thisEventNode));
						} else {
							EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
						}
					}
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));
				if (DEBUG) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	boolean checkForRacesWDC(boolean isWrite, WDCGuardState x, AccessEvent ae, int tid, CV hb, CV wcp, CV dc, CV wdc, RdWrNode thisEventNode) {
		final CV wcpUnionPO = new CV(wcp);
		wcpUnionPO.set(tid, hb.get(tid));
		int shortestRaceTid = -1;
		boolean shortestRaceIsWrite = false; // only valid if shortestRaceTid != -1
		RaceType shortestRaceType = RaceType.DCOrdered; // only valid if shortestRaceTid != -1
		
		// First check for race with prior write
		if (x.wdcWrite.anyGt(wdc)) {
			RaceType type = RaceType.WDCRace;
			if (x.dcWrite.anyGt(dc)) {
				type = RaceType.DCRace;
				if (x.wcpWrite.anyGt(wcpUnionPO)) {
					type = RaceType.WCPRace;
					if (x.hbWrite.anyGt(hb)) {
						type = RaceType.HBRace;
					}
				} else {
					if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
				if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
			}
			shortestRaceTid = x.lastWriteTid;
			shortestRaceIsWrite = true;
			shortestRaceType = type;
			// Add event node edge
			if (!DISABLE_EVENT_GRAPH) {
				EventNode.addEdge(x.lastWriteEvent.eventNode, thisEventNode);
			}
		} else {
			if (DEBUG) Assert.assertTrue(!x.dcWrite.anyGt(dc));
			if (DEBUG) Assert.assertTrue(!x.wcpWrite.anyGt(wcpUnionPO));
			if (DEBUG) Assert.assertTrue(!x.hbWrite.anyGt(hb));
		}
		// Next check for races with prior reads
		if (isWrite) {
			if (x.wdcRead.anyGt(wdc)) {
				int index = -1;
				while ((index = x.wdcRead.nextGt(wdc, index + 1)) != -1) {
					RaceType type = RaceType.WDCRace;
					if (x.dcRead.get(index) > dc.get(index)) {
						type = RaceType.DCRace;
						if (x.wcpRead.get(index) > wcpUnionPO.get(index)) {
							type = RaceType.WCPRace;
							if (x.hbRead.get(index) > hb.get(index)) {
								type = RaceType.HBRace;
							}
						} else {
							if (DEBUG) Assert.assertTrue(x.hbRead.get(index) <= hb.get(index));
						}
					} else {
						if (DEBUG) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));
						if (DEBUG) Assert.assertTrue(!x.hbRead.anyGt(hb));
					}
					//Update the latest race with the current race since we only want to report one race per access event
					DynamicSourceLocation dl = shortestRaceTid >= 0 ? (shortestRaceIsWrite ? x.lastWriteEvent : x.lastReadEvents[shortestRaceTid]) : null;
					if (DISABLE_EVENT_GRAPH || (dl == null || x.lastReadEvents[index].eventNode.eventNumber > dl.eventNode.eventNumber)) {
						shortestRaceTid = index;
						shortestRaceIsWrite = false;
						shortestRaceType = type;
					}
					if (DEBUG) Assert.assertTrue(x.lastReadEvents[index] != null);
					if (!DISABLE_EVENT_GRAPH && DEBUG) Assert.assertTrue(x.lastReadEvents[index].eventNode != null);
					if (!DISABLE_EVENT_GRAPH) {
						// This thread's last reader node might be same as the last writer node, due to merging
						if (x.lastWriteEvent != null && x.lastReadEvents[index].eventNode == x.lastWriteEvent.eventNode) {
							if (DEBUG) Assert.assertTrue(EventNode.edgeExists(x.lastWriteEvent.eventNode, thisEventNode));
						} else {
							EventNode.addEdge(x.lastReadEvents[index].eventNode, thisEventNode);
						}
					}
				}
			} else {
				if (DEBUG) Assert.assertTrue(!x.dcRead.anyGt(dc));
				if (DEBUG) Assert.assertTrue(!x.wcpRead.anyGt(wcpUnionPO));
				if (DEBUG) Assert.assertTrue(!x.hbRead.anyGt(hb));
			}
		}
		return recordRace(x, ae, tid, shortestRaceTid, shortestRaceIsWrite, shortestRaceType, thisEventNode);
	}
	
	@Override
	public void volatileAccess(final VolatileAccessEvent fae) {
		final ShadowThread td = fae.getThread();
		synchronized(td) {
			int tid = td.getTid();
			
			if (COUNT_EVENT) {
				if (fae.isWrite()) {
					volatile_write.inc(tid);
				} else {
					volatile_read.inc(tid);
				}
			}			
			
			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //Volatiles are treated the same as non-volatile rd/wr accesses since each release does not have a corresponding acquire
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, tid, currentCriticalSection, "volatileAccess");
			}
			
			handleEvent(fae, thisEventNode);
			
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				String fieldName = fae.getInfo().getField().getName();
				if (fae.isWrite()) {
					Util.log("volatile wr("+ fieldName +") by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
				} else {
					Util.log("volatile rd("+ fieldName +") by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
				}
			}
			
			//Lock on the volatile variable vd
			WDCVolatileData vd = get(fae.getShadowVolatile());
			synchronized(vd) {
				// Handle race edges
				if (DC) {
					ts_set_eTd(td, ts_get_dc(td).get(tid));
				} else if (WDC) {
					ts_set_eTd(td, ts_get_wdc(td).get(tid));
				} else {
					ts_set_eTd(td, ts_get_hb(td).get(tid));
				}
				
				//For generating event node graph
				if (!DISABLE_EVENT_GRAPH) {
					//No need to add edges to an event graph HB and WCP since these relations are sound, only the eventNumber is needed.
					if (DC || HB_WCP_DC) {
						final CV dc = ts_get_dc(td);
						if (fae.isWrite()) {
							// Add edge from last read node to this write node
							if (vd.dcReads.anyGt(dc)) {
								int index = -1;
								while ((index = vd.dcReads.nextGt(dc, index + 1)) != -1) {
									EventNode.addEdge(vd.lastReadEvents[index], thisEventNode);
								}
							}
						}
						// Add edge from last write node to this event node
						if (vd.dcWrites.anyGt(dc)) {
							int index = -1;
							while ((index = vd.dcWrites.nextGt(dc, index + 1)) != -1) {
								EventNode.addEdge(vd.lastWriteEvents[index], thisEventNode);
							}
						}
					} else if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						final CV wdc = ts_get_wdc(td);
						if (fae.isWrite()) {
							// Add edge from last read node to this write node
							if (vd.wdcReads.anyGt(wdc)) {
								int index = -1;
								while ((index = vd.wdcReads.nextGt(wdc, index + 1)) != -1) {
									EventNode.addEdge(vd.lastReadEvents[index], thisEventNode);
								}
							}
						}
						// Add edge from last write node to this event node
						if (vd.wdcWrites.anyGt(wdc)) {
							int index = -1;
							while ((index = vd.wdcWrites.nextGt(wdc, index + 1)) != -1) {
								EventNode.addEdge(vd.lastWriteEvents[index], thisEventNode);
							}
						}
					}
				}
				
				// Join with incoming edge
				if (HB) {
					final CV hb = ts_get_hb(td);
					if (fae.isWrite()) {
						//incoming rd-wr edge
						hb.max(vd.hbReadsJoined);
					}
					//incoming wr-wr (if fae.isWrite()) edge or incoming wr-rd (if fae.isRead()) edge
					hb.max(vd.hbWrite);
				}
				if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					if (fae.isWrite()) {
						//incoming rd-wr edge
						hb.max(vd.hbReadsJoined);
						wcp.max(vd.hbReadsJoined);
					}
					//incoming wr-wr (if fae.isWrite()) edge or incoming wr-rd (if fae.isRead()) edge
					hb.max(vd.hbWrite);
					wcp.max(vd.hbWrite); // Union with HB since a volatile write-read edge is effectively a hard WCP edge
				}
				if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
					final CV dc = ts_get_dc(td);
					if (fae.isWrite()) {
						//incoming rd-wr edge		
						dc.max(vd.dcReadsJoined);
					}
					//incoming wr-wr (if fae.isWrite()) edge or incoming wr-rd (if fae.isRead()) edge
					dc.max(vd.dcWrite);
				}
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV wdc = ts_get_wdc(td);
					if (fae.isWrite()) {
						//incoming rd-wr edge
						wdc.max(vd.wdcReadsJoined);
					}
					//incoming wr-wr (if fae.isWrite()) edge or incoming wr-rd (if fae.isRead()) edge
					wdc.max(vd.wdcWrite);
				}
				
				// Update volatile VC by joining with current thread's VC
				if (HB) {
					final CV hb = ts_get_hb(td);
					if (fae.isWrite()) {
						//outgoing wr-wr and wr-rd race
						vd.hbWrite.max(hb);
					} else {
						//outgoing rd-wr edge
						vd.hbReadsJoined.max(hb);
					}
				}
				if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					if (fae.isWrite()) {
						//outgoing wr-wr and wr-rd edge
						vd.hbWrite.max(hb);
						vd.wcpWrite.max(hb); // Don't increment since WCP doesn't include PO
					} else {
						//outgoing rd-wr edge
						vd.hbReadsJoined.max(hb);
						vd.wcpReadsJoined.max(hb);
					}
				}
				if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
					final CV dc = ts_get_dc(td);
					if (fae.isWrite()) {
						//outgoing wr-wr and wr-rd edge
						vd.dcWrite.max(dc);
						if (!DISABLE_EVENT_GRAPH) vd.dcWrites.set(tid, dc.get(tid));
					} else {
						//outgoing rd-wr edge
						vd.dcReadsJoined.max(dc);
						if (!DISABLE_EVENT_GRAPH) vd.dcReads.set(tid, dc.get(tid)); 
					}
				}
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV wdc = ts_get_wdc(td);
					if (fae.isWrite()) {
						//outgoing wr-wr and wr-rd edge
						vd.wdcWrite.max(wdc);
						if (!DISABLE_EVENT_GRAPH) vd.wdcWrites.set(tid, wdc.get(tid));
					} else {
						//outgoing rd-wr edge
						vd.wdcReadsJoined.max(wdc);
						if (!DISABLE_EVENT_GRAPH) vd.wdcReads.set(tid, wdc.get(tid)); 
					}
				}
						
				// Increment for outgoing edge
				if (HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV hb = ts_get_hb(td);
					//outgoing wr-wr and wr-rd race (if fae.isWrite()) or outgoing rd-wr edge (if fae.isRead())
					hb.inc(tid);
				}
				if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
					final CV dc = ts_get_dc(td);
					//outgoing wr-wr and wr-rd race (if fae.isWrite()) or outgoing rd-wr edge (if fae.isRead())
					dc.inc(tid);
				}
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV wdc = ts_get_wdc(td);
					//outgoing wr-wr and wr-rd race (if fae.isWrite()) or outgoing rd-wr edge (if fae.isRead())
					wdc.inc(tid);
				}
				
				if (fae.isWrite()) {
					vd.lastWriteEvents[tid] = thisEventNode;
				} else {
					vd.lastReadEvents[tid] = thisEventNode;
				}
			}
		}
			
		super.volatileAccess(fae);
	}
	
	protected void error(final AccessEvent ae, final String relation, final String prevOp, final int prevTid, final DynamicSourceLocation prevDL, final String curOp, final int curTid) {
		// Don't bother printing error during execution. All race information is collected using StaticRace now.
		if (ae instanceof FieldAccessEvent) {
			fieldError((FieldAccessEvent) ae, relation, prevOp, prevTid, prevDL, curOp, curTid);
		} else {
			arrayError((ArrayAccessEvent) ae, relation, prevOp, prevTid, prevDL, curOp, curTid);
		}
	}
	
	protected void fieldError(final FieldAccessEvent fae, final String relation, final String prevOp, final int prevTid, final DynamicSourceLocation prevDL, final String curOp, final int curTid) {
		final FieldInfo fd = fae.getInfo().getField();
		final ShadowThread currentThread = fae.getThread();
		final Object target = fae.getTarget();
			
		if (VERBOSE) {
			fieldErrors.error(currentThread,
					fd,
					"Relation",						relation,
					"Guard State", 					fae.getOriginalShadow(),
					"Current Thread",				toString(currentThread), 
					"Class",						target==null?fd.getOwner():target.getClass(),
					"Field",						Util.objectToIdentityString(target) + "." + fd, 
					"Prev Op",						prevOp + prevTid,
					"Prev Loc",						prevDL == null ? "?" : prevDL.toString(),
					"Prev Event #",					prevDL == null ? "?" : prevDL.eventNode,
					"Cur Op",						curOp + curTid,
					"Current Event #",				ts_get_lastEventNode(currentThread)==null?"null":ts_get_lastEventNode(currentThread).eventNumber,
					"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
			);
		} else {
			fieldErrors.errorQuite(currentThread,
					fd,
					"Relation",						relation,
					"Current Thread",				toString(currentThread), 
					"Class",						target==null?fd.getOwner():target.getClass(),
					"Field",						Util.objectToIdentityString(target) + "." + fd, 
					"Prev Op",						prevOp + " " + ShadowThread.get(prevTid),
					"Cur Op",						curOp + " " + ShadowThread.get(curTid),
					"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
			);
		}
			
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
	}
	
	protected void arrayError(final ArrayAccessEvent aae, final String relation, final String prevOp, final int prevTid, final DynamicSourceLocation prevDL, final String curOp, final int curTid) {
		final ShadowThread currentThread = aae.getThread();
		final Object target = aae.getTarget();

		if (VERBOSE) {
			arrayErrors.error(currentThread,
					aae.getInfo(),
					"Relation",						relation,
					"Alloc Site", 					ArrayAllocSiteTracker.get(aae.getTarget()),
					"Guard State", 					aae.getOriginalShadow(),
					"Current Thread",				currentThread==null?"":toString(currentThread), 
					"Array",						Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]",
					"Prev Op",						ShadowThread.get(prevTid)==null?"":prevOp + prevTid + ("name = " + ShadowThread.get(prevTid).getThread().getName()),
					"Prev Loc",						prevDL == null ? "?" : prevDL.toString(),
					"Prev Event #",					prevDL == null ? "?" : prevDL.eventNode,
					"Cur Op",						ShadowThread.get(curTid)==null?"":curOp + curTid + ("name = " + ShadowThread.get(curTid).getThread().getName()), 
					"Current Event #",				ts_get_lastEventNode(currentThread)==null?"null":ts_get_lastEventNode(currentThread).eventNumber,
					"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
			);
		} else {
			arrayErrors.errorQuite(currentThread,
					aae.getInfo(),
					"Alloc Site", 					ArrayAllocSiteTracker.get(aae.getTarget()),
					"Current Thread",				toString(currentThread), 
					"Array",						Util.objectToIdentityString(target) + "[" + aae.getIndex() + "]",
					"Prev Op",						prevOp + " " + ShadowThread.get(prevTid),
					"Cur Op",						curOp + " " + ShadowThread.get(curTid),
					"Stack",						ShadowThread.stackDumpForErrorMessage(currentThread) 
			);
		}
		
		if (DEBUG) Assert.assertTrue(prevTid != curTid);
		aae.getArrayState().specialize();
	}

	@Override
	public void preStart(final StartEvent se) {
		final ShadowThread td = se.getThread();
		synchronized(td) {
			
			final int tid = td.getTid();
			
			if (COUNT_EVENT) fork.inc(tid);			

			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //preStart is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, tid, currentCriticalSection, "start");
			}
			handleEvent(se, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("preStart by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// The forked thread has not started yet, so there should be no need to lock
			final ShadowThread forked = se.getNewThread();
			
			// Handle race edges
			if (DC) {
				ts_set_eTd(td, ts_get_dc(td).get(tid));
			} else if (WDC) {
				ts_set_eTd(td, ts_get_wdc(td).get(tid));
			} else {
				ts_set_eTd(td, ts_get_hb(td).get(tid));
			}
			
			if (HB) {
				final CV hb = ts_get_hb(td);
				final CV forked_hb = ts_get_hb(forked);
				forked_hb.max(hb);
				hb.inc(tid);
			}
			if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				final CV hb = ts_get_hb(td);
				final CV forked_hb = ts_get_hb(forked);
				final CV forked_wcp = ts_get_wcp(forked);
				
				// Compute WCP before modifying HB
				forked_wcp.max(hb); // Use HB here because this is a hard WCP edge
				forked_hb.max(hb);
				hb.inc(tid);
			}
			if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
				final CV dc = ts_get_dc(td);
				final CV forked_dc = ts_get_dc(forked);
				forked_dc.max(dc);
				dc.inc(tid);
			}
			if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				final CV wdc = ts_get_wdc(td);
				final CV forked_wdc = ts_get_wdc(forked);
				forked_wdc.max(wdc);
				wdc.inc(tid);
			}
			//For generating event node graph
			if (!DISABLE_EVENT_GRAPH) {
				if (HB) {
					ts_set_hb(forked, new CVE(ts_get_hb(forked), thisEventNode));
				} else if (WCP) {
					ts_set_wcp(forked, new CVE(ts_get_wcp(forked), thisEventNode));
				} else if (DC || HB_WCP_DC) {
					ts_set_dc(forked, new CVE(ts_get_dc(forked), thisEventNode));
				} else if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					ts_set_wdc(forked, new CVE(ts_get_wdc(forked), thisEventNode));
				}
			}
		}

		super.preStart(se);
	}

	@Override
	public void postJoin(final JoinEvent je) {
		final ShadowThread td = je.getThread();
		synchronized(td) {
			final int tid = td.getTid();
			
			if (COUNT_EVENT) join.inc(tid);

			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //postJoin is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, tid, currentCriticalSection, "join");
			}
			handleEvent(je, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			//Thread is already joined so there should be no need to lock
			final ShadowThread joining = je.getJoiningThread();
			final int joining_tid = joining.getTid();

			// this test tells use whether the tid has been reused already or not.  Necessary
			// to still account for stopped thread, even if that thread's tid has been reused,
			// but good to know if this is happening alot...
			if (joining.getTid() == -1) {
				Yikes.yikes("Joined after tid got reused --- don't touch anything related to tid here!");
			}
			
			if (!DISABLE_EVENT_GRAPH) {
				EventNode priorNode = ts_get_lastEventNode(joining);
				EventNode.addEdge(priorNode, thisEventNode);
			}
			
			if (PRINT_EVENT) {
				Util.log("postJoin by T"+tid+" | joining T"+joining_tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			if (HB) {
				ts_get_hb(td).max(ts_get_hb(joining));
			}
			if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				ts_get_hb(td).max(ts_get_hb(joining));
				ts_get_wcp(td).max(ts_get_hb(joining)); // Use HB since this is a hard WCP edge
			}
			if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
				ts_get_dc(td).max(ts_get_dc(joining));
			}
			if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
				ts_get_wdc(td).max(ts_get_wdc(joining));
			}
		}

		super.postJoin(je);	
	}

	@Override
	public void preWait(WaitEvent we) {
		final ShadowThread td = we.getThread();
		synchronized (td) {
			if (COUNT_EVENT) preWait.inc(td.getTid());
			
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new AcqRelNode(-2, we.getLock(), td.getTid(), false, currentCriticalSection);
				updateCurrentCriticalSectionAtRelease(td, thisEventNode);
			}
			handleEvent(we, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));

			if (PRINT_EVENT) {
				Util.log("preWait by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// lock is already held
			handleRelease(td, we.getLock(), thisEventNode);
		}
		
		super.preWait(we);
	}

	@Override
	public void postWait(WaitEvent we) {
		final ShadowThread td = we.getThread();
		synchronized (td) {
			if (COUNT_EVENT) postWait.inc(td.getTid());
			
			AcqRelNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) {
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new AcqRelNode(-2, we.getLock(), td.getTid(), true, currentCriticalSection);
				updateCurrentCriticalSectionAtAcquire(td, thisEventNode);
			}
			handleEvent(we, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("postWait by T"+td.getTid()+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			// lock is already held
			// Considering wait--notify to be a hard WCP and WDC edge.
			// (If wait--notify is used properly, won't it already be a hard edge?)
			handleAcquire(td, we.getLock(), thisEventNode, true);
		}

		super.postWait(we);
	}

	public static String toString(final ShadowThread td) {
		return String.format("[tid=%-2d   hb=%s   wcp=%s   dc=%s	wdc=%s]", td.getTid(), ((HB || WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) ? ts_get_hb(td) : "N/A"), ((WCP || HB_WCP_DC || HB_WCP_WDC ||HB_WCP_DC_WDC) ? ts_get_wcp(td) : "N/A"), ((DC || HB_WCP_DC || HB_WCP_DC_WDC) ? ts_get_dc(td) : "N/A"), ((WDC || HB_WCP_WDC || HB_WCP_DC_WDC) ? ts_get_wdc(td) : "N/A"));
	}

	private final Decoration<ShadowThread, CV> cvForExit = 
		ShadowThread.makeDecoration("WDC:barrier", DecorationFactory.Type.MULTIPLE, new NullDefault<ShadowThread, CV>());

	public void preDoBarrier(BarrierEvent<WDCBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
		WDCBarrierState dcBE = be.getBarrier();
		ShadowThread currentThread = be.getThread();
		CV entering = dcBE.getEntering();
		entering.max(ts_get_hb(currentThread));
		cvForExit.set(currentThread, entering);
	}

	public void postDoBarrier(BarrierEvent<WDCBarrierState> be) {
		Assert.assertTrue(false); // Does this ever get triggered in our evaluated programs?
		WDCBarrierState dcBE = be.getBarrier();
		ShadowThread currentThread = be.getThread();
		CV old = cvForExit.get(currentThread);
		dcBE.reset(old);
		if (HB) {
			ts_get_hb(currentThread).max(old);
		}
		if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			ts_get_hb(currentThread).max(old);
			ts_get_wcp(currentThread).max(old); // Also update WCP since a barrier is basically an all-to-all hard WCP edge
		}
		if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
			ts_get_dc(currentThread).max(old); // Updating WDC to HB seems fine at a barrier (?)
		}
		if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
			ts_get_wdc(currentThread).max(old);
		}
	}

	@Override
	public void classInitialized(ClassInitializedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized (td) {
			final int tid = td.getTid();
			
			if (COUNT_EVENT) classInit.inc(tid);
			
			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //classInitialized is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, tid, currentCriticalSection, "initialize");
			}
			handleEvent(e, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classInitialized by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				// Handle race edges
				if (DC) {
					ts_set_eTd(td, ts_get_dc(td).get(tid));
				} else if (WDC) {
					ts_set_eTd(td, ts_get_wdc(td).get(tid));
				} else {
					ts_set_eTd(td, ts_get_hb(td).get(tid));
				}
				
				if (HB) {
					final CV hb = ts_get_hb(td);
					classInitTime.get(e.getRRClass()).hbWrite.max(hb);
					hb.inc(tid);
				}
				if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					classInitTime.get(e.getRRClass()).hbWrite.max(hb);
					classInitTime.get(e.getRRClass()).wcpWrite.max(wcp);
					hb.inc(tid);
				}
				if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
					final CV dc = ts_get_dc(td);
					classInitTime.get(e.getRRClass()).dcWrite.max(dc);
					dc.inc(tid);
				}
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV wdc = ts_get_wdc(td);
					classInitTime.get(e.getRRClass()).wdcWrite.max(wdc);
					wdc.inc(tid);
				}
				
				//For generating event node graph
				if (!DISABLE_EVENT_GRAPH) {
					//No need to add edges to an event graph HB and WCP since these relations are sound, only the eventNumber is needed.
					if (DC || HB_WCP_DC) {
						classInitTime.get(e.getRRClass()).dcWrite.setEventNode(thisEventNode);
					} else if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						classInitTime.get(e.getRRClass()).wdcWrite.setEventNode(thisEventNode);
					}
				}
			}
		}

		super.classInitialized(e);
	}
	
	@Override
	public void classAccessed(ClassAccessedEvent e) {
		final ShadowThread td = e.getThread();
		synchronized(td) {
			final int tid = td.getTid();
			
			if (COUNT_EVENT) classAccess.inc(tid);
			
			EventNode thisEventNode = null;
			if (!DISABLE_EVENT_GRAPH) { //classInitialized is handled the same as rd/wr accesses
				AcqRelNode currentCriticalSection = getCurrentCriticalSection(td);
				thisEventNode = new EventNode(-2, tid, currentCriticalSection, "class_accessed");
			}
			handleEvent(e, thisEventNode);
			if (DEBUG && !DISABLE_EVENT_GRAPH) Assert.assertTrue(thisEventNode.eventNumber > -2 || td.getThread().getName().equals("Finalizer"));
			
			if (PRINT_EVENT) {
				Util.log("classAccessed by T"+tid+(!DISABLE_EVENT_GRAPH ? ", event count:"+thisEventNode.eventNumber : ""));
			}
			
			synchronized(classInitTime) { //Not sure what we discussed for classInit, but FT synchronizes on it so I assume the program executing does not protect accesses to classInit.
				WDCVolatileData initTime = classInitTime.get(e.getRRClass());
				if (HB) {
					final CV hb = ts_get_hb(td);
					hb.max(initTime.hbWrite);
				}
				if (WCP || HB_WCP_DC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV hb = ts_get_hb(td);
					final CV wcp = ts_get_wcp(td);
					hb.max(initTime.hbWrite);
					wcp.max(initTime.hbWrite); // union with HB since this is effectively a hard WCP edge
				}
				if (DC || HB_WCP_DC || HB_WCP_DC_WDC) {
					final CV dc = ts_get_dc(td);
					dc.max(initTime.dcWrite);
				}
				if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
					final CV wdc = ts_get_wdc(td);
					wdc.max(initTime.wdcWrite);
				}
				//For generating event node graph	
				if (!DISABLE_EVENT_GRAPH) {
					//No need to add edges to an event graph HB and WCP since these relations are sound, only the eventNumber is needed.
					if (DC || HB_WCP_DC) {
						if (initTime.dcWrite.anyGt(ts_get_dc(td))) {
							EventNode.addEdge(initTime.dcWrite.eventNode, thisEventNode);
						}
					}
					if (WDC || HB_WCP_WDC || HB_WCP_DC_WDC) {
						if (initTime.wdcWrite.anyGt(ts_get_wdc(td))) {
							EventNode.addEdge(initTime.wdcWrite.eventNode, thisEventNode);
						}
					}
				}
			}
		}
	}

	@Override
	public void printXML(XMLWriter xml) {
		for (ShadowThread td : ShadowThread.getThreads()) {
			xml.print("thread", toString(td));
		}
	}

	public File storeReorderedTraces() {
		File commandDir = null;
		if (RR.wdcbPrintReordering.get()) {
			File traceDir = new File("WDC_Traces");
			if (!traceDir.exists()) {
				traceDir.mkdir();
			}
			int version = 1;
			String commandDirName = traceDir + "/" + CommandLine.javaArgs.get().trim();
			commandDir = new File(commandDirName + "_" + version);
			while (commandDir.exists()) {
				version++;
				commandDir = new File(commandDirName + "_" + version);
			}
			commandDir.mkdir();
		}
		return commandDir;
	}
}
