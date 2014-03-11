package com.midisheetmusicmemo;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONException;

import com.midisheetmusicmemo.R;

import android.content.Context;
import android.graphics.drawable.NinePatchDrawable;

public class TommyConfig {
	public static int BACKGROUND_COLOR = 0xFFFFFFFF;
	public static MyStyle def_style;
	
	public static void init(Context ctx) {
		if(def_style == null) {
			def_style = new MyStyle();
			def_style.tile_bk_lower = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_lower));
			def_style.tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper));
			def_style.background1   = def_style.tile_bk_upper;
		}
	}
	
	public static MyStyle getCurrentStyle() {
		return def_style;
	}
	
	
	// High score and timestamp are interleaved.

	public static void populateHSTSArraysFromJSONString(ArrayList<Long> hs_array, ArrayList<Long> ts_array, String sz) {
		hs_array.clear();
		try {
			JSONArray ja = new JSONArray(sz);
			int i=0;
			while(i < ja.length()) {
				hs_array.add(ja.getLong(i));
				ts_array.add(ja.getLong(i));
			}
		} catch(JSONException e) {
			e.printStackTrace();
		}
	}
	
	public static String HSTSArraysToJSONString(ArrayList<Long> hs_array, ArrayList<Long> ts_array) {
		JSONArray ja = new JSONArray();
		for(int i=0; i<hs_array.size(); i++) {
			ja.put(hs_array.get(i));
			ja.put(ts_array.get(i));
		}
		return ja.toString();
	}
}
