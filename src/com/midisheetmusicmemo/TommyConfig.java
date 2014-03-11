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
	public final static float[] BLANK_RATIOS = {0.25f, 0.5f, 0.75f, 1.0f};
	public final static int[]   CURVE_COLORS = {0xFF0033FF, 0xFF00FF00, 0xFFFFFF00, 0xFFFF0000};
	
	public static void init(Context ctx) {
		if(def_style == null) {
			def_style = new MyStyle();
			def_style.tile_bk_lower = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_lower));
			def_style.tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper));
			def_style.background1   = def_style.tile_bk_upper;
			def_style.btn_text_color = 0xFF000000;
		}
	}
	
	public static MyStyle getCurrentStyle() {
		return def_style;
	}
	
	
	// High score and timestamp are interleaved.

	public static void populateHSTSArraysFromJSONString(ArrayList<ArrayList<Long>> hs_array,
			ArrayList<ArrayList<Long>> ts_array,
			ArrayList<ArrayList<Integer>> right_clicks,
			ArrayList<ArrayList<Integer>> wrong_clicks,
			String sz) {
		int M = hs_array.size();
		String[] sep = sz.split(":");
		assert(sep.length == M);
		for(int i=0; i<M; i++) { 
			hs_array.get(i).clear(); 
			ts_array.get(i).clear();
			right_clicks.get(i).clear();
			wrong_clicks.get(i).clear();
		}
		try {
			for(int i=0; i<M; i++) {
				JSONArray ja = new JSONArray(sep[i]);
				int j = 0;
				while(j < ja.length()) {
					hs_array.get(i).add(ja.getLong(j)); j++;
					ts_array.get(i).add(ja.getLong(j)); j++;
					right_clicks.get(i).add(ja.getInt(j)); j++;
					wrong_clicks.get(i).add(ja.getInt(j)); j++;
				}
			}
		} catch(JSONException e) {
			e.printStackTrace();
		}
	}
	
	public static String HSTSArraysToJSONString(ArrayList<ArrayList<Long>> hs_array,
			ArrayList<ArrayList<Long>> ts_array,
			ArrayList<ArrayList<Integer>> right_clicks,
			ArrayList<ArrayList<Integer>> wrong_clicks) {
		int M = hs_array.size(); // M configurations, each having an ArrayList<Long>.
		StringBuilder sb = new StringBuilder();
		for(int j=0; j<M; j++) {
			JSONArray ja = new JSONArray();
			for(int i=0; i<hs_array.get(j).size(); i++) {
				ja.put(hs_array.get(j).get(i));
				ja.put(ts_array.get(j).get(i));
				ja.put(right_clicks.get(j).get(i));
				ja.put(wrong_clicks.get(j).get(i));
			}
			sb.append(ja.toString());
			if(j < M-1) sb.append(":");
		}	
		return sb.toString();
	}
}
