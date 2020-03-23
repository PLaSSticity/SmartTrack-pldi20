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

import java.io.Serializable;

import acme.util.Assert;
import rr.tool.RR;

/**
 * LongVectorClock are mutable, extensible functions from ShadowThread 
 * ids to epochs.
 * 
 * The client is responsible for providing synchronization.
 */
public class LongVectorClock implements Serializable {
	private static final int FAST = 8;

	protected volatile long/*epoch*/[] values;

	// use for all VCs that start with an empty array
	private static final long/*epoch*/[] EMPTY = new long/*epoch*/[0];

	protected LongVectorClock() { 
		values = EMPTY;
	}

	public LongVectorClock(LongVectorClock other) {
		this(other.size());
		copy(other);
	}
	
	// requires: size >= 0
	public LongVectorClock(int size) {
		if (size > 0) {
			makeCV(size);
		} else {
			values = EMPTY;
		}
	}

	// requires size >= 0
	// requires exclusive access to this
	public void makeCV(int size) {
		values = new long/*epoch*/[size];
		clearFrom(values, 0);
	}

	// requires: exclusive access to this.
	private static void clearFrom(long/*epoch*/[] values, int pos) {
		for (int i = pos; i < values.length; i++) {
			values[i] = LongEpoch.make(i, 0);
		}
	}

	// requires: exclusive access to this and src
	final public void copy(LongVectorClock src) {
		long/*epoch*/[] srcValues = src.values;
		ensureCapacity(srcValues.length);
		long/*epoch*/[] dstValues = this.values;

		// dstValues.length == n, srcValues.length == m
		// n >= m
		switch (srcValues.length) {
		default: slowCopy(src); // copy 8..m
		case 8: dstValues[7]=srcValues[7];
		case 7: dstValues[6]=srcValues[6];
		case 6: dstValues[5]=srcValues[5];
		case 5: dstValues[4]=srcValues[4];
		case 4: dstValues[3]=srcValues[3];
		case 3: dstValues[2]=srcValues[2];
		case 2: dstValues[1]=srcValues[1];
		case 1: dstValues[0]=srcValues[0];
		case 0:  
		}

		// handle m..n-1
		for (int i = srcValues.length; i < dstValues.length; i++) {
			dstValues[i] = LongEpoch.make(i,0);
		}
	}

	// requires: exclusive access to this, src
	// requires this.values.length >= src.values.length 
	final private void slowCopy(LongVectorClock src) {
		long/*epoch*/[] srcValues = src.values;
		long/*epoch*/[] thisValues = this.values;
		for (int i = FAST; i < thisValues.length; i++) {
			thisValues[i] = srcValues[i];
		}
	}

	// requires: exclusive access to this
	final private void ensureCapacity(int len) {
		int curLength = values.length;
		if (curLength < len) {
			long/*epoch*/[] b = new long/*epoch*/[len];
			for(int i = 0; i < curLength; i++) {
				b[i] = values[i];
			}
			clearFrom(b, curLength);
			values = b;
		}
	}

	// requires: exclusive access to this and other
	final public void max(LongVectorClock other) {
		long/*epoch*/[] otherValues = other.values;
		ensureCapacity(otherValues.length);
		long/*epoch*/[] thisValues = this.values;

		// thisValues.length >= otherValues.length
		// otherValues.length..thisValues.length-1: stays the same.
		switch (otherValues.length) {
		default: slowMax(other); // max 8..otherValues
		case 8: if (LongEpoch.leq(thisValues[7], otherValues[7])) thisValues[7] = otherValues[7];
		case 7: if (LongEpoch.leq(thisValues[6], otherValues[6])) thisValues[6] = otherValues[6];
		case 6: if (LongEpoch.leq(thisValues[5], otherValues[5])) thisValues[5] = otherValues[5];
		case 5: if (LongEpoch.leq(thisValues[4], otherValues[4])) thisValues[4] = otherValues[4];
		case 4: if (LongEpoch.leq(thisValues[3], otherValues[3])) thisValues[3] = otherValues[3];
		case 3: if (LongEpoch.leq(thisValues[2], otherValues[2])) thisValues[2] = otherValues[2];
		case 2: if (LongEpoch.leq(thisValues[1], otherValues[1])) thisValues[1] = otherValues[1];
		case 1: if (LongEpoch.leq(thisValues[0], otherValues[0])) thisValues[0] = otherValues[0];
		case 0:  
		}

	}

	// requires: exclusive access to this, other
	// srcValues.length <= dstValues.length
	final private void slowMax(LongVectorClock src) {
		long/*epoch*/[] srcValues = src.values;
		long/*epoch*/[] dstValues = this.values;
		for (int i = FAST; i < srcValues.length; i++) {
			if (LongEpoch.leq(dstValues[i], srcValues[i])) dstValues[i] = srcValues[i];
		}
	}

	/* Return false if all entries in this.values are <= other.values. */
	// requires: exclusive access to this and other
	final public boolean leq(LongVectorClock other) {
		return !anyGt(other);
	}

	/* Return true if any entry in this.values is greater than in other.values. */
	// requires: exclusive access to this and other
	final public boolean anyGt(LongVectorClock other) {
		//	other.ensureCapacity(this.values.length);
		long/*epoch*/[] thisValues = this.values;
		long/*epoch*/[] otherValues = other.values;

		int thisLen = thisValues.length;
		int otherLen = otherValues.length;
		int min = Math.min(thisLen, otherLen);
		switch (min) {  
		default: if (slowAnyGt(thisValues, otherValues, min)) return true; // handle 8..min
		case 8:  if (!LongEpoch.leq(thisValues[7], otherValues[7])) return true;
		case 7:  if (!LongEpoch.leq(thisValues[6], otherValues[6])) return true;
		case 6:  if (!LongEpoch.leq(thisValues[5], otherValues[5])) return true;
		case 5:  if (!LongEpoch.leq(thisValues[4], otherValues[4])) return true;
		case 4:  if (!LongEpoch.leq(thisValues[3], otherValues[3])) return true;
		case 3:  if (!LongEpoch.leq(thisValues[2], otherValues[2])) return true;
		case 2:  if (!LongEpoch.leq(thisValues[1], otherValues[1])) return true;
		case 1:  if (!LongEpoch.leq(thisValues[0], otherValues[0])) return true;
		case 0:
		}

		// handle min..thisLen 
		for (int i = min; i < thisLen; i++) {
			if (thisValues[i] != LongEpoch.make(i, 0)) return true;
		}

		// handle thisLen..otherLen
		// our values are t@0 -> so never greater than other values.

		return false;
	}

	/* 
	 * Return true if any entry in ca1 is greater than in c2a. 
	 * requires: min <= ca1.length, min <= ca2.length 
	 * requires: exclusive access to ca1 and ca2
	 */
	final private static boolean slowAnyGt(long/*epoch*/[] ca1, long/*epoch*/[] ca2, int len) {
		for (int i = FAST; i < len; i++) { 
			if (!LongEpoch.leq(ca1[i], ca2[i])) return true;
		}
		return false; 
	}

	/*
	 * Returns next index i >= start such that this.a[i] > other.a[i],
	 * or -1 if no such. 
	 * requires: exclusive access to this and other
	 */
	final public int nextGt(LongVectorClock other, int start) {
		//other.ensureCapacity(this.values.length);

		final long/*epoch*/[] thisValues = this.values;
		final int thisLen = thisValues.length;

		if (start >= thisLen) {
			// this.values has no non-0 epochs left.
			return -1;
		}

		final long/*epoch*/[] otherValues = other.values;
		final int otherLen = otherValues.length;

		final int min = Math.min(thisLen, otherLen);

		int i = start;
		
		// handle start..min
		for (; i < min; i++) {
			if (!LongEpoch.leq(thisValues[i], otherValues[i])) {
				return i;
			}
		}

		// handle i..thisLen
		for (; i < thisLen; i++) {
			if (thisValues[i] != LongEpoch.make(i, 0)) {
				return i;
			}
		}		

		// handle thisLen..otherLen
		// our values are t@0 -> so never greater than other values.

		return -1;
	}

	// requires: exclusive access to this
	final public void tick(int tid) {
		ensureCapacity(tid + 1);
		values[tid] = LongEpoch.tick(values[tid]);
	}

	// requires: exclusive access to this
	final public void set(int tid, long/*epoch*/ v) {
		Assert.assertTrue(tid == LongEpoch.tid(v));
		ensureCapacity(tid + 1);
		values[tid] = v;
	}

	// requires: exclusive access to this
	@Override
	public String toString() {
		StringBuilder r = new StringBuilder();
		r.append("[");
		for (int i = 0; i < values.length; i++) {
			r.append((i > 0 ? " " : "") + LongEpoch.toString(values[i]));
		}
		return r.append("]").toString();
	}

	// Requires: exclusive access to this, or
	//   that only current thread can write to values[tid]. 
	final public long/*epoch*/ get(final int tid) {
		final long/*epoch*/[] myValues = values;
		if (tid < myValues.length) {
			return myValues[tid];
		} else {
			return LongEpoch.make(tid, 0);
		}
	}

	// requires: exclusive access to this
	final public int size() {
		return values.length;
	}

	public static void main(String args[]) {
		LongVectorClock v1 = new LongVectorClock(4);
		LongVectorClock v2 = new LongVectorClock(8);
		v1.tick(3);
		v1.tick(5);
		v2.tick(1);
		v2.tick(3);
		v2.tick(5);
		v2.tick(7);
		System.out.println("v1 =" + v1);
		System.out.println("v2 =" + v2);
		System.out.println("v1 <= v1 => " + !v1.anyGt(v1));
		System.out.println("v1 <= v2 => " + !v1.anyGt(v2));
		System.out.println("v2 <= v1 => " + !v2.anyGt(v1));

		int start = -1;
		while (true) {
			start = v2.nextGt(v1, start + 1);
			if (start == -1) break;
			System.out.print(start + " " );
		}
		System.out.println();

		{
			LongVectorClock v3 = new LongVectorClock(0);
			v3.max(v2);
			System.out.println("v3 =" + v3);
		}

		{
			LongVectorClock v3 = new LongVectorClock(12);
			v3.copy(v1);
			v3.tick(3);
			v3.tick(10);
			v3.max(v2);
			System.out.println("v3 =" + v3);
		}

		{
			LongVectorClock v3 = new LongVectorClock(12);
			v3.copy(v1);
			v3.tick(3);
			v3.tick(10);
			v3.copy(v2);
			System.out.println("v3 =" + v3);
		}




	}

}

