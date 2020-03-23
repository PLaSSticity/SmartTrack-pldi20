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

import rr.state.ShadowVolatile;
import rr.tool.RR;

public class WDCVolatileData {

	public final ShadowVolatile peer;
	
	//HB
	public CV hbWrite;
	public CV hbReadsJoined;
	
	//WCP
	public CV wcpWrite;
	public CV wcpReadsJoined;
	
	//DC
	public CVE dcWrite;
	public CV dcReadsJoined;
	
	//WDC
	public CVE wdcWrite;
	public CV wdcReadsJoined;
	
	//For constraint graph
	public CV dcWrites;
	public CV dcReads;
	
	public CV wdcWrites;
	public CV wdcReads;

	public EventNode[] lastWriteEvents;
	public EventNode[] lastReadEvents;
	
	public WDCVolatileData(ShadowVolatile ld) {
		this.peer = ld;
		if (WDCTool.HB || WDCTool.WCP || WDCTool.HB_WCP_DC || WDCTool.HB_WCP_WDC || WDCTool.HB_WCP_DC_WDC) {
			this.hbWrite = new CV(WDCTool.INIT_CV_SIZE);
			this.hbReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.WCP || WDCTool.HB_WCP_DC || WDCTool.HB_WCP_WDC || WDCTool.HB_WCP_DC_WDC) {
			this.wcpWrite = new CV(WDCTool.INIT_CV_SIZE);
			this.wcpReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.DC || WDCTool.HB_WCP_DC || WDCTool.HB_WCP_DC_WDC) { 
			this.dcWrite = new CVE(new CV(WDCTool.INIT_CV_SIZE), null);
			this.dcReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
		
			this.dcWrites = new CV(WDCTool.INIT_CV_SIZE);
			this.dcReads = new CV(WDCTool.INIT_CV_SIZE);
		}
		if (WDCTool.WDC || WDCTool.HB_WCP_WDC || WDCTool.HB_WCP_DC_WDC) {
			this.wdcWrite = new CVE(new CV(WDCTool.INIT_CV_SIZE), null);
			this.wdcReadsJoined = new CV(WDCTool.INIT_CV_SIZE);
			
			this.wdcWrites = new CV(WDCTool.INIT_CV_SIZE);
			this.wdcReads = new CV(WDCTool.INIT_CV_SIZE);
		}

		this.lastWriteEvents = new EventNode[RR.maxTidOption.get()];
		this.lastReadEvents = new EventNode[RR.maxTidOption.get()];
	}

	@Override
	public String toString() {
		return String.format("[HB=%s] [WCP=%s] [DC=%s] [WDC=%s]", hbWrite, wcpWrite, dcWrite, wdcWrite); 
	}

	
}
