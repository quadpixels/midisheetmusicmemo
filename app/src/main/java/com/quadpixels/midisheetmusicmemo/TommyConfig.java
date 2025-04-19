package com.quadpixels.midisheetmusicmemo;

import java.util.ArrayList;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONException;

import com.quadpixels.midisheetmusicmemo.R;
import com.quadpixels.midisheetmusicmemo.R.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.NinePatchDrawable;
import android.util.Log;

public class TommyConfig {
	public static final int FINEGRAINED_HISTORY_LENGTH = 5; // History length 5: Right/Wrong at first attempt & Time taken until correct answer
	
	
	public static int BACKGROUND_COLOR = 0xFFFFFFFF;
	public static final String TOMMY_PREF_NAME = "SheetMusicFlashcardPrefs";
	public static final String IS_FROM_TOMMY_ACTIVITY = "IsFromTommyActivity";
	public static final String FILE_URI_ID = "MidiFileURI";
	public static final String FILE_CHECKSUM_ID = "MidiFileChecksum";
	private static final int NUM_STYLES = 5;
	public static MyStyle styles[] = new MyStyle[NUM_STYLES];
	private static int style_idx = 0; 
	public final static float[] BLANK_RATIOS = {0.25f, 0.5f, 0.75f, 1.0f};
	public final static int[]   CURVE_COLORS = {0xFF0033FF, 0xFF00FF00, 0xFFFFFF00, 0xFFFF0000};
	
	public static String[] GAUGE_LABELS;
	
	public static Bitmap bmp_settings, bmp_palette, bmp_dropshadow32, bmp_dropshadow32h,
		bmp_dropshadow32vertex;
	
	// Some day, I'd want to move them into an X.M.L. file
	public static void init(Context ctx) {
		if(styles[0] == null) {
			styles[0] = new MyStyle();
			styles[0].tile_bk_lower = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper));
			styles[0].tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper));
			styles[0].background_separator = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.background_separator));
			styles[0].btn_text_color = 0xFFFFFFFF;
			styles[0].text_color     = 0xFF000000;
			styles[0].highlight_color= 0xFFFFFF33;
			styles[0].background_color = 0xFF2C2C2C;
			styles[0].gauge_color_neutral = 0xFF6666FF;
			styles[0].gauge_color_undesirable = 0xFFFF0000;
			styles[0].gauge_color_desirable = 0xFF00FF00;
			
			styles[1] = new MyStyle();
			styles[1].tile_bk_lower = styles[1].tile_bk_upper =(NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper_bamboo));
			styles[1].background_separator = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.background_separator_bamboo));
			styles[1].btn_text_color = 0xFFFFFFFF;
			styles[1].text_color = 0xFF333333;
			styles[1].highlight_color = 0xFF136613;
			styles[1].background_color = 0xFF3f4d35;
			styles[1].gauge_color_neutral = 0xFF136613;
			styles[1].gauge_color_undesirable = 0xFFFF0000;
			styles[1].gauge_color_desirable = 0xFF00FF00;
			
			styles[2] = new MyStyle();
			styles[2].tile_bk_lower = styles[2].tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper_carmine));
			styles[2].background_separator = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.background_separator_carmine));
			styles[2].btn_text_color = 0xFFFFFFFF;
			styles[2].text_color = 0xFF663366;
			styles[2].highlight_color = 0xFFFFFF33;
			styles[2].background_color = 0xFF52151a;
			styles[2].gauge_color_neutral = 0xFFFFFF33;
			styles[2].gauge_color_undesirable = 0xFFFF0000;
			styles[2].gauge_color_desirable = 0xFF00FF00;
			
			styles[3] = new MyStyle();
			styles[3].tile_bk_lower = styles[3].tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper_horizonblue));
			styles[3].background_separator = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.background_separator_horizonblue));
			styles[3].btn_text_color = 0xFFFFFFFF;
			styles[3].text_color = 0xFF663366;
			styles[3].highlight_color = 0xFF33FFFF;
			styles[3].background_color = 0xFF2e1d1d;
			styles[3].gauge_color_neutral = 0xFF333333;
			styles[3].gauge_color_undesirable = 0xFFFF0000;
			styles[3].gauge_color_desirable = 0xFF00FF00;
			
			styles[4] = new MyStyle();
			styles[4].tile_bk_lower = styles[4].tile_bk_upper = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.ninepatch2_upper_coffee));
			styles[4].background_separator = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.background_separator_coffee));
			styles[4].btn_text_color = 0xFFFFFFFF;
			styles[4].text_color = 0xFF663366;
			styles[4].highlight_color = 0xFFEEFFFF;
			styles[4].background_color = 0xFF844531;
			styles[4].gauge_color_neutral = 0xFF333333;
			styles[4].gauge_color_undesirable = 0xFFFF0000;
			styles[4].gauge_color_desirable = 0xFF00FF00;
		}
		if(bmp_settings == null) {
			bmp_settings = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.settings);
			bmp_palette = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.palette);
			bmp_dropshadow32 = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.shadow32);
			bmp_dropshadow32h = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.shadow32h);
			bmp_dropshadow32vertex = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.shadow32vertex);
		}
		if(GAUGE_LABELS == null) {
			GAUGE_LABELS = ctx.getResources().getStringArray(R.array.gauge_names);
		}
	}
	
	public static void cycleStyle() {
		style_idx ++;
		if(style_idx >= styles.length) style_idx = 0;
	}
	
	public static void setStyleIdx(int idx) {
		if(idx < 0) idx = 0;
		if(idx >= styles.length) idx = styles.length-1;
		style_idx = idx;
	}
	
	public static MyStyle getCurrentStyle() {
		return styles[style_idx];
	}
	
	public static MyStyle getStyleByIdx(int idx) {
		return styles[idx];
	}
	
	// High score and timestamp are interleaved.

	public static void populateHSTSArraysFromJSONString(ArrayList<ArrayList<Long>> hs_array,
			ArrayList<ArrayList<Long>> ts_array,
			ArrayList<ArrayList<Integer>> right_clicks,
			ArrayList<ArrayList<Integer>> wrong_clicks,
			String sz) {
		int M = hs_array.size();
		String[] sep = sz.split(":");
		assert(sep.length == M || sep.length == 0);
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
	
	public static boolean populateMasteryStateArrayFromJSONString(
			int num_actual_tracks, int num_measures,
			ArrayList<ArrayList<Integer>> mstates, String sz) {
		boolean is_except = false;
		int M = num_actual_tracks;
		String[] sep = sz.split(":");
		assert(sep.length == M || sep.length == 0);
		for(int i=0; i<M; i++) {
			mstates.get(i).clear();
		}
		int x = 0;
		try {
			for(int i=0; i<M; i++) {
				JSONArray ja = new JSONArray(sep[i]);
				int j = 0;
				while(j < ja.length()) {
					mstates.get(i).add(ja.getInt(j)); j++;
					x++;
				}
			}
		} catch(JSONException e) {
			for(int i=0; i<num_actual_tracks; i++) {
				mstates.get(i).clear();
				for(int j=0; j<num_measures; j++) {
					mstates.get(i).add(0);
				}
			}
			is_except = true;
		}
		Log.v("populateMasteryStateArray", "elts="+x);
		return is_except;
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
	
	public static String MasteryStateArrayToJSONString(ArrayList<ArrayList<Integer>> mstates) {
		int M = mstates.size();
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<M; i++) {
			JSONArray ja = new JSONArray();
			for(int j=0; j<mstates.get(i).size(); j++) {
				ja.put(mstates.get(i).get(j));
			}
			sb.append(ja.toString());
			if(i < M-1) sb.append(":");
		}
		return sb.toString();
	}
	
	public static String QuizCoarseStatisticsToJSONString(
			ArrayList<ArrayList<Integer>> right_clicks, // Per tile
			ArrayList<ArrayList<Integer>> wrong_clicks,
			ArrayList<ArrayList<Long>> millises
			) {
		int M = right_clicks.size();
		StringBuilder sb = new StringBuilder();
		for(int j=0; j<M; j++) {
			JSONArray ja = new JSONArray();
			for(int i=0; i<right_clicks.get(j).size(); i++) {
				ja.put(right_clicks.get(j).get(i));
				ja.put(wrong_clicks.get(j).get(i));
				ja.put(millises.get(j).get(i));
			}
			sb.append(ja.toString());
			if(j < M-1) sb.append(":");
		}
		return sb.toString();
	}
	
	public static boolean populateQuizCoarseStatisticsFromJSONString(
			int num_actual_tracks, int num_measures,
			ArrayList<ArrayList<Integer>> right_clicks, // Per tile
			ArrayList<ArrayList<Integer>> wrong_clicks,
			ArrayList<ArrayList<Long>> millises,
			String sz) {
		boolean is_ok = true;
		int M = num_actual_tracks;
		String[] sep = sz.split(":");
		assert(M == sep.length || 0 == sep.length);
		for(int i=0; i<M; i++) { 
			right_clicks.add(new ArrayList<Integer>()); 
			wrong_clicks.add(new ArrayList<Integer>());
			millises.add(new ArrayList<Long>());
		}
		Log.v("JSON -> Quiz Stats", " " + sep.length + ", M=" + M);
		try {
			for(int i=0; i<M; i++) {
				JSONArray ja = new JSONArray(sep[i]);
				int j = 0;
				while(j < ja.length()) {
					right_clicks.get(i).add(ja.getInt(j)); j++;
					wrong_clicks.get(i).add(ja.getInt(j)); j++;
					millises.get(i).add(ja.getLong(j));    j++;
				}
			}
		} catch(JSONException e) { // Load default data
			e.printStackTrace();
			is_ok = false;
			right_clicks.clear();
			wrong_clicks.clear();
			millises.clear();
			for(int i=0; i<num_actual_tracks; i++) {
				right_clicks.add(new ArrayList<Integer>(Collections.nCopies(num_measures, 0)));
				wrong_clicks.add(new ArrayList<Integer>(Collections.nCopies(num_measures, 0)));
				millises.add(new ArrayList<Long>(Collections.nCopies(num_measures, 0L)));
			}
		}
		return is_ok;
	}
	
	public static boolean populateQuizFineStaticsFromJSONString(
			int num_actual_tracks, int num_measures,
			ArrayList<ArrayList<ArrayList<Boolean>>> ok_clicks_f,
			ArrayList<ArrayList<ArrayList<Long>>> millises_f,
			String sz
		) {
		boolean is_ok = true;
		ok_clicks_f.clear(); millises_f.clear();
		try {
			String[] sep = sz.split("\\#\\$\\#");
			Log.v("Populate FG History", sep.length + ", " + sz);
			assert(sep.length <= TommyConfig.FINEGRAINED_HISTORY_LENGTH);
			if(sep.length < TommyConfig.FINEGRAINED_HISTORY_LENGTH) { throw new JSONException("blah"); }
			for (int age = 0; age < sep.length; age++) {
				String sep0 = sep[age];
				String sep1[] = sep0.split(":");
				assert(sep1.length == num_actual_tracks);
				ArrayList<ArrayList<Boolean>> this_age_okclicks = new ArrayList<ArrayList<Boolean>>();
				ArrayList<ArrayList<Long>>    this_age_millis   = new ArrayList<ArrayList<Long>>();
				for(int tid = 0; tid < num_actual_tracks; tid ++) {
					JSONArray ja = new JSONArray(sep1[tid]);
					int j = 0;
					ArrayList<Boolean> this_track_okclicks = new ArrayList<Boolean>();
					ArrayList<Long>    this_track_millis   = new ArrayList<Long>();
					Log.v("Populate FG History", "ja.length="+ja.length()+", measure="+num_measures);
					while(j < ja.length()) {
						this_track_okclicks.add(ja.getBoolean(j)); j++;
						this_track_millis.add(ja.getLong(j));      j++;
					}
					this_age_okclicks.add(this_track_okclicks);
					this_age_millis.add(this_track_millis);
				}
				ok_clicks_f.add(this_age_okclicks);
				millises_f.add(this_age_millis);
			}
		} catch (JSONException e) {
			e.printStackTrace();
			is_ok = false;
			ok_clicks_f.clear();
			millises_f.clear();
			for(int i=0; i<TommyConfig.FINEGRAINED_HISTORY_LENGTH; i++) {
				ArrayList<ArrayList<Boolean>> this_track_okclicks = new ArrayList<ArrayList<Boolean>>();
				ArrayList<ArrayList<Long>> this_track_millis = new ArrayList<ArrayList<Long>>();
				for(int tid = 0; tid < num_actual_tracks; tid++) {
					this_track_okclicks.add(new ArrayList<Boolean>(Collections.nCopies(num_measures, false)));
					this_track_millis.add(new ArrayList<Long>(Collections.nCopies(num_measures, 0L)));
				}
				ok_clicks_f.add(this_track_okclicks);
				millises_f.add(this_track_millis);
			}
		}
		return is_ok;
	}
	
	public static String QuizFineStatisticsToJSONString(
			ArrayList<ArrayList<ArrayList<Boolean>>> ok_clicks_f,
			ArrayList<ArrayList<ArrayList<Long>>> millises_f) {
		StringBuilder sb = new StringBuilder();
		int num_tracks = ok_clicks_f.get(0).size();
		int num_measures = ok_clicks_f.get(0).get(0).size();
		for(int age=0; age < TommyConfig.FINEGRAINED_HISTORY_LENGTH; age++) {
			if(age > 0) sb.append("#$#");
			for(int tidx = 0; tidx < num_tracks; tidx++) {
				ArrayList<Boolean> this_track_okclicks = ok_clicks_f.get(age).get(tidx);
				ArrayList<Long>    this_track_millis   = millises_f.get(age).get(tidx);
				JSONArray ja = new JSONArray();
				for(int midx = 0; midx < num_measures; midx++) {
					ja.put(this_track_okclicks.get(midx));
					ja.put(this_track_millis.get(midx));
				}
				if(tidx > 0) sb.append(":");
				sb.append(ja.toString());
			}
		}
		Log.v("FG History", sb.toString());
		return sb.toString();
	}
}
