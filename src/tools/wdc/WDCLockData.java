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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.WeakHashMap;

import acme.util.identityhash.WeakIdentityHashMap;
import acme.util.identityhash.WeakIdentityHashSet;
import rr.state.ShadowLock;
import rr.state.ShadowThread;
import rr.state.ShadowVar;

public class WDCLockData {

	public final ShadowLock peer;
	public final CV hb;
	public final CV wcp;
	public final CV dc;
	public final CV wdc;
	
	public HashSet<ShadowVar> readVars; // variables read during critical section
	public HashSet<ShadowVar> writeVars; // variables written during critical section
	
	public final HashMap<ShadowThread, CVE> doPerThreadLockMap;
	public WeakIdentityHashMap<ShadowVar, LinkedList<ShadowThread>> latestReadVars;
	public WeakIdentityHashMap<ShadowVar, ShadowThread> latestWriteVars;
	
	public EventNode latestRelNode;
	
	//WCP
	public WeakIdentityHashMap<ShadowVar,CV> wcpReadMap;
	public WeakIdentityHashMap<ShadowVar,CV> wcpWriteMap;
	public final HashMap<ShadowThread,ArrayDeque<CV>> wcpAcqQueueMap;
	public final HashMap<ShadowThread,ArrayDeque<CV>> wcpRelQueueMap;
	public final ArrayDeque<CV> wcpAcqQueueGlobal;
	public final ArrayDeque<CV> wcpRelQueueGlobal;
	
	//DC
	public WeakIdentityHashMap<ShadowVar,CVE> dcReadMap;
	public WeakIdentityHashMap<ShadowVar,CVE> dcWriteMap;
	public final HashMap<ShadowThread,PerThreadQueue<CVE>> dcAcqQueueMap;
	public final HashMap<ShadowThread,PerThreadQueue<CVE>> dcRelQueueMap;
	public final PerThreadQueue<CVE> dcAcqQueueGlobal;
	public final PerThreadQueue<CVE> dcRelQueueGlobal;
	
	//WDC
	public WeakIdentityHashMap<ShadowVar,CVE> wdcReadMap;
	public WeakIdentityHashMap<ShadowVar,CVE> wdcWriteMap;

	public WDCLockData(ShadowLock ld) {
		this.peer = ld;
		this.hb = new CV(WDCTool.INIT_CV_SIZE);
		this.wcp = new CV(WDCTool.INIT_CV_SIZE);
		this.dc = new CV(WDCTool.INIT_CV_SIZE);
		this.wdc = new CV(WDCTool.INIT_CV_SIZE);

		this.readVars = new HashSet<ShadowVar>();
		this.writeVars = new HashSet<ShadowVar>();
		
		this.doPerThreadLockMap = new HashMap<ShadowThread, CVE>();
		this.latestReadVars = new WeakIdentityHashMap<ShadowVar, LinkedList<ShadowThread>>();
		this.latestWriteVars = new WeakIdentityHashMap<ShadowVar, ShadowThread>();
		
		latestRelNode = null;

		//WCP
		this.wcpReadMap = new WeakIdentityHashMap<ShadowVar,CV>();
		this.wcpWriteMap = new WeakIdentityHashMap<ShadowVar,CV>();
		this.wcpAcqQueueMap = new HashMap<ShadowThread,ArrayDeque<CV>>();
		this.wcpRelQueueMap = new HashMap<ShadowThread,ArrayDeque<CV>>();
		this.wcpAcqQueueGlobal = new ArrayDeque<CV>();
		this.wcpRelQueueGlobal = new ArrayDeque<CV>();
		
		//DC
		this.dcReadMap = new WeakIdentityHashMap<ShadowVar,CVE>();
		this.dcWriteMap = new WeakIdentityHashMap<ShadowVar,CVE>();
		this.dcAcqQueueMap = new HashMap<ShadowThread,PerThreadQueue<CVE>>();
		this.dcRelQueueMap = new HashMap<ShadowThread,PerThreadQueue<CVE>>();
		this.dcAcqQueueGlobal = new PerThreadQueue<CVE>();
		this.dcRelQueueGlobal = new PerThreadQueue<CVE>();
		
		//WDC
		this.wdcReadMap = new WeakIdentityHashMap<ShadowVar,CVE>();
		this.wdcWriteMap = new WeakIdentityHashMap<ShadowVar,CVE>();
	}

	@Override
	public String toString() {
		return String.format("[HB=%s] [WCP=%s] [DC=%s] [WDC=%s]", hb, wcp, dc, wdc);
	}
	
}
