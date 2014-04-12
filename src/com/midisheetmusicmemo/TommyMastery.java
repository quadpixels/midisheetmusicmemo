package com.midisheetmusicmemo;

import java.util.LinkedList;

// Computes Mastery.
public class TommyMastery {

	// Mastery Transition Table
	//                  Right                 Right            Right
	//  [Initial, 0] ----------> [State 1]  -------> [State 2] -------> [State 3 (Mastered)]
	//      |                       |                    |
	//      | Wrong                 | Wrong              | Wrong
	//      |                       |                    |
	//      V       Right           V       Right        V     Right              Right
	//  [State 4 ] ------------> [State 5] --------> [State 6] -------> [State 7] -------> [State 8] -------> [State 9(Mastered)]
	//    ^   |                    ^  |                 ^  |              /                  /
	//    |   |                    |  |                 |  |             /                  /
	//    \___/                    \__/                 \__/  <---------/  <---------------/
	//     Wrong                   Wrong                Wrong      Wrong             Wrong
	//
	public static float MASTERY_STATE_SCORES[] = {0.0f, 0.33f, 0.66f, 1.00f, 0.00f, 0.20f, 0.40f, 0.60f, 0.80f, 1.00f};
	// State transition table
	public static int MASTERY_NEXT_STATE_RIGHT[] = {1, 2, 3, 3, 5, 6, 7, 8, 9, 9};
	public static int MASTERY_NEXT_STATE_WRONG[] = {4, 5, 6, 3, 4, 5, 6, 6, 6, 9};
	
	// Number of consecutive correct answers needed to get to next level
	private int mastery_state = 0;
	private LinkedList<Boolean> history;
	TommyMastery(int _mastery_level) {
		mastery_state = _mastery_level;
		history = new LinkedList<Boolean>();
	}
	public void appendOutcome(boolean outcome) {
		history.addFirst(outcome);
		
		if(outcome == true) {
			mastery_state = MASTERY_NEXT_STATE_RIGHT[mastery_state];
		} else {
			mastery_state = MASTERY_NEXT_STATE_WRONG[mastery_state];
		}
	}
	public void free() {
		history.clear();
	}
	float getMasteryLevel() { 
		return MASTERY_STATE_SCORES[mastery_state]; 
	}
	int getMasteryState() { return mastery_state; }
}
