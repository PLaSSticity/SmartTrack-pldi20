// AUTO-GENERATED --- DO NOT EDIT DIRECTLY 
/******************************************************************************

Copyright (c) 2016, Cormac Flanagan (University of California, Santa Cruz)
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

package tools.util;

import acme.util.Assert;
import acme.util.Util;
import rr.state.ShadowThread;
import rr.tool.RR;

public final class LongEpoch {

	/*
	 * We use a variable number of bits for ids, based on the maxTid configured
	 * on the command line.
	 */
	public static final int TID_BITS = Long/*epoch*/.SIZE - Long/*epoch*/.numberOfLeadingZeros(RR.maxTidOption.get());

	static {
		Assert.assertTrue(TID_BITS > 0 && TID_BITS <= 16, 
				"LongEpochs can only have 1-16 bits for tids, not " + TID_BITS + " --- check 0 < maxTid <= 2^16 ");
		Util.logf("LongEpoch will use %d bits for tids", TID_BITS);
	}

	public static final int CLOCK_BITS = Long/*epoch*/.SIZE - TID_BITS;
	public static final long/*epoch*/ MAX_CLOCK = ( ((long/*epoch*/)1) << CLOCK_BITS) - 1;
	public static final int MAX_TID = (1 << TID_BITS) - 1;

	public static final long/*epoch*/ ZERO = 0;
	public static final long/*epoch*/ READ_SHARED = -1;

	public static int tid(long/*epoch*/ epoch) {
		return (int)(epoch >>> CLOCK_BITS);
	}

	public static long/*epoch*/ clock(long/*epoch*/ epoch) {
		return epoch & MAX_CLOCK;
	}

	// clock should not have a tid -- it should be a raw clock value.
	public static long/*epoch*/ make(int tid, long/*epoch*/ clock) {
		return (((long/*epoch*/)tid) << CLOCK_BITS) | clock;
	}


	public static long/*epoch*/ make(ShadowThread td, long/*epoch*/ clock) {
		return make(td.getTid(), clock);
	}

	public static long/*epoch*/ tick(long/*epoch*/ epoch) {
		Assert.assertTrue(clock(epoch) <= MAX_CLOCK - 1, "LongEpoch clock overflow");
		return epoch + 1;
	}
	
	public static long/*epoch*/ tick(long/*epoch*/ epoch, int amount) {
		Assert.assertTrue(clock(epoch) <= MAX_CLOCK - amount, "LongEpoch clock overflow");
		return epoch + amount;
	}
	
	
	public static boolean leq(long/*epoch*/ e1, long/*epoch*/ e2) {
		// Assert.assertTrue(tid(e1) == tid(e2));
		return clock(e1) <= clock(e2);
	}

	public static long/*epoch*/ max(long/*epoch*/ e1, long/*epoch*/ e2) {
		// Assert.assertTrue(tid(e1) == tid(e2));
		return LongEpoch.make(tid(e1), Math.max(clock(e1), clock(e2)));
	}

	public static String toString(long/*epoch*/ epoch) {
		if (epoch == READ_SHARED) {
			return "SHARED";
		} else {
			return String.format("(%d:%X)", tid(epoch), clock(epoch));
		}
	}

	public static void main(String args[]) {
		{
			long/*epoch*/ e = make(3,11);
			System.out.println(toString(e));
		}
		{
			long/*epoch*/ e = make(MAX_TID-1,11);
			System.out.println(toString(e));
			System.out.println(toString(tick(e)));
		}
		{
			long/*epoch*/ e = make(MAX_TID+1,11);
			System.out.println(toString(e));
		}
	}

}
