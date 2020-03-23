package tools.pip;

import java.util.HashMap;

import acme.util.Util;
import rr.meta.MethodInfo;
import rr.meta.SourceLocation;

class StaticRace {
	SourceLocation location; // Might contain just one location, which means the location races with itself
	final MethodInfo secondNodeMI;
	
	StaticRace(SourceLocation secondSite) {
		location = secondSite;
		// TODO: add method information?
		this.secondNodeMI = null;
	}

	@Override
	public boolean equals(Object o) {
		return this.location.equals(((StaticRace)o).location);
	}
	
	@Override
	public int hashCode() {
		return location.hashCode();
	}
	
	static HashMap<StaticRace,Integer> staticRaceMap = new HashMap<StaticRace,Integer>();

	static synchronized void addRace(StaticRace staticRace) {
		Integer count = staticRaceMap.get(staticRace);
		if (count == null) {
			staticRaceMap.put(staticRace, 1);
		} else {
			staticRaceMap.put(staticRace, count.intValue() + 1);
		}
	}
	
	static synchronized void reportRaces() {
		Util.println(staticRaceMap.size() + " statically unique race(s)");
		long dynamicCount = 0;
		for (StaticRace staticRace : staticRaceMap.keySet()) {
			dynamicCount += staticRaceMap.get(staticRace);
		}
		Util.println(dynamicCount + " dynamic race(s)");
	}
}
