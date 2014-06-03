package com.quadpixels.midisheetmusicmemo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiOptions;
import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.midisheetmusicmemo.R;
import com.midisheetmusicmemo.SheetMusic;
import com.midisheetmusicmemo.SheetMusic.Vec2;

// 2014-03-02: Refactor to prepare for more refactoring --- bitmap cache
// 2014-03-03: Tested on Android 4.3 and attempting to find the cause of the memory leak
// 2014-03-06: Cause of memory leak bug = Forgot to finish the updater updater_thread
// 2014-03-13: Add separator in U.I.
// 2014-05-03: Improve Inertial Scrolling.
// 2014-05-05: To implement "Mission Accomplished" dialog, showing Before and After
// 2014-05-31: Bugfix: when moving the horizontal bar, recycle area would go out-of-bounds.
// 2014-06-03: "Track" and "staff" are used interchangeably in this doc
//             Added histograms to status load/save during orientation change

public class TommyView2 extends View implements Runnable {
	
	// Some constants
	String difficulty_1, difficulty_2, difficulty_3, difficulty_4;
	String please_make_a_choice, well_done, tiles_to_go;
	String str_quiz_tiles_to_go, str_quiz_seconds, str_quiz_num_clicks, str_quiz_measures;
	
	private static SheetMusic sheet1;
	MidiOptions options;
	boolean is_freed = false;
	private boolean DEBUG = MidiSheetMusicActivity.DEBUG;
	private boolean is_running;
	float density;
	Random rnd = new Random();
	boolean is_inited = false,
			is_ui_created = false;
	long elapsed_millis, last_redraw_millis;
	
	int AREA1_HGT, AREA1_bitmap_hgt0; // Recycling Area Height
	int width, height;
	float AREA1_zoom_x, AREA1_zoom_y; // Preset zoom, can be adjusted by user.
	int curr_layout_id;
	float blanks_ratio = 0.5f;
	int frame_count = 0;
	final int FRAME_DELAY = 17;
	
	int BITMAP_MEM_BUDGET = 0;
	long last_heap_memory_alloc = 0;
	
	Context ctx; Thread thread; Paint paint, bmp_paint;
	SheetMusic sheet;

	ArrayList<Integer> measureHeights;
	ArrayList<Integer> measureWidths;
	ArrayList<ArrayList<Integer> > measureHashes;
	ArrayList<ArrayList<MeasureStatus>> measures_status = new ArrayList<ArrayList<MeasureStatus>>();
	ArrayList<ArrayList<Integer>> deck_uids = new ArrayList<ArrayList<Integer>>();
	int curr_hl_staff = -1, curr_hl_measure = -1;
	long curr_highlighted_millis; boolean is_curr_measure_ok_first_try = true;
	int num_measures, num_staffs, num_tiles_total, num_tiles_hidden, num_notes;
	int num_right_clicks, num_wrong_clicks;
	String midi_title = "No title", midi_uri_string=null;
	byte[] midi_data;
	float recycle_area_score_x_offset = 0; // for state change only
	ArrayList<TileInAnimation> tiles_in_animation = new ArrayList<TileInAnimation>();
	
	boolean need_redraw = false;
	SharedPreferences prefs_highscores, prefs_lastplayed, prefs_quizcount, prefs_quizstats, prefs_finegrained;
	int num_times_played = 0; long checksum;
	
	ArrayList<ArrayList<Long>> highscores = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Long>> timestamps = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Integer>> right_clicks_history = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> wrong_clicks_history = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> right_clicks_measures= new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> wrong_clicks_measures= new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Long>> delays_measures= new ArrayList<ArrayList<Long>>();
	ArrayList<Integer> actual_track_idx = new ArrayList<Integer>();
	ArrayList<ArrayList<ArrayList<Boolean>>> okclicks_history_f = new ArrayList<ArrayList<ArrayList<Boolean>>>();
	ArrayList<ArrayList<ArrayList<Long>>> millis_history_f = new ArrayList<ArrayList<ArrayList<Long>>>();

	// This array is to be read from conf file
	ArrayList<ArrayList<Integer>> measure_mastery_states       = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<TommyMastery>> masteries = new ArrayList<ArrayList<TommyMastery>>();
	
	// Histograms, before and after. I need to use ArrayList b/c I need to serialize them
	//   and save them across orientation changes.
	int[] mastery_histogram_before = null;
	int[] mastery_histogram_after  = null;
	Bitmap state_transitions_bmp = null;
	
	Rect src = new Rect(), dst = new Rect();
	static final int SEPARATOR_HEIGHT = 12; 
	static final int NAVIGATION_BUTTON_WIDTH = 24;
	
	// Resizing Recycle Area
	int pinch_begin_x_avg = 0, pinch_begin_y_avg = 0;
	int pinch_touchid0 = -999, pinch_touchid1 = -999;
	int pinch_begin_AREA1_HGT = -999;
	boolean is_resizing_recycling_area = false;
	
	public void pause() {
		is_running = false;
	}
	
	public void resume() {
		is_running = true;
	}
	
	String highScoreArrayToString(ArrayList<Long> x) {
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<x.size(); i++) {
			if(i > 0) {
				sb.append(',');
			}
			sb.append(x.get(i));
		}
		return sb.toString();
	}
	void highScoreStringToArray(String x, ArrayList<Long> array) {
		String[] sp = x.split(",");
		Log.v("tommyview2", x + ", sp.length="+sp.length);
		for(String blah : sp) { Log.v("tommyview2", blah); }
		array.clear();
		for(int i=0; i<sp.length; i++) {
			String s = sp[i];
			if(s.length() > 0) {
				array.add(Long.parseLong(sp[i]));
			}
		}
	}

	BitmapHelper bitmap_helper;
	
	enum GameState {
		NOT_STARTED,
		PLAYING,
		FINISHED;
	};
	GameState game_state = GameState.NOT_STARTED;
	
	void saveState(Bundle bundle) {
		for(int i=0; i<num_staffs; i++) {
			for(int j=0; j<num_measures; j++) {
				MeasureStatus st = measures_status.get(i).get(j); 
				if(st == MeasureStatus.IN_ANIMATION) {
					st = MeasureStatus.IN_RECYCLE;
				} else if(st == MeasureStatus.IN_SELECTION) { // 把发出来了的牌收回去。「接下来发什么牌」是在deck_uids里保存的。
					st = MeasureStatus.HIDDEN;
					int uid = getTileUIDFromMidSid(j, i);
					deck_uids.get(0).add(uid);
				}
				measures_status.get(i).set(j, st);
			}
		}
		bundle.putSerializable("deck_uids", deck_uids);
		bundle.putSerializable("measure_hashes", measureHashes);
		bundle.putSerializable("measure_statuses", measures_status);
		bundle.putBoolean("is_inited", is_inited);
		bundle.putInt("num_measures", num_measures);
		bundle.putInt("num_staffs",   num_staffs);
		bundle.putInt("num_tiles_total", num_tiles_total);
		bundle.putInt("num_tiles_hidden", num_tiles_hidden);
		bundle.putInt("curr_hl_staff", curr_hl_staff);
		bundle.putInt("curr_hl_measure", curr_hl_measure);
		bundle.putFloat("recycle_area_score_x_offset", recycle_area.score_x_offset);
		bundle.putInt("num_notes", num_notes);
		bundle.putString("title", midi_title);
		bundle.putLong("last_redraw_millis", last_redraw_millis);
		bundle.putLong("elapsed_millis", elapsed_millis);
		bundle.putSerializable("game_state", game_state);
		bundle.putLong("checksum", checksum);
		bundle.putInt("num_times_played", num_times_played);
		bundle.putSerializable("highscores", highscores);
		bundle.putSerializable("timestamps", timestamps);
		bundle.putSerializable("right_clicks_history", right_clicks_history);
		bundle.putSerializable("wrong_clicks_history", wrong_clicks_history);
		bundle.putSerializable("measure_heights", measureHeights);
		bundle.putSerializable("measure_widths", measureWidths);
		bundle.putFloat("blanks_ratio", blanks_ratio);
		bundle.putInt("num_right_clicks", num_right_clicks);
		bundle.putInt("numwrong_clicks", num_wrong_clicks);
		bundle.putIntArray("mastery_histogram_before", mastery_histogram_before);
		bundle.putIntArray("mastery_histogram_after",  mastery_histogram_after);
		bundle.putSerializable("actual_track_idx", actual_track_idx);
		bundle.putSerializable("masteries", masteries);
		bundle.putSerializable("measure_mastery_states", measure_mastery_states);
		bitmap_helper.free();
		is_running = false;
	}
	
	// A crazy smoke tester
	// may rotate the screen 2147483647 times in 1 quiz, causing the program to crash! 
	@SuppressWarnings("unchecked")
	public void loadState(Bundle bundle) {
		deck_uids = (ArrayList<ArrayList<Integer>>)bundle.getSerializable("deck_uids");
		measureHashes = (ArrayList<ArrayList<Integer>>)(bundle.getSerializable("measure_hashes"));
		measures_status = (ArrayList<ArrayList<MeasureStatus>>)(bundle.getSerializable("measure_statuses"));
		is_inited = bundle.getBoolean("is_inited");
		num_measures = bundle.getInt("num_measures");
		num_staffs = bundle.getInt("num_staffs");
		num_tiles_total = bundle.getInt("num_tiles_total");
		num_tiles_hidden = bundle.getInt("num_tiles_hidden");
		curr_hl_staff = bundle.getInt("curr_hl_staff");
		curr_hl_measure = bundle.getInt("curr_hl_measure");
		recycle_area_score_x_offset = bundle.getFloat("recycle_area_score_x_offset");
		num_notes = bundle.getInt("num_notes");
		midi_title = bundle.getString("title");
		last_redraw_millis = bundle.getLong("last_redraw_millis");
		game_state = (GameState)(bundle.getSerializable("game_state"));
		elapsed_millis = bundle.getLong("elapsed_millis");
		checksum = bundle.getLong("checksum");
		num_times_played = bundle.getInt("num_times_played");
		highscores = (ArrayList<ArrayList<Long>>)bundle.getSerializable("highscores");
		timestamps = (ArrayList<ArrayList<Long>>)bundle.getSerializable("timestamps");
		right_clicks_history = (ArrayList<ArrayList<Integer>>)bundle.getSerializable("right_clicks_history");
		wrong_clicks_history = (ArrayList<ArrayList<Integer>>)bundle.getSerializable("wrong_clicks_history");
		measureHeights = (ArrayList<Integer>)bundle.getSerializable("measure_heights");
		measureWidths  = (ArrayList<Integer>)bundle.getSerializable("measure_widths");
		sheet = TommyView2.sheet1;
		bitmap_helper = new BitmapHelper(this, sheet, AREA1_zoom_x, AREA1_zoom_y);
		blanks_ratio = bundle.getFloat("blanks_ratio");
		num_right_clicks = bundle.getInt("num_right_clicks");
		num_wrong_clicks = bundle.getInt("num_wrong_clicks");
		actual_track_idx = (ArrayList<Integer>) bundle.getSerializable("actual_track_idx");
		masteries = (ArrayList<ArrayList<TommyMastery>>) bundle.getSerializable("masteries");
		mastery_histogram_before= bundle.getIntArray("mastery_histogram_before");
		mastery_histogram_after = bundle.getIntArray("mastery_histogram_after");
		measure_mastery_states = (ArrayList<ArrayList<Integer>>) bundle.getSerializable("measure_mastery_states");
		is_running = true;
	}
	

	// Bitmaps helper
	class BitmapHelper {
		SheetMusic sheet;
		TommyView2 view;
		private int bytes_consumed;
		private float bitmap_zoom_x, bitmap_zoom_y;
		BitmapHelper(TommyView2 _tv, SheetMusic _sheet, float _bz_x, float _bz_y) {
			bytes_consumed = 0;
			view = _tv; sheet = _sheet;
			bitmap_zoom_x = _bz_x;
			bitmap_zoom_y = _bz_y;
		}
		@SuppressLint("UseSparseArrays")
		HashMap<Integer, Bitmap> cached_bmps = new HashMap<Integer, Bitmap>();
		Bitmap getTileBitmap(int staff_idx, int measure_idx) {
			int uid = view.getTileUIDFromMidSid(measure_idx, staff_idx);
			if(cached_bmps.containsKey(uid) == false) {
				Bitmap bmp = sheet.RenderTile(measure_idx, staff_idx, bitmap_zoom_x, bitmap_zoom_y);
				bytes_consumed += bmp.getHeight() * bmp.getWidth() * 2; // RGB 565, 1 pixel = 2 bytes
				synchronized(cached_bmps) {
					cached_bmps.put(uid, bmp);
				}
			}
			return cached_bmps.get(uid);
		}
		int getTileBitmapWidth(int staff_idx, int measure_idx) {
			return view.measureWidths.get(measure_idx);
		}
		int getTileBitmapHeight(int staff_idx, int measure_idx) {
			return view.measureHeights.get(staff_idx);
		}
		int getBytesConsumed() { return bytes_consumed; }
		private void do_clearBmp(int key) {
			Bitmap bmp = cached_bmps.get(key);
			cached_bmps.remove(key);
			bytes_consumed -= bmp.getHeight() * bmp.getWidth() * 2;
			bmp.recycle();
			bmp = null;
		}
		public void clearOutOfSightBitmaps() {
			int midsid[] = {-1, -1};
			Set<Integer> keys = cached_bmps.keySet();
			HashSet<Integer> to_delete = new HashSet<Integer>();
			synchronized(cached_bmps) {
				for(Integer x : keys) {
					view.getTileMidSidFromUID(x, midsid);
					int midx = midsid[0], sidx = midsid[1];
					TommyView2.MeasureStatus status = view.measures_status.get(sidx).get(midx);
					if(status == TommyView2.MeasureStatus.IN_ANIMATION ||
							status == TommyView2.MeasureStatus.IN_SELECTION) {
						;
					} else {
						if(view.recycle_area.isMeasureOutOfSight(midx)) {
							to_delete.add(x);
						}
					}
				}
				for(Integer x : to_delete) {
					do_clearBmp(x);
				}
			}
		}
		public void free() {
			Set<Integer> keys = cached_bmps.keySet();
			HashSet<Integer> to_delete = new HashSet<Integer>();
			for(Integer x : keys) { to_delete.add(x); }
			for(Integer x : to_delete) { do_clearBmp(x); }
			cached_bmps.clear();
		}
	}
	
	class TileInAnimation {
		int x_start, y_start; // Screen space
		int x_end_offset, y_end_offset; // Score space, need multiply by zoom
		int measure_idx, staff_idx;
		float start_zoom, completion;
		long start_millis;
		final static int DURATION = 300; // 300 ms
		boolean is_ended = false;
		
		TileInAnimation(int _x_start, int _y_start, int _staff_idx, int _measure_idx, float _start_zoom) {
			x_start = _x_start; y_start = _y_start;
			int xy[] = {-1, -1};
			staff_idx = _staff_idx; measure_idx = _measure_idx;
			recycle_area.getTileXYOffsetZoom(staff_idx, measure_idx, xy);
			x_end_offset = xy[0]; y_end_offset = xy[1];
			start_zoom = _start_zoom;
			start_millis = System.currentTimeMillis();
			completion = 0.0f;
		}
		
		void draw(Canvas c) {
			if(!is_ended) {
				float end_zoom = recycle_area.zoom_x;
				int x = (int) (
						x_start * (1.0f - completion) +
						(x_end_offset - recycle_area.score_x_offset) * end_zoom * completion
					);
				int y = (int) (
						y_start * (1.0f - completion) + 
						(y_end_offset * recycle_area.zoom_y) * completion
					);
				float zm_x = start_zoom * (1.0f - completion) + (end_zoom) * completion;
				float zm_y = start_zoom * (1.0f - completion) + (recycle_area.zoom_y)* completion ;
				Bitmap bmp = bitmap_helper.getTileBitmap(staff_idx, measure_idx);
				int w = (int)(zm_x * bmp.getWidth());
				int h = (int)(zm_y * bmp.getHeight());
				src.set(0, 0, bmp.getWidth(), bmp.getHeight());
				dst.set(x, y, x+w, y+h);
				NinePatchDrawable bk;
				if(staff_idx == 0) {
					bk = TommyConfig.getCurrentStyle().tile_bk_upper;
				} else bk = TommyConfig.getCurrentStyle().tile_bk_lower;
				bk.setBounds(dst);
				bk.setTargetDensity(c);
				bk.draw(c);
				c.drawBitmap(bmp, src, dst, bmp_paint);
			}
		}
		
		void update(long millis) {
			long delta = millis - start_millis;
			if(delta > DURATION) { // Ends animation
				is_ended = true;
				completion = 1.0f;
				measures_status.get(staff_idx).set(measure_idx, MeasureStatus.IN_RECYCLE);
			} else {
				float t = delta * 1.0f / DURATION;
				completion = 1.0f - (1.0f-t) * (1.0f-t);
			}
		}
	};

	static enum MeasureStatus {
		HIDDEN, IN_SELECTION, IN_RECYCLE,
		IN_ANIMATION
	}
	
	void help_getRecycleZoneBB(Rect bb) {
		if(recycle_area == null) {
			bb.left = bb.right = bb.top = bb.bottom = -1;
			return;
		}
		bb.left   = recycle_area.x;
		bb.bottom = recycle_area.y;
		bb.right  = recycle_area.x + recycle_area.W;
		bb.top    = recycle_area.y + recycle_area.H;
	}
	
	void help_getSelectionZoneBB(Rect bb) {
		int x0 = 2147483647, x1 = 0, y0 = 2147483647, y1 = 0;
		for(SelectionTile st : tiles) {
			if(st.x < x0) x0 = st.x;
			if(st.x + st.W > x1) x1 = st.x + st.W;
			if(st.y < y0) y0 = st.y;
			if(st.y + st.H > y1) y1 = st.y + st.H;
		}
		bb.left = x0; bb.right = x1; bb.top = y1; bb.bottom = y0;
	}
	
	void getFirstHighlightStaffMeasure() {
		int num_hidden = getMeasureStatusCountByType(MeasureStatus.HIDDEN);
		int num_select = getMeasureStatusCountByType(MeasureStatus.IN_SELECTION);
		if(num_hidden + num_select <= 0) {
			curr_hl_staff = curr_hl_measure = -1;
			return;
		}
		for(int i=0; i<num_measures; i++) {
			for(int j=0; j<num_staffs; j++) {
				MeasureStatus status = measures_status.get(j).get(i); 
				if(status == MeasureStatus.HIDDEN || status == MeasureStatus.IN_SELECTION) {
					curr_hl_staff = j;
					curr_hl_measure = i;
					curr_highlighted_millis = System.currentTimeMillis();
					is_curr_measure_ok_first_try = true;
					return;
				}
			}
		}
	}
	
	void advanceHighlightedStaffMeasureIdx() {
		measures_status.get(curr_hl_staff).set(curr_hl_measure, MeasureStatus.IN_ANIMATION);
		num_tiles_hidden --;
		Log.v("Advance", String.format("num_tiles_hidden=%d", num_tiles_hidden));
		if(num_tiles_hidden > 0) {
			getFirstHighlightStaffMeasure();
			Log.v("advance", String.format("measure %d", curr_hl_measure));
			if(recycle_area.isMeasureOutOfSight(curr_hl_measure)) {
				recycle_area.centerAtMeasure(curr_hl_measure);
			}
		} else {
			if(MidiSheetMusicActivity.DEBUG) {
				Toast.makeText(ctx, "Game finished! Congrats!", Toast.LENGTH_LONG).show();
			}
			game_state = GameState.FINISHED;
			SharedPreferences.Editor editor = prefs_highscores.edit();
			SharedPreferences.Editor editor_playcount = prefs_quizcount.edit();
			SharedPreferences.Editor editor_quizstats = prefs_quizstats.edit();
			SharedPreferences.Editor editor_lastplay  = prefs_lastplayed.edit();
			SharedPreferences.Editor editor_finegrained=prefs_finegrained.edit();
			
			String key1 = String.format("%x", checksum), key2 = midi_uri_string; // 2014-06-20 midi_title -> midi_uri_string
			
			// Update # plays of the current file.
			num_times_played ++;
			editor_playcount.putInt(midi_uri_string, num_times_played);
			editor_playcount.commit();
			
			{
				long last_play_us = System.currentTimeMillis();
				editor_lastplay.putLong(key1, last_play_us);
				editor_lastplay.putLong(key2, last_play_us);
				editor_lastplay.commit();
			}
			
			long curr_millis = System.currentTimeMillis(); 
			
			// Update histories of the current file.
			int hs_array_idx = -999;
			for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
				if(TommyConfig.BLANK_RATIOS[i] == blanks_ratio) {
					hs_array_idx = i; break;
				}
			}

			highscores.get(hs_array_idx).add(elapsed_millis);
			timestamps.get(hs_array_idx).add(curr_millis);
			right_clicks_history.get(hs_array_idx).add(num_right_clicks);
			wrong_clicks_history.get(hs_array_idx).add(num_wrong_clicks);
			
			String key = String.format("%x_HS", checksum);
			
			long ts1, ts2, ts3, ts4;
			ts1 = System.currentTimeMillis();
			{ // Highscores
				StringBuilder hssb = new StringBuilder();
				hssb.append(TommyConfig.HSTSArraysToJSONString(highscores, timestamps, right_clicks_history, wrong_clicks_history));
				editor.putString(key, hssb.toString());
				editor.commit();
			}
			
			ts2 = System.currentTimeMillis();
			{ // Quiz Stats
				String sz = TommyConfig.QuizCoarseStatisticsToJSONString(right_clicks_measures, wrong_clicks_measures, delays_measures);
				editor_quizstats.putString(key1, sz);
				Log.v("TommyView2", "New QuizStats String="+sz);
				editor_quizstats.commit();
			}
			
			ts3 = System.currentTimeMillis();
			{ // Fine-grained Stats
				String sz = TommyConfig.QuizFineStatisticsToJSONString(okclicks_history_f, millis_history_f);
				editor_finegrained.putString(key1, sz);
				editor_finegrained.commit();
			}
			
			// Mastery Levels.
			int NT = sheet.getActualNumberOfTracks();
			// Mastery level histogram.
			for(int i=0; i<TommyMastery.MASTERY_STATE_SCORES.length; i++)
				mastery_histogram_after[i] = 0;
			String cksm = String.format("%x", checksum);
			{
				for(int i=0; i<NT; i++) { // i is index of staff.
					boolean is_shown = true;
					if(actual_track_idx.indexOf(i) == -1) is_shown = false;
					for(int j=0; j<num_measures; j++) { // 2014-05-31: Ignore the first measure 
						int st = masteries.get(i).get(j).getMasteryState();
						measure_mastery_states.get(i).set(j, st);
						if(is_shown && j>0) mastery_histogram_after[st] ++;
					}
				}
				String sz = TommyConfig.MasteryStateArrayToJSONString(measure_mastery_states);
				editor_quizstats.putString(cksm + "_mastery_states", sz);
				editor_quizstats.commit();
			}
			
			ts4 = System.currentTimeMillis();
			if(MidiSheetMusicActivity.DEBUG) {
				String msg = String.format("%d ms = %d HScore + %d QStats + %d QFineStats", ts4-ts1, ts2-ts1, ts3-ts2, ts4-ts3);
				Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
			}
			
			// Hide Selection Tile, show State Transition Tile
			for(SelectionTile st : tiles) st.is_visible = false;
			for(StateTransitionTile stt : sttiles) {
				stt.is_visible = true;
				stt.need_redraw = true;
			}
		}
	}
	
	// Widgets.
	InvisibleDPad[] invdpads = new InvisibleDPad[10];
	int touches_on_bk = 0;
	RecycleArea recycle_area = null;
	ArrayList<SelectionTile> tiles = new ArrayList<SelectionTile>();
	ArrayList<StateTransitionTile> sttiles = new ArrayList<TommyView2.StateTransitionTile>();
	
	class InvisibleDPad {
		final static int TRIPLECLICK_WINDOW = 300; // 300 ms
		int touchx, touchy; // Screen coordinates
		int deltax, deltay;
		int touchid = -1;
		int click_count = 0;
		int window_idx = 0;
		
		public void clearDeltaXY() { deltax = deltay = 0; }
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				touchx = x; touchy = y;
				touchid = id;
				touches_on_bk ++;
				return true;
			} else {
				if(touchid != id) {
					return false;
				}
			}
			return true;
		}
		boolean touchUp(int id) {
			if(id == touchid) {
				touchid = -1;
				deltax = deltay = touchx = touchy = 0;
				touches_on_bk --;
			}
			clearDeltaXY();
			return true;
		}
		boolean touchMove(int id, int x, int y) {
			if(touchid == -1) {
				;
			} else if(touchid == id) {
				deltax += x - touchx;
				deltay += y - touchy;
				touchx = x;
				touchy = y;
			} else {
				;
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
		void update(long millis) {
		}
	}

	static enum FloatingButtonType {
		SEPARATOR,
		LEFT_BUTTON,
		RIGHT_BUTTON
		// Focus and toggle zoom may be put on Separator since they arent frequently used!
	};
	class FloatingButton {
		int W, H, x, y, touchid = -1, touchx, touchy; // Touch X and Touch Y are in screen coordinates
		int touchx_begin, touchy_begin, deltay;
		Bitmap bmp_label;
		NinePatchDrawable bk;
		FloatingButtonType type;
		int id; // Left Button / Right Button
		boolean need_redraw;
		public FloatingButton (int _w, int _h, int _x, int _y, FloatingButtonType _ty) {
			x = _x; y = _y; H = _h; W = _w; type = _ty;
		}
		public boolean intersect(int tx, int ty) {
			if(tx > x && tx < x+W && ty > y && ty < y+H) return true;
			else return false;
		}
		public boolean touchDown(int id, int tx, int ty) {
			if(touchid == -1) {
				need_redraw = true;
				if(intersect(tx, ty)) {
					touchid = id;
					touchx = touchx_begin = tx; touchy = touchy_begin = ty;
					deltay = 0;
					switch(type) {
					case SEPARATOR:
						if(is_resizing_recycling_area == false) {
							pinch_begin_AREA1_HGT = AREA1_HGT;
							is_resizing_recycling_area = true;
						}
						break;
					default: break;
					}
					return true;
				} else return false;
			} else return false;
		}
		public boolean touchMove(int id, int x, int y) {
			if(touchid == id) {
				deltay += y - touchy;
				touchx = x; touchy = y; 
			}
			return true;
		}
		public boolean touchUp(int id) {
			if(touchid != -1) { 
				touchid = -1;
				if(intersect(touchx, touchy)) {
					switch(type) {
						case SEPARATOR:
							{
								if(is_resizing_recycling_area == true) {
									is_resizing_recycling_area = false;
									AREA1_HGT = pinch_begin_AREA1_HGT= pinch_begin_AREA1_HGT + (touchy - touchy_begin);
									recycle_area.panScoreByScreenX(0.0f); // Make sure not moving out of bounds
									clearDeltaXY();
								}
							}
							break;
						case LEFT_BUTTON:
						{
							recycle_area.animateScrollOneMeasureToTheLeft(); break;
						}
						case RIGHT_BUTTON:
						{
							recycle_area.animateScrollOneMeasureToTheRight();break;
						}
						default: break;
					}
				}
			}
			return true;
		}
		public void draw(Canvas c) {
			dst.set(x, y, x+W, y+H);
			if(bk != null) {
				bk.setBounds(dst);
				bk.draw(c);
				if(isTouched()) {
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					paint.setAlpha(128);
					paint.setStyle(Style.FILL);
					c.drawRect(dst, paint);
					paint.setAlpha(255);
				}
			}
			switch(type) {
			case LEFT_BUTTON:
			case RIGHT_BUTTON:
				dst.set((int)(x+2*density), (int)(y+H-2*density-5*density), 
						(int)(x+W-2*density), (int)(y+H-2*density));
				paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
				paint.setStyle(Style.STROKE);
				c.drawRect(dst, paint);
			break;
				default: break;
			}
			need_redraw = false;
		}
		public void clearDeltaXY() { deltay = 0; }
		public boolean isTouched() {
			if(touchid == -1) return false;
			else return true;
		}
	}
	FloatingButton separator, left_button, right_button;
	
	
	// For making choice.
	class SelectionTile {
		boolean need_redraw = false;
		boolean is_visible  = true;
		int W, H, x, y, touchid, touchx, touchy, staff_idx, measure_idx, pad;
		int shake_delta_x = 0;
		int bmp_x, bmp_y, bmp_hw, bmp_hh;
		float zoom;
		long shake_begin_millis = 0;
		final static long SHAKE_DURATION = 500;
		boolean is_shaking = false;
		int id = 0;
		Bitmap bmp;
		
		public SelectionTile(int _x, int _y, int w, int h) {
			initLayout(_x, _y, w, h);
			staff_idx = measure_idx = -1;
			touchid = -1;
			shake_delta_x = 0;
		}
		
		public void initLayout(int _x, int _y, int _W, int _H) {
			x = _x; y = _y; W = _W; H = _H; pad = (int)(4*density);
			computeBitmapPosition();
		}
		
		private void computeBitmapPosition() {
			if(staff_idx == -1 || measure_idx == -1) { return; }
			else {
				int bw = bitmap_helper.getTileBitmapWidth(staff_idx, measure_idx), 
						bh = bitmap_helper.getTileBitmapHeight(staff_idx, measure_idx);
				
					float zoom_w = 1.0f * (W - 2*pad) / bw;
					float zoom_h = 1.0f * (H - 2*pad) / bh;
					
					if(zoom_w < zoom_h) zoom = zoom_w;
					else zoom = zoom_h;	
					
					bmp_hw = (int)(bw * 0.5f * zoom);
					bmp_hh = (int)(bh * 0.5f * zoom);
					bmp_x  = x+W/2 - bmp_hw;
					bmp_y  = y+H/2 - bmp_hh;
			}
		}
		public void setMeasure(int _staff_idx, int _measure_idx) {
			need_redraw = true;
			if(_staff_idx == -1 || _measure_idx == -1) {; }
			else {
				measure_idx = _measure_idx; staff_idx = _staff_idx;
				measures_status.get(_staff_idx).set(_measure_idx, MeasureStatus.IN_SELECTION);
				computeBitmapPosition();
			}
			Log.v("SelectionTile", String.format("X=%d Y=%d zoom=%f", x, y, zoom));
		}

		void prepareToDraw() {
			bmp = null;
			if(staff_idx != -1 && measure_idx != -1) {
				bmp = bitmap_helper.getTileBitmap(staff_idx, measure_idx);
			}
		}
		public void draw(Canvas c) {
			need_redraw = false;
			if(!is_visible) return;
			if(bmp != null) {
				paint.setFilterBitmap(true);
				int ddy = 0;
				if(touchid != -1) ddy = (int)(4*density);
				src.set(0, 0, bmp.getWidth(), bmp.getHeight());
				dst.set(bmp_x + shake_delta_x + ddy,
						ddy + bmp_y, bmp_x+2*bmp_hw + shake_delta_x - ddy, 
						-ddy + bmp_y+2*bmp_hh);
				NinePatchDrawable bk;
				if(staff_idx == 0) {
					bk = TommyConfig.getCurrentStyle().tile_bk_upper;
				} else bk = TommyConfig.getCurrentStyle().tile_bk_lower;
				bk.setBounds(dst);
				bk.setTargetDensity(c);
				bk.draw(c);
				c.drawBitmap(bmp, src, dst, bmp_paint);
			} else {
				float cx = x + W/2, cy = y + H/2, r = (H<W) ? H/4 : W/4;
				if(game_state == GameState.NOT_STARTED) {
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint.setTextSize(16*density);
					String x;
					switch(id) {
					case 0:
						x = difficulty_1; break;
					case 1:
						x = difficulty_2; break;
					case 2:
						x = difficulty_3; break;
					case 3:
						x = difficulty_4; break;
					default:
						x = "";
					}
					paint.setStyle(Style.FILL);
					paint.setTextAlign(Align.CENTER);
					c.drawText(x, cx, cy, paint);
				} else {
					paint.setColor(0xFFC0C0C0);
					paint.setStyle(Style.FILL);
					c.drawCircle(cx, cy, r, paint);
				}
			}
			
			/*
			paint.setColor(0xFFC0C0FF);
			paint.setStyle(Style.STROKE);
			paint.setStrokeWidth(density);
			canvas.drawRect(x, y, x+W, y+H, paint);
			*/

			if(DEBUG) {
				if(need_redraw) {
					paint.setColor(Color.RED);
				} else {
					paint.setColor(Color.GREEN);
				}
				paint.setStyle(Style.STROKE);
				paint.setStrokeWidth(density);
				c.drawRect(x, y, x+W, y+H, paint);
			}
		}
		
		public boolean touchDown(int id, int tx, int ty) {
			if(tx > x && tx < x+W && ty > y && ty < y+H) {
				touchx = tx; touchy = ty;
				touchid = id;
				need_redraw = true;
				return true;
			}
			return false;
		}
		public boolean touchMove(int id, int x, int y) {
			if(touchid == -1) {
				;
			} else if(touchid == id) {
				touchx = x;
				touchy = y;
			} else {
				;
			}
			return true;
		}
		
		private void pushFineGrainedHistory(int staff_idx, int measure_idx, boolean is_ok, long millis) {
			int actual_sidx = actual_track_idx.get(staff_idx);
			for(int age = TommyConfig.FINEGRAINED_HISTORY_LENGTH-2; age>=0; age--) {
				Log.v("fine grained", "" + age + ","+actual_sidx+","+measure_idx);
				boolean tmp = okclicks_history_f.get(age)
						.get(actual_sidx)
						.get(measure_idx);
				okclicks_history_f.get(age+1).get(actual_sidx).set(measure_idx, tmp);
				long tmp1 = millis_history_f.get(age).get(actual_sidx).get(measure_idx);
				millis_history_f.get(age+1).get(actual_sidx).set(measure_idx, tmp1);
			}
			okclicks_history_f.get(0).get(actual_sidx).set(measure_idx, is_ok);
			millis_history_f.get(0).get(actual_sidx).set(measure_idx, millis);
		}
		
		private void onTileChosenInGame() {
			if(staff_idx != -1 && measure_idx != -1) {
				int hash_my = measureHashes.get(staff_idx).get(measure_idx),
					hash_ans = measureHashes.get(curr_hl_staff).get(curr_hl_measure);
				Log.v("touchUp", String.format("hash=%x, %x", hash_my, hash_ans));
				if(hash_ans == hash_my) {
					num_right_clicks ++;
					
					
					{
						// Add to statistics
						long delta = System.currentTimeMillis() - curr_highlighted_millis;
						int rc = right_clicks_measures.get(actual_track_idx.get(curr_hl_staff)).get(curr_hl_measure);
						right_clicks_measures.get(actual_track_idx.get(curr_hl_staff)).set(curr_hl_measure, rc+1);
						long millis = delays_measures.get(actual_track_idx.get(curr_hl_staff)).get(curr_hl_measure);
						delays_measures.get(actual_track_idx.get(curr_hl_staff)).set(curr_hl_measure, millis + delta);
						
						pushFineGrainedHistory(curr_hl_staff, curr_hl_measure, is_curr_measure_ok_first_try, delta);
						int actual_sidx = actual_track_idx.get(curr_hl_staff);
						masteries.get(actual_sidx).
							get(curr_hl_measure).appendOutcome(is_curr_measure_ok_first_try);
					}
					
					
					// When hashes collide
					// Swap the actual underlying tiles
					if(!(curr_hl_staff == staff_idx && curr_hl_measure == measure_idx)) {
						
						MeasureStatus st_actual = measures_status.get(curr_hl_staff).get(curr_hl_measure);
						if(st_actual == MeasureStatus.IN_ANIMATION) {
							throw new RuntimeException("Should not be in animation");
						} else if(st_actual == MeasureStatus.IN_SELECTION) {
							boolean is_ok = false;
							for(SelectionTile s : tiles) {
								if(s != this && s.measure_idx == curr_hl_measure && s.staff_idx == curr_hl_staff) {
									s.setMeasure(staff_idx, measure_idx);
									is_ok = true;
									break;
								}
							}
							if(!is_ok) {
								throw new RuntimeException("Should be ok");
							}
						}
						MeasureStatus st_mine   = measures_status.get(staff_idx).get(measure_idx);
						measures_status.get(curr_hl_staff).set(curr_hl_measure, st_mine);
						measures_status.get(staff_idx).set(measure_idx, st_actual);

						
						if(DEBUG) {
							String blah = String.format("Hash correction (%d,%d(%s)) <-> (%d,%d(%s))",
								curr_hl_staff, curr_hl_measure, st_actual.toString(),
								staff_idx, measure_idx, st_mine.toString());
							Toast.makeText(ctx, blah, Toast.LENGTH_LONG).show();
						}
						
						setMeasure(curr_hl_staff, curr_hl_measure);
						
					}
					
					TileInAnimation tia = new TileInAnimation(bmp_x, bmp_y, staff_idx, measure_idx, zoom);
					synchronized(tiles_in_animation) {
						tiles_in_animation.add(tia);
					}
					setRandomHiddenMeasure();
					advanceHighlightedStaffMeasureIdx();
					need_redraw = true;
				} else {
					num_wrong_clicks ++;
					{// Add to statistics
						Log.v("TommyView2", String.format("%d, %d", actual_track_idx.get(curr_hl_staff), curr_hl_measure));
						int wc = wrong_clicks_measures.get(actual_track_idx.get(curr_hl_staff)).get(curr_hl_measure);
						wrong_clicks_measures.get(actual_track_idx.get(curr_hl_staff)).set(curr_hl_measure, wc+1);
						is_curr_measure_ok_first_try = false;
					}
					startShaking();
				}
			} else {
			}
		}
		private void onTileChosenBeforeGame() {
			switch(id) {
			case 0:
				blanks_ratio = 0.25f; startGame(); break;
			case 1:
				blanks_ratio = 0.5f; startGame(); break;
			case 2:
				blanks_ratio = 0.75f; startGame(); break;
			case 3:
				blanks_ratio = 1.0f; startGame(); break;
		/*
			case 4: {
				AREA1_HGT += density*10.0f;
				if(curr_layout_id == 1) { adjustSizeLayout1(); }
				else if(curr_layout_id == 2) adjustSizeLayout2();
				break;
			}
			case 5: {
				AREA1_HGT -= density * 10.0f;
				if(curr_layout_id == 1) { adjustSizeLayout1(); }
				else if(curr_layout_id == 2) adjustSizeLayout2();
			}
		*/
			default:
				break;
			}
		}
		
		public boolean touchUp(int id) {
			if(id == touchid) {
				if(touchx > x && touchx < x+W && touchy > y && touchy < y+H) {
					if(game_state == GameState.PLAYING) {
						onTileChosenInGame();
					} else if(game_state == GameState.NOT_STARTED) {
						onTileChosenBeforeGame();
					}
				}
				touchid = -1;
				touchx = touchy = 0;
			}
			need_redraw = true;
			return true;
		}
		private void setToInvalid() {
			staff_idx = measure_idx = -1;
		}
		private void setRandomHiddenMeasure() {
			need_redraw = true;
			if(getMeasureStatusCountByType(MeasureStatus.HIDDEN) == 0) {
				Log.v("SelectionTile", "Set to invalid");
				setToInvalid();
			} else {
				int dealidx = 0;
				for(; dealidx<deck_uids.size(); dealidx++) {
					if(deck_uids.get(dealidx).size() > 0) break;
				}
				if(dealidx == deck_uids.size()) { 
					setToInvalid(); return; 
				}
				int this_deal_idx = rnd.nextInt(deck_uids.get(dealidx).size());
				int this_uid = deck_uids.get(dealidx).get(this_deal_idx);
				Log.v("SelectionTile", String.format("Using deal #%d idx #%d UID %d",
						dealidx, this_deal_idx, this_uid));
				deck_uids.get(dealidx).remove(this_deal_idx);
				int midsid[] = {-1, -1};
				getTileMidSidFromUID(this_uid, midsid);
				int sid = midsid[1];
				int mid = midsid[0];
				setMeasure(sid, mid);
			}
		}
		
		public void startShaking() {
			shake_begin_millis = System.currentTimeMillis();
			is_shaking = true;
		}
		
		public void update(long millis) {
			if(is_shaking) {
				need_redraw = true;
				long delta = millis - shake_begin_millis;
				if(delta > SHAKE_DURATION) {
					is_shaking = false;
					shake_delta_x = 0;
				} else {
					float completion = 1.0f*delta / SHAKE_DURATION;
					shake_delta_x = (int)(Math.sin(Math.PI * 2 * 3 * completion) * density * 10);
				}
			}
		}
	}
	
	class StateTransitionTile {
		final static int pad = 3, txt_H = 16;
		int W, H, x, y;
		int[] histogram = null;
		int[][] txt_xy = null;
		String title;
		boolean is_visible = false;
		boolean need_redraw = false;
		int strans_x, strans_y, strans_vis_w, strans_vis_h;
		float strans_zoom = 1.0f;
		public StateTransitionTile(int _w, int _h, int _x, int _y, int[] _hist) {
			W = _w; H = _h; x = _x; y = _y; histogram = _hist;
			int num_states = TommyMastery.MASTERY_COORDS.length;
			txt_xy = new int[num_states][2];
			computeBitmapZoom();
		}
		void draw(Canvas c) {
			need_redraw = false;
			if(!is_visible) return;
			synchronized(dst) {
				src.set(0, 0, state_transitions_bmp.getWidth(), state_transitions_bmp.getHeight());
				int ty = (int)(txt_H * density);
				dst.set(x + strans_x + pad, y + strans_y + pad + ty, 
						x + strans_x + pad + strans_vis_w, 
						y + strans_y + pad + strans_vis_h + ty);
				paint.setFilterBitmap(true);
				c.drawBitmap(state_transitions_bmp, src, dst, paint);
				paint.setFilterBitmap(false);

				paint.setStrokeWidth(density);
				paint.setStyle(Style.FILL);
				paint.setColor(0xFFFFFFFF);
				paint.setTextSize(12.0f*density);
				paint.setTextAlign(Align.LEFT);
				c.drawText(title, x, y + ty - (paint.ascent() - paint.descent())/2, paint);
				
				paint.setTextAlign(Align.CENTER);
				paint.setTextSize(strans_vis_h * 0.15f);
				for(int i=0; i<txt_xy.length; i++) {
					c.drawText("" + histogram[i], 
						x + pad + txt_xy[i][0], 
						y + pad + txt_xy[i][1] + ty - (paint.ascent() + paint.descent())/2, paint);
				}
			}
		}
		public void computeBitmapZoom() {
			float ratio1 = (H - 2*pad - txt_H * density) * 1.0f / state_transitions_bmp.getHeight();
			float ratio2 = (W - 2*pad) * 1.0f / state_transitions_bmp.getWidth();
			if(ratio1 > ratio2) {
				ratio1 = ratio2;
			}
			strans_vis_w = (int)(state_transitions_bmp.getWidth() * ratio1);
			strans_vis_h = (int)(state_transitions_bmp.getHeight()* ratio1);
			strans_x = pad + (W - strans_vis_w)/2;
			strans_y = (int)(pad + (H - strans_vis_h - txt_H*density)/2);
			strans_zoom = ratio1;

			int num_states = TommyMastery.MASTERY_COORDS.length;
			for(int i=0; i<num_states; i++) {
				txt_xy[i][0] = strans_x + (int)(TommyMastery.MASTERY_COORDS[i][0] / 289.0f * strans_vis_w);
				txt_xy[i][1] = strans_y + (int)(TommyMastery.MASTERY_COORDS[i][1] / 116.0f * strans_vis_h);
			}
			need_redraw = true;
		}
	}
	
	// Shall have only 1 instance.
	// Also, shows intro message and outro message
	//
	//  ---------------------+-------------------+----------------------
	//    INTRO MESSAGE      |                   |  OUTRO MESSAGE
	//    TITLE              | MEASURES          |  TIME TAKEN
	//    #MEAS, #NOTES      |                   |  CORRECT/ERRONEOUS
	//  ---------------------+-------------------+----------------------
	//
	class RecycleArea {
		final static int TRIPLECLICK_WINDOW = 300; // 300 ms
		ArrayList<Integer> tiles_x = new ArrayList<Integer>();
		boolean need_redraw = false;
		
		int W, H, x, y, touchx, touchy, touchid, deltax, deltay, touch_xbegin, touch_count;
		float vel_x;
		long last_touch_millis;
		boolean is_inertia;
		
		private float score_x_offset; // 自譜子的bmp的x=score_x_offset處開始顯示。
		int score_x_offset_max, score_x_offset_min;
		float zoom_y, zoom_0, zoom_1;
		int next_zoom_idx = 1;
		float zoom_x, zoom_x_start, zoom_x_end;

		final static int DELTA_X_WINDOW_SIZE = 3; // # FRAMES
		long window_timestamps[] = new long[DELTA_X_WINDOW_SIZE];
		int  window_deltays[]  = new int[DELTA_X_WINDOW_SIZE];
		int window_idx = 0;

		private void addWindowEntry(long timestamp, int deltax) {
			window_deltays[window_idx] = deltax;
			window_timestamps[window_idx] = timestamp;
			window_idx ++;
			if(window_idx >= DELTA_X_WINDOW_SIZE) { window_idx = 0; }
		}
		
		private void clearWindowEntries() {
			for(int i=0; i<DELTA_X_WINDOW_SIZE; i++) {
				window_deltays[i] = 0;
				window_timestamps[i] = 0;
			}
			window_idx = 0;
		}
		
		private float getWindowVelXPerMilliSecond() {
			int widx = window_idx - 1;
			if(widx < 0) widx = widx + DELTA_X_WINDOW_SIZE;
			long max_tstamp = window_timestamps[widx], min_tstamp = max_tstamp;
			int sum_dy = window_deltays[widx];			
			for(int j=0; j<DELTA_X_WINDOW_SIZE; j++) {
				widx --;
				if(widx < 0) widx += DELTA_X_WINDOW_SIZE;
				
				if(window_deltays[widx] == 0) break;
				else {
					long ts = window_timestamps[widx];
					if(ts >= max_tstamp) {
						break;
					} else {
						if(ts < min_tstamp) {
							min_tstamp = ts;
							sum_dy += window_deltays[widx];
						}
					}
				}
			}
			long millis = max_tstamp - min_tstamp;
			if(millis == 0) return 0;
			return (float) (sum_dy * 1.0f / (max_tstamp - min_tstamp) * Math.pow(0.5, millis*1.0f/40.0f));
		}
		
		
		// Intro and Outro
		// UNIT: SCREEN
		int intro_width, outro_width;
		
		// Animation
		float zoom_start, zoom_end;
		float score_x_offset_start, score_x_offset_end;
		long anim_begin_millis, zoom_begin_millis;
		static final int ANIM_DURATION = 500; // 500 ms
		boolean is_in_fling = false, is_in_zooming = false;
		
		public RecycleArea(int _W, int _H) {
			initLayout(_W, _H);
			x = 0; y = 0; vel_x = 0; touchid = -1;
			intro_width = W; outro_width = W;
			touch_count = 0;
			Log.v("RecycleArea", String.format("W=%d H=%d", W, H));
		}
		
		void initLayout(int _W, int _H) {
			zoom_0  = zoom_y = 1.0f * AREA1_HGT / AREA1_bitmap_hgt0;
			zoom_1 = zoom_0 * 0.5f;
			if(next_zoom_idx == 1) {
				zoom_x = zoom_0;
			} else zoom_x = zoom_1;
			W = _W; H = _H;
		}
		
		public void toggleZoom() {
			float zm;
			if(next_zoom_idx == 1) zm = zoom_1;
			else zm = zoom_0;
			next_zoom_idx = 1 - next_zoom_idx;
			score_x_offset_min = (int)(-intro_width / zoom_0); // This doesn't change with zoom0 or zoom1
			startAnimZoomTo(zm);
		}

		void computeXPositions() {
			tiles_x.clear();
			int x = 0;
			for(int i=0; i<num_measures; i++) {
				tiles_x.add(x);
				x = x + bitmap_helper.getTileBitmapWidth(0, i);
			}
			score_x_offset_max = x;
			score_x_offset_min = (int)(-intro_width / zoom_x);
		}
		
		void setXPositionToLeftMost() {
			recycle_area_score_x_offset = score_x_offset = score_x_offset_min;
		}
		
		void getTileXYOffsetZoom(int idx_staff, int idx_measure, int[] xy) {
			int x = tiles_x.get(idx_measure);
			int y = 0;
			for(int i=0; i<idx_staff; i++) {
				y += bitmap_helper.getTileBitmapHeight(i, 0);
			}
			xy[0] = x; xy[1] = y;
		}
		
		private void do_draw(Canvas c, int x_begin, int idx_measure_begin, boolean is_draw_shadow) {
			int x = x_begin, idx_measure = idx_measure_begin, idx_staff = 0;
			while(x < W && idx_measure < num_measures) {
				Bitmap bmp = bitmap_helper.getTileBitmap(idx_staff, idx_measure);
				int dx = (int)(measureWidths.get(idx_measure)*zoom_x),
						dy = (int)(measureHeights.get(idx_staff)*zoom_y);
				
				if(!is_draw_shadow) {
					if(idx_staff == curr_hl_staff && idx_measure == curr_hl_measure) {
						paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
						paint.setStyle(Style.STROKE);
						paint.setStrokeWidth(density * 2.0f);
						c.drawRect(x+2, y+2, x+dx-2, y+dy-2, paint);
					}
				}
				
				if(measures_status.get(idx_staff).get(idx_measure) == MeasureStatus.IN_RECYCLE) {
					if(is_draw_shadow) {
						/*
						Rect b = new Rect(
								(int) (x-19*density),
								(int) (y-16*density), 
								(int) (x+dx+23*density), 
								(int) (y+dy+24*density)
							);
						shadow1.setBounds(b);
						shadow1.setTargetDensity((int)(density * 160));
						shadow1.draw(c);
						b = null;
						*/
					} else {
						src.set(0, 0, bmp.getWidth(), bmp.getHeight());
						dst.set(x, y, x+dx, y+dy);
						NinePatchDrawable bk;
						if(idx_staff == 0) {
							bk = TommyConfig.getCurrentStyle().tile_bk_upper;
						} else bk = TommyConfig.getCurrentStyle().tile_bk_lower;
						bk.setBounds(dst);
						bk.setTargetDensity(c);
						bk.draw(c);
						c.drawBitmap(bmp, src, dst, bmp_paint);
					}
				}
				
				idx_staff++;
				y = y + dy;
				if(idx_staff >= num_staffs) {
					idx_measure ++;
					idx_staff = 0;
					y = 0;
					x = x + dx;
				}
			}
		}
		
		public boolean isIntroVisible() {
			return score_x_offset < 0;
		}
		
		public boolean isOutroVisible() {
			return (score_x_offset > score_x_offset_max - W/zoom_x);
		}
		
		public void draw(Canvas c) {
			paint.setColor(TommyConfig.getCurrentStyle().background_color);
			paint.setStyle(Style.FILL);
			paint.setFilterBitmap(true);
			c.drawRect(0, 0, W, H, paint);
			
			paint.setTextAlign(Align.LEFT);
			paint.setFilterBitmap(true);
			int x = 0, idx_measure = 0;
			
			// Fast forward to the index
			float score_x = 0;
			while(true) {
				float delta = bitmap_helper.getTileBitmapWidth(0, idx_measure);
				if(score_x + delta > score_x_offset) break;
				score_x = score_x + delta;
				idx_measure++;
				if(idx_measure == num_measures - 1) break;
			}
			x = (int)((score_x - score_x_offset) * zoom_x);
			
//			do_draw(c, x, idx_measure, true); // Shadows // With new NinePatch, this can be decommissioned!!
			do_draw(c, x, idx_measure, false); // Shadows
			
			
			// Intro and outro messages
			{
				paint.setColor(Color.WHITE);
				paint.setStyle(Style.STROKE);
				paint.setTextAlign(Align.LEFT);
				paint.setAntiAlias(true);
				final int pad = (int)(density * 3.0f);
				
				long delta = elapsed_millis;
				float seconds_elapsed = delta / 1000.f;
				
				if(isIntroVisible()) {
					NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;
					int intro_x0 = (int)(-score_x_offset * zoom_x - intro_width);
					dst.set(intro_x0, y, intro_x0+intro_width, y+H);
					bk.setBounds(dst);
					bk.draw(c);
					paint.setStrokeWidth(1.0f*density);
					
					{
						float speculative_txt_size = H / 6.0f;
						paint.setTextSize(speculative_txt_size);
						paint.setStyle(Style.FILL);
						int y1 = y + pad + (int)(12*density);
						String s = midi_title;
						paint.getTextBounds(s, 0, s.length(), src);
						if(src.width() > intro_width) {
							float txtscale = intro_width * 1.0f / src.width();
							paint.setTextScaleX(txtscale);
							
						}
						paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
						c.drawText(s, intro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
						paint.setTextScaleX(1.0f);
						y1 = y1 + src.bottom - src.top;
						
						s = String.format(str_quiz_measures, num_measures);
						paint.getTextBounds(s, 0, s.length(), src);
						c.drawText(s, intro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
						y1 = y1 + src.bottom - src.top;

						s = String.format(str_quiz_seconds, seconds_elapsed);
						paint.getTextBounds(s, 0, s.length(), src);
						c.drawText(s, intro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
						y1 = y1 + src.bottom - src.top;

						s = String.format(str_quiz_num_clicks, num_right_clicks, num_wrong_clicks);
						paint.getTextBounds(s, 0, s.length(), src);
						c.drawText(s, intro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
					}
				}
				if(isOutroVisible()) {
					NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;
					int outro_x0 = (int)((score_x_offset_max - score_x_offset) * zoom_x);
					bk.setBounds(outro_x0, y, outro_x0+outro_width, y+H);
					bk.draw(c);
					paint.setTextSize(H/6.0f);
					paint.setStyle(Style.FILL);

					int y1 = y + pad + (int)(12*density);
					String s;
					if(game_state == GameState.PLAYING) {
						s = String.format(str_quiz_tiles_to_go, num_tiles_total, num_tiles_hidden);
					} else if(game_state == GameState.NOT_STARTED) {
						s = please_make_a_choice;
					} else {
						s = well_done;
					}
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint.getTextBounds(s, 0, s.length(), src);
					c.drawText(s, outro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
					y1 = y1 + src.bottom - src.top;
					
					s = String.format(str_quiz_seconds, seconds_elapsed);
					paint.getTextBounds(s, 0, s.length(), src);
					c.drawText(s, outro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
					y1 = y1 + src.bottom - src.top;
					
					s = String.format(str_quiz_num_clicks, num_right_clicks, num_wrong_clicks);
					c.drawText(s, outro_x0 + pad, y1 + (src.bottom-src.top)/2, paint);
					
				}
			}
			
			if(DEBUG) {
				if(need_redraw) {
					paint.setColor(Color.RED);
				} else {
					paint.setColor(Color.GREEN);
				}
				paint.setStyle(Style.STROKE);
				paint.setStrokeWidth(density);
				c.drawRect(x, y, x+W, y+H, paint);
			}
			
			need_redraw = false;
		}
		public boolean panScoreByScreenX(float dx) {
			boolean is_oob = false;
			score_x_offset -= dx / zoom_x;
			if(score_x_offset < score_x_offset_min) {
				score_x_offset = (int) (score_x_offset_min);
				is_oob = true;
			}
			if(score_x_offset > score_x_offset_max) {
				score_x_offset = (int) (score_x_offset_max);
				is_oob = true;
			}
			return is_oob;
		}
		
		public boolean isMeasureOutOfSight(int midx) {
			int x=0;
			int shown_score_xmin = (int)(score_x_offset);
			int shown_score_xmax = (int)(score_x_offset + W/zoom_x);
			for(int i=0; i<midx; i++) x = x + bitmap_helper.getTileBitmapWidth(0, i);
			int xmin = (int)(x), xmax = (int)(x + bitmap_helper.getTileBitmapWidth(0, midx));
			if(xmax < shown_score_xmin || xmin > shown_score_xmax) return true;
			return false;
		}
		
		public void centerAtMeasure(int midx) {
			int x = 0;
			for(int i=0; i<num_measures; i++) {
				int w = bitmap_helper.getTileBitmapWidth(0, i);
				if(i == midx) {
					x = x + w/2; break;
				}
				x = x + w;
			}
			int anim_to = (int)(x - (W/2.0f/zoom_x));
			startAnimMoveTo(anim_to);
		}
		
		public void updateInertialMovement(long millis) {
			if(!is_inertia) {
				return;
			}
			need_redraw = true;
			vel_x = vel_x * 0.95f;
			if(Math.abs(vel_x) < 0.3f*density) {
				vel_x = 0.0f;
				is_inertia = false;
			} else {
				if(panScoreByScreenX(vel_x)) {
					vel_x = 0.0f; // If true then Out Of Bounds.
				}
			}
		}
		
		void animateScrollOneMeasureToTheRight() {
			if(is_in_fling) {
				is_in_fling = false;
				score_x_offset = score_x_offset_end;
			}
			int rightmost = (int)(score_x_offset + (width + 10)/ zoom_x);
			int idx = 0, x = 0;
			while(idx < measureWidths.size()) {
				int x_next = x + measureWidths.get(idx);
				if(x_next > rightmost) {
					startAnimMoveTo(x_next - width / zoom_x);
					break;
				}
				x = x_next;
				idx ++;
			}
			if(idx == measureWidths.size()) {
				startAnimMoveTo(x);
			}
		}
		
		void animateScrollOneMeasureToTheLeft() {
			if(is_in_fling) {
				is_in_fling = false;
				score_x_offset = score_x_offset_end;
			}
			int leftmost = (int)(score_x_offset - 10/zoom_x);
			int idx = 0, x = 0;
			while(idx < measureWidths.size()) {
				int x_next = x + measureWidths.get(idx);
				if(x_next >= leftmost) {
					startAnimMoveTo(x);
					break;
				}
				x = x_next;
				idx ++;
			}
			if(idx == 0) {
				startAnimMoveTo(-intro_width / zoom_x);
			}
		}
		
		private void startAnimMoveTo(float score_x_target) {
			score_x_offset_start = score_x_offset;
			score_x_offset_end = score_x_target;
			if(score_x_offset_end > score_x_offset_max) {
				score_x_offset_end = score_x_offset_max;
			}
			is_in_fling = true;
			anim_begin_millis = System.currentTimeMillis();
			vel_x = 0;
		}
		
		private void startAnimZoomTo(float zm) {
			zoom_x_end = zm;
			zoom_x_start = zoom_x;
			zoom_begin_millis = System.currentTimeMillis();
			is_in_zooming = true;
		}
		
		void update(long millis) {
			if(is_inertia) {
				this.updateInertialMovement(millis);
			}
			
			if(!is_in_fling) {
				if(touchid != -1) {
					panScoreByScreenX(deltax);
					deltax = deltay = 0;
				}
			} else {
				need_redraw = true;
				long delta = millis - anim_begin_millis;
				if(delta > ANIM_DURATION) {
					is_in_fling = false;
				} else {
					float completion = delta * 1.0f / ANIM_DURATION;
					float t = 1.0f - (1.0f - completion) * (1.0f - completion);
					int x = (int)(score_x_offset_end * t + 
							score_x_offset_start   * (1.0f - t));
					score_x_offset = x;
				}
			}
			
			if(is_in_zooming) {
				need_redraw = true;
				long delta = millis - zoom_begin_millis;
				if(delta > ANIM_DURATION) {
					is_in_zooming = false;
				}
				float completion = delta*1.0f / ANIM_DURATION;
				float t = 1.0f - (1.0f - completion) * (1.0f - completion);
				zoom_x = zoom_x_start * (1.0f - t) + zoom_x_end * t;
				score_x_offset_min = (int)(-intro_width / zoom_x);
			}
			
			if(millis - last_touch_millis > TRIPLECLICK_WINDOW) { touch_count = 0; }
			if(game_state != GameState.FINISHED) {
				if(isIntroVisible() || isOutroVisible()) {
					need_redraw = true;
				}
			}
		}
		boolean intersect(int tx, int ty) {
			if(tx > x && tx < x+W && ty > y && ty < y+H) return true;
			return false;
		}
		
		boolean touchDown(int id, int tx, int ty) {
			if(intersect(tx, ty)) {
				touchx = touch_xbegin = tx; touchy = ty;
				touchid = id;
				vel_x = 0;
				last_touch_millis = System.currentTimeMillis();
				addWindowEntry(last_touch_millis, 0);
				touch_count ++;
				if(touch_count == 3) {
					toggleZoom();
				}
				return true;
			}
			return false;
		}
		
		boolean touchMove(int id, int x, int y) {
			need_redraw = true;
			if(touchid == -1) {
				;
			} else if(touchid == id) {
				deltax += x - touchx;
				addWindowEntry(System.currentTimeMillis(), x - touchx);
				touchx = x;
				deltay += y - touchy;
				touchy = y;
			} else {
				;
			}
			return true;
		}
		
		boolean touchUp(int id) {
			if(id == touchid) {
				this.vel_x = 16.0f * getWindowVelXPerMilliSecond();
				Log.v("Tommy2 RecycleArea Velx", ""+this.vel_x);
				addWindowEntry(System.currentTimeMillis(), 0); // To account for delay!
				deltax = 0;
				touchid = -1;
				this.is_inertia = true;
				Log.v("RecycleArea TouchUp", "vel_x="+vel_x);
				need_redraw = true;
				return true;
			}
			return false;
		}

		public void setScoreXOffset(float x) {
			if(x < score_x_offset_min) x = score_x_offset_min;
			if(x > score_x_offset_max) x = score_x_offset_max;
			score_x_offset = x;
		}
	};
	
	private void layout1() {
		AREA1_zoom_x = AREA1_zoom_y = 1.0f * AREA1_HGT / AREA1_bitmap_hgt0;
		recycle_area = new RecycleArea(width, AREA1_HGT); //, AREA1_zoom_x, AREA1_zoom_y);
		// 2 times the num of pixels * color depth (16b)
		BITMAP_MEM_BUDGET = (int)(2 * 2 *width * height * (1.0f*AREA1_bitmap_hgt0 / AREA1_HGT));
		recycle_area.computeXPositions();
		recycle_area.setXPositionToLeftMost();
		separator = new FloatingButton(width, (int)(SEPARATOR_HEIGHT*density), 0, height/3,
				FloatingButtonType.SEPARATOR);
		float separator_hgt = SEPARATOR_HEIGHT*density;
		float vert_btn_width = NAVIGATION_BUTTON_WIDTH*density;
		int height_btns = (int)(height - AREA1_HGT - separator_hgt);  
		
		left_button = new FloatingButton((int)(vert_btn_width), height_btns, 0, (int)(AREA1_HGT+separator_hgt),
				FloatingButtonType.LEFT_BUTTON);
		right_button= new FloatingButton((int)(vert_btn_width), height_btns, (int)(width-vert_btn_width), 
				(int)(AREA1_HGT + separator_hgt), FloatingButtonType.RIGHT_BUTTON);
		separator.bk = left_button.bk = right_button.bk = TommyConfig.getCurrentStyle().background_separator;
		
		final int N_COLS = 4, N_ROWS = 2;
		final int TILEW = (int)((width-2*vert_btn_width)/N_COLS), TILEH = (int)((height*2/3 - SEPARATOR_HEIGHT - SEPARATOR_HEIGHT*density)/2);
		tiles.clear();
		int tile_id = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = (int)(vert_btn_width + j * TILEW), y = i * TILEH + height/3;
				SelectionTile st = new SelectionTile(x, y, TILEW, TILEH);
				st.id = tile_id;
				tile_id ++;
				tiles.add(st);
			}
		}
		
		{
			sttiles.clear();
			int x0 = tiles.get(0).x, y0 = tiles.get(0).y, w0 = 2*tiles.get(0).W, h0 = 2*tiles.get(0).H;
			StateTransitionTile stt0 = new StateTransitionTile(w0, h0, x0, y0, mastery_histogram_before);
			stt0.title = "Before";
			StateTransitionTile stt1 = new StateTransitionTile(w0, h0, x0+w0, y0, mastery_histogram_after);
			stt1.title = "After";
			sttiles.add(stt0);
			sttiles.add(stt1);
		}
		
		bitmap_helper.bitmap_zoom_x = 1.0f;
		bitmap_helper.bitmap_zoom_y = 1.0f;
		adjustSizeLayout1();
	}
	
	private void adjustSizeLayout1() {

		if(AREA1_HGT < 30) AREA1_HGT = 30;
		if(AREA1_HGT > height-30) AREA1_HGT = height-30;
		
		recycle_area.initLayout(width, AREA1_HGT);
		recycle_area.computeXPositions();
		separator.y = AREA1_HGT;
		final int N_COLS = 4, N_ROWS = 2;
		float separator_hgt = SEPARATOR_HEIGHT * density;
		float vert_btn_width = NAVIGATION_BUTTON_WIDTH * density;
		left_button.H = right_button.H = (int)(height - AREA1_HGT - separator_hgt);
		left_button.y = right_button.y = (int)(AREA1_HGT + separator_hgt);
		final int TILEW = (int)(width-2*vert_btn_width)/N_COLS, TILEH = (int)((height - AREA1_HGT - separator_hgt)/2);
		int idx = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = (int)(vert_btn_width + j*TILEW), y = i*TILEH + (int)(AREA1_HGT + separator_hgt);
				tiles.get(idx).initLayout(x, y, TILEW, TILEH);
				idx++;
			}
		}
		{
			int y0 = tiles.get(0).y, h0 = tiles.get(0).H * 2;
			StateTransitionTile stt0 = sttiles.get(0);
			stt0.y = y0; stt0.H = h0;
			stt0.computeBitmapZoom();
			StateTransitionTile stt1 = sttiles.get(1);
			stt1.y = y0; stt1.H = h0;
			stt1.computeBitmapZoom();
		}
	}
	
	// Vertical
	private void layout2() {
		AREA1_zoom_x = AREA1_zoom_y = 1.0f * AREA1_HGT / AREA1_bitmap_hgt0;
		recycle_area = new RecycleArea(width, (int)(AREA1_HGT)); //, AREA1_zoom_x, AREA1_zoom_y);
		BITMAP_MEM_BUDGET = (int)(2 * 2 * width * height * (1.0f*AREA1_bitmap_hgt0 / AREA1_HGT));
		recycle_area.computeXPositions();
		recycle_area.setXPositionToLeftMost();
		float separator_hgt = SEPARATOR_HEIGHT*density;
		
		int btn_thickness = (int)(NAVIGATION_BUTTON_WIDTH * density);
		
		separator = new FloatingButton(width, (int)(separator_hgt), 0, AREA1_HGT,
				FloatingButtonType.SEPARATOR);
		
		
		left_button = new FloatingButton(width/2, btn_thickness, 
				0, (int)(height - btn_thickness),
				FloatingButtonType.LEFT_BUTTON);
		right_button= new FloatingButton(width/2, btn_thickness,
				width/2, (int)(height - btn_thickness), 
				FloatingButtonType.RIGHT_BUTTON);
		
		left_button.bk = right_button.bk = separator.bk = TommyConfig.getCurrentStyle().background_separator;
		final int N_COLS = 2, N_ROWS = 4;
		final int TILEW = (int)(width/N_COLS), 
				TILEH = (int)((height-AREA1_HGT-SEPARATOR_HEIGHT*density-btn_thickness)/N_ROWS);
		tiles.clear();
		int tile_id = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = (int)(j * TILEW), y = i * TILEH + (int)(AREA1_HGT + separator_hgt);
				SelectionTile st = new SelectionTile(x, y, TILEW, TILEH);
				st.id = tile_id;
				tile_id ++;
				tiles.add(st);
			}
		}
		
		{
			sttiles.clear();
			int x0 = tiles.get(0).x, y0 = tiles.get(0).y, w0 = 2*tiles.get(0).W, h0 = 2*tiles.get(0).H;
			StateTransitionTile stt0 = new StateTransitionTile(w0, h0, x0, y0, mastery_histogram_before);
			stt0.title = "Before";
			StateTransitionTile stt1 = new StateTransitionTile(w0, h0, x0, y0+h0, mastery_histogram_after);
			stt1.title = "After";
			sttiles.add(stt0);
			sttiles.add(stt1);
		}
		
		
		bitmap_helper.bitmap_zoom_x = 1.0f;
		bitmap_helper.bitmap_zoom_y = 1.0f;
	}
	private void adjustSizeLayout2() {
		if(AREA1_HGT < 30) AREA1_HGT = 30;
		if(AREA1_HGT > height-30) AREA1_HGT = height-30;
		recycle_area.initLayout(width, AREA1_HGT);
		recycle_area.computeXPositions();
		separator.y = AREA1_HGT;
		final int N_COLS = 2, N_ROWS = 4;
		
		float separator_hgt = SEPARATOR_HEIGHT*density;
		float button_thickness = NAVIGATION_BUTTON_WIDTH*density;
				
		int TILEW = (int)((width)/N_COLS), 
				TILEH = (int)((height-AREA1_HGT-separator_hgt-button_thickness)/N_ROWS);
		int idx = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = (int)(j*TILEW), y = i*TILEH + (int)(AREA1_HGT + separator_hgt);
				tiles.get(idx).initLayout(x, y, TILEW, TILEH);
				idx ++;
			}
		}

		{
			int y0 = tiles.get(0).y, h0 = tiles.get(0).H * 2;
			StateTransitionTile stt0 = sttiles.get(0);
			stt0.y = y0; stt0.H = h0;
			stt0.computeBitmapZoom();
			StateTransitionTile stt1 = sttiles.get(1);
			stt1.y = y0 + h0; stt1.H = h0;
			stt1.computeBitmapZoom();
		}
	}
	
	int getMeasureStatusCountByType(MeasureStatus s) {
		int ret = 0;
		for(ArrayList<MeasureStatus> alms : measures_status) {
			for(MeasureStatus ms : alms) {
				if(ms == s) ret ++; 
			}
		}
		return ret;
	}
	
	boolean getRandomMeasureByStatus(MeasureStatus status, int[] sidmid) {
		if(getMeasureStatusCountByType(status) == 0) return false;
		while(true) {
			int sid = rnd.nextInt(num_staffs);
			int mid = rnd.nextInt(num_measures);
			if(measures_status.get(sid).get(mid) == status && mid != 0) { // Exclude first measure
				sidmid[0] = sid;
				sidmid[1] = mid;
				break;
			}
		}
		return true;
	}
	
	int getTileUIDFromMidSid(int measure_idx, int staff_idx) {
		return staff_idx + num_staffs * measure_idx;
	}
	
	void getTileMidSidFromUID(int uid, int[] midsid) {
		int mid = uid / num_staffs;
		int sid = uid - num_staffs*mid;
		midsid[0] = mid;
		midsid[1] = sid;
	}
	
	void startGame() {
		if(game_state == GameState.NOT_STARTED) {
			game_state = GameState.PLAYING;
			initGamePlay();
			populateSelectionTiles();
			recycle_area.centerAtMeasure(curr_hl_measure);
		}
	}
	
	// 出题
	void initGamePlay() {
		num_tiles_hidden = (int)(blanks_ratio * (num_tiles_total - 2)); // First two are always hidden
		
		ArrayList<Integer> tmpuids = new ArrayList<Integer>();
		for(int i=0; i<num_tiles_hidden; i++) {
			while(getMeasureStatusCountByType(MeasureStatus.IN_RECYCLE) > 0) {
				int sidmid[] = {-1, -1};
				getRandomMeasureByStatus(MeasureStatus.IN_RECYCLE, sidmid);
				int mid = sidmid[1], sid = sidmid[0], uid = getTileUIDFromMidSid(mid, sid);
				if(!tmpuids.contains(uid) && mid != 0) {
					measures_status.get(sid).set(mid, MeasureStatus.HIDDEN);
					tmpuids.add(uid);
					break;
				}
			}
		}
		
		Collections.sort(tmpuids);
		// Shuffle first [tile.length] hidden tiles only.
		{
			int num_deals = ((tmpuids.size() - 1) / tiles.size()) + 1;
			int idx_tmpuid = 0;
			for(int didx = 0; didx < num_deals; didx++) {
				ArrayList<Integer> this_deals_id = new ArrayList<Integer>();
				int this_deal_size = (idx_tmpuid + tiles.size() < tmpuids.size()) ?
						tiles.size() : tmpuids.size() - idx_tmpuid;
				for(int j=0; j<this_deal_size; j++) {
					while(true) {
						int uid_idx = rnd.nextInt(this_deal_size) + idx_tmpuid;
						int uid     = tmpuids.get(uid_idx);
						if(this_deals_id.contains(uid) == false) {
							this_deals_id.add(uid);
							break;
						}
					}
				}
				Log.v("InitGamePlay", String.format("Deal %d, %d tiles", deck_uids.size(), this_deals_id.size()));
				deck_uids.add(this_deals_id);
				idx_tmpuid += this_deal_size;
			}
		}
		getFirstHighlightStaffMeasure();
		elapsed_millis = 0;
		is_inited = true;
	}
	
	private void populateSelectionTiles() {
		Log.v("TommyView2::populateSlectionTiles", tiles.size()+" tiles");
		for(SelectionTile st : tiles) {
			st.setRandomHiddenMeasure();
		}
	}
	
	// 2014-03-01: initMidiFile现在和initUI是绑在一起的，也就是说
	// 这两个init都完成时，才会有is_inited = true;
	// 2014-03-06:
	// 但是其实是不应该绑在一起的
	private void initMidiFile() {
		MidiFile midifile = new MidiFile(midi_data, midi_title);
		sheet = TommyView2.sheet1 = new SheetMusic(ctx);
		sheet.is_tommy_linebreak = true; // Otherwise NullPointer Error
		sheet.init(midifile, options);
		sheet.setVisibility(View.GONE);
		sheet.tommyFree();
		CRC32 crc = new CRC32();
		crc.update(midi_data);
		checksum = crc.getValue();
		
		sheet.ComputeMeasureHashesNoLineBreakNoRender();
		measureHashes = sheet.getMeasureHashes();
		measureHeights = sheet.getMeasureHeights();
		measureWidths = sheet.getMeasureWidths();
		num_notes = sheet.getNumNotes();
		
		// Construct Boolean Array
		{
			measures_status.clear();
			num_measures = sheet.getNumMeasures();
			num_staffs   = sheet.getNumStaffs();
			num_tiles_total = 0;
			for(int i=0; i<num_staffs; i++) {
				ArrayList<MeasureStatus> blah = new ArrayList<MeasureStatus>();
				for(int j=0; j<num_measures; j++) {
					blah.add(MeasureStatus.IN_RECYCLE);
					num_tiles_total ++;
				}
				measures_status.add(blah);
			}
		}
		
		// Other stuff
		{
			num_wrong_clicks = num_right_clicks = 0;
			for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
				highscores.add(new ArrayList<Long>());
				timestamps.add(new ArrayList<Long>());
				right_clicks_history.add(new ArrayList<Integer>());
				wrong_clicks_history.add(new ArrayList<Integer>());
			}
		}

		String cksm = String.format("%x", checksum);
		String hs_sz = prefs_highscores.getString(String.format("%x_HS", checksum), "");
		Log.v("initMidiFile", "Historical Scores: " + hs_sz);
		TommyConfig.populateHSTSArraysFromJSONString(highscores, timestamps, right_clicks_history, wrong_clicks_history, hs_sz);
		num_times_played = prefs_quizcount.getInt(String.format("%x", checksum), 0);

		mastery_histogram_before = new int[TommyMastery.MASTERY_STATE_SCORES.length];
		mastery_histogram_after  = new int[TommyMastery.MASTERY_STATE_SCORES.length];

		sheet.getVisibleActualTrackIndices(actual_track_idx);
		
		int NT = sheet.getActualNumberOfTracks();
		{
			String mssz = prefs_quizstats.getString(cksm+"_mastery_states", "");
			for(int i=0; i<NT; i++) {
				measure_mastery_states.add(new ArrayList<Integer>());
				masteries.add(new ArrayList<TommyMastery>());
			}
			TommyConfig.populateMasteryStateArrayFromJSONString(NT, num_measures, measure_mastery_states, mssz);

			for(int i=0; i<NT; i++) { // staff index
				boolean is_shown = true; // Is this staff shown? If no then histogram is not incremented
				if(actual_track_idx.indexOf(i) == -1) is_shown = false;
				for(int j=0; j<num_measures; j++) { // Ignore the first measure. measure index.
					int x = measure_mastery_states.get(i).get(j);
					masteries.get(i).add(new TommyMastery(x));
					if(j>0 && is_shown) mastery_histogram_before[x] ++;
				}
			}
		}
		
		bitmap_helper = new BitmapHelper(this, sheet, 1.0f, 1.0f);
		is_inited = true;
	}
	
	private void initUI() {
		Log.v("TommyView2::initUI", " W="+width + ", H="+height);
		// Prepare U.I. Elements.
		for(int i=0; i<touches.length; i++) touches[i] = new Vec2();
		for(int i=0; i<invdpads.length; i++) {
			invdpads[i] = new InvisibleDPad();
		}
		
		// Compute Zoom 1 and layout
		{
			int max_hgt = 0;
			for(int i=0; i<num_measures; i++) {
				int hgt = 0;
				for(int j=0; j<num_staffs; j++) {
					hgt = hgt + bitmap_helper.getTileBitmapHeight(j, i);
				}
				if(max_hgt < hgt) {
					max_hgt = hgt;
				}
			}
			
			AREA1_bitmap_hgt0 = max_hgt;
			
			if(width > height) {
				AREA1_HGT = (int)(height * 0.33f);
				curr_layout_id = 1;
				layout1();
			} else {
				AREA1_HGT = (int)(height * 0.25f);
				curr_layout_id = 2;
				layout2();
			}
		}
		// This is kind of like a callback function,
		//   the 
		if(TommyView2Activity.popupview != null) {
			TommyView2Activity.popupview.update();
		}
	}
	
	public TommyView2(Context context, Bundle icicle, byte[] _data, String _title, String _midi_uri_string, MidiOptions _options) {
		super(context);
		difficulty_1 = context.getResources().getString(R.string.difficulty_1);
		difficulty_2 = context.getResources().getString(R.string.difficulty_2);
		difficulty_3 = context.getResources().getString(R.string.difficulty_3);
		difficulty_4 = context.getResources().getString(R.string.difficulty_4);
		str_quiz_num_clicks = context.getResources().getString(R.string.quiz_num_clicks);
		str_quiz_seconds    = context.getResources().getString(R.string.quiz_seconds);
		str_quiz_tiles_to_go= context.getResources().getString(R.string.quiz_tiles_to_go);
		str_quiz_measures   = context.getResources().getString(R.string.quiz_measures);
		please_make_a_choice = context.getResources().getString(R.string.please_make_a_choice);
		well_done = context.getResources().getString(R.string.well_done);
		midi_data = _data; midi_title = _title; options = _options; midi_uri_string = _midi_uri_string;
		density = MidiSheetMusicActivity.density;
		ctx = context;
		prefs_highscores = ctx.getSharedPreferences("highscores", Context.MODE_PRIVATE);
		prefs_lastplayed = ctx.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
		prefs_quizcount  = ctx.getSharedPreferences("quizcounts", Context.MODE_PRIVATE);
		prefs_quizstats  = ctx.getSharedPreferences("quizstats",  Context.MODE_PRIVATE);
		prefs_finegrained =ctx.getSharedPreferences("finegrained",Context.MODE_PRIVATE);
		
		thread = new Thread(this);
		paint = new Paint();
		bmp_paint = new Paint();
		bmp_paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
		bmp_paint.setFilterBitmap(true);
		TommyConfig.init(ctx);
		if(icicle != null) {
			loadState(icicle);
		}
		if(!is_inited) {
			initMidiFile();
		}
		String cksm = String.format("%x", checksum);
		int NT = sheet.getActualNumberOfTracks();
		{
			String quiz_stat_sz = prefs_quizstats.getString(cksm, "");
			TommyConfig.populateQuizCoarseStatisticsFromJSONString(NT, num_measures, right_clicks_measures, wrong_clicks_measures,
				delays_measures, quiz_stat_sz);
			sheet.getVisibleActualTrackIndices(actual_track_idx);
		}

		// Fine-grained history
		// If this takes a couple of seconds, shall we move it to another updater_thread?
		{
			String fghist = prefs_finegrained.getString(cksm, "");
			boolean isok = TommyConfig.populateQuizFineStaticsFromJSONString(NT, num_measures, okclicks_history_f, millis_history_f, fghist);
			Log.v("TommyView2", "Load fine grained history = " + isok);
		}
		
		last_heap_memory_alloc = Debug.getNativeHeapAllocatedSize();

		if(state_transitions_bmp == null) 
			state_transitions_bmp = BitmapFactory.decodeResource(getResources(), R.drawable.statetransitions);
	}

	@Override
	public void run() {
		while(!is_freed) {
			if(is_running) {
				long last_millis = 0;
				while(true) {
					try {
						long curr_millis = System.currentTimeMillis();
						long delta = curr_millis - last_millis;
						/*// This doesn't make sense anymore since we moved from SurfaceView to View
						  // With a View, drawing is done by the main updater_thread, so the time measurement
						  // doesn't capture time spent in rendering anymore!
						if(delta < FRAME_DELAY) {
							Thread.sleep(FRAME_DELAY - delta);
						}
						*/
						if(frame_count > 1 && game_state == GameState.PLAYING) {
							elapsed_millis += delta;
						}
						last_millis = curr_millis;
						if(delta < FRAME_DELAY) {
							Thread.sleep(FRAME_DELAY - delta);
						}
						update(); // Sync-Locked.
						if(need_redraw) {
							for(SelectionTile st : tiles) st.prepareToDraw();
							postInvalidate();
						}
						if(!is_running) break; // fixed on 20140306
					} catch (Exception e) {}
				}
			} else {
				try{
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				} 
			}
		}
	}
	
	private void do_draw(Canvas c) {
		frame_count ++;
		c.drawColor(TommyConfig.getCurrentStyle().background_color);
		int w = this.getWidth();

		if(recycle_area != null) recycle_area.draw(c);
		if(separator    != null) separator.draw(c);
		if(left_button  != null) left_button.draw(c);
		if(right_button != null) right_button.draw(c);
		for(SelectionTile st : tiles) st.draw(c);
		
		for(StateTransitionTile stt : sttiles) stt.draw(c);
		
		synchronized(tiles_in_animation) {
			for(TileInAnimation tia : tiles_in_animation) tia.draw(c);
		}
		
		if(MidiSheetMusicActivity.DEBUG)
		{
			paint.setColor(Color.BLUE);
			paint.setTextAlign(Align.CENTER);
			paint.setAntiAlias(true);
			paint.setTextSize(12*density);
			paint.setStrokeWidth(1.0f);
			paint.setStyle(Style.FILL);
			String x = String.format("%d frames, Bitmap=%.2fM/%.2fM, NativeHeap=%.2fM", frame_count,
					bitmap_helper.getBytesConsumed() / 1048576.0f,
					BITMAP_MEM_BUDGET/1048576.0f,
					last_heap_memory_alloc / 1048576.0f
					);
			c.drawText(x, w/2, 12*density, paint);
		}
		need_redraw = false;
	}
	
	@Override
	protected void onDraw(Canvas c) {
		synchronized(this) { // To prevent canvas tearing.
			do_draw(c);
		}
		last_redraw_millis = System.currentTimeMillis();
	}
	
	private void update() {
		synchronized(this) {
			need_redraw = false;
			long millis = System.currentTimeMillis();
			
			if(recycle_area != null) {
				recycle_area.update(millis);
				need_redraw |= recycle_area.need_redraw;
			}
			
			Iterator<TileInAnimation> itr0 = tiles_in_animation.iterator();
			synchronized(tiles_in_animation) {
				for(; itr0.hasNext(); ) {
					need_redraw = true;
					TileInAnimation tia = itr0.next();
					tia.update(millis);
					if(tia.is_ended == true) {
						itr0.remove();
					}
				}
			}
			
			for(SelectionTile st : tiles) {
				st.update(millis);
				need_redraw |= st.need_redraw;
			}
			
			for(StateTransitionTile stt : sttiles) {
				need_redraw |= stt.need_redraw;
			}
			
			for(InvisibleDPad ivd : invdpads) {
				if(ivd != null) 
				ivd.update(millis); 
			}
			
			if(separator.isTouched()) {
				AREA1_HGT = pinch_begin_AREA1_HGT + separator.touchy - separator.touchy_begin;
				if(curr_layout_id == 1) adjustSizeLayout1();
				else if(curr_layout_id == 2) adjustSizeLayout2();
				separator.clearDeltaXY();
				recycle_area.panScoreByScreenX(0.0f); // Make sure not moving out of bounds.
			}
			
			need_redraw |= separator.need_redraw;
			need_redraw |= left_button.need_redraw;
			need_redraw |= right_button.need_redraw;
			
			int bytes_consumed = bitmap_helper.getBytesConsumed();
			if(bytes_consumed > BITMAP_MEM_BUDGET) {
				bitmap_helper.clearOutOfSightBitmaps();
			}
		}
	}
	
	
	/* ------------- Touch U.I. --------------- */
	Vec2[] touches = new Vec2[10];

	private void myTouchDown(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;

		// Step 3: Panning and zooming
		{
			for(int i=0; i<invdpads.length; i++) {
				if(invdpads[i].touchDown(id, x, y)) break;
			}
			
			// Pinch
			/*
			if(touches_on_bk == 2) {
				int ptouchid0 = -999, ptouchid1 = -999;
				for(int i=0; i<invdpads.length; i++) {
					if(invdpads[i].isTouched()) {
						ptouchid0 = i; break;
					}
				}
				for(int i=ptouchid0+1; i<invdpads.length; i++) {
					if(invdpads[i].isTouched()) {
						ptouchid1 = i; break;
					}
				}
				int x0 = invdpads[ptouchid0].touchx, x1 = invdpads[ptouchid1].touchx,
					y0 = invdpads[ptouchid0].touchy, y1 = invdpads[ptouchid1].touchy;
				if(recycle_area.intersect(x0, y0) && recycle_area.intersect(x1, y1)) {
					pinch_begin_x_avg = (x0 + x1)/2;
					pinch_begin_y_avg = (y0 + y1)/2;
					pinch_touchid0 = ptouchid0;
					pinch_touchid1 = ptouchid1;
					pinch_begin_AREA1_HGT = AREA1_HGT;
					is_resizing_recycling_area = true;
				}
			}
			*/
		}
		
		if(separator.touchDown(id, x, y)) return;
		if(left_button.touchDown(id, x, y)) return;
		if(right_button.touchDown(id, x, y)) return;
		if(recycle_area.touchDown(id, x, y)) return;
		
		// Step 2: Process music puzzle pieces
		{
			for(SelectionTile st : tiles) {
				if(st.touchDown(id, x, y)) return;
			}
		}
		
		
	}
	
	private void myTouchUp(int id) {
		touches[id].x = -1; touches[id].y = -1;
		for(InvisibleDPad idp : invdpads) {
			idp.touchUp(id);
			/*
			if(id == pinch_touchid1 || id == pinch_touchid0) {
				pinch_touchid1 = pinch_touchid0 = -999;
				is_resizing_recycling_area = false;
			}
			*/
		}
		for(SelectionTile st  : tiles) st.touchUp(id);
		recycle_area.touchUp(id);
		separator.touchUp(id);
		left_button.touchUp(id);
		right_button.touchUp(id);
	}
	
	private void myTouchMove(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;
		for(InvisibleDPad idp : invdpads) {
			idp.touchMove(id, x, y);
			
		}
		
		/*
		if(isPinchingRecycleArea()) {
			int y0 = invdpads[pinch_touchid0].touchy, y1 = invdpads[pinch_touchid1].touchy;
			int yavg = (y0 + y1)/2;
			AREA1_HGT = pinch_begin_AREA1_HGT + (yavg - pinch_begin_y_avg);
			if(curr_layout_id == 1) adjustSizeLayout1();
			else if(curr_layout_id == 2) adjustSizeLayout2();
		}
		*/
		
		for(SelectionTile st : tiles) { st.touchMove(id, x, y); }
		recycle_area.touchMove(id, x, y);
		separator.touchMove(id, x, y);
	}
	
	// Touch events
	@Override
	public boolean onTouchEvent(MotionEvent evt) {
		int action = evt.getActionMasked();
		switch(action) {
		case MotionEvent.ACTION_DOWN:
			myTouchDown(evt.getPointerId(0), (int)evt.getX(), (int)evt.getY());
			break;
		case MotionEvent.ACTION_UP:
			myTouchUp(evt.getPointerId(0));
			break;
		case MotionEvent.ACTION_POINTER_DOWN: {
			int idx = evt.getActionIndex();
			myTouchDown(evt.getPointerId(evt.getActionIndex()), (int)evt.getX(idx), (int)evt.getY(idx));
			break;
		}
		case MotionEvent.ACTION_POINTER_UP: {
			myTouchUp(evt.getPointerId(evt.getActionIndex()));
			break;
		}
		case MotionEvent.ACTION_MOVE:
			for(int idx=0; idx<evt.getPointerCount(); idx++) {
				int id = evt.getPointerId(idx);
				myTouchMove(id, (int)evt.getX(idx), (int)evt.getY(idx));
			}
		}
		return true;
	}
	
	private void onMeasureOrSurfaceCreated() {
		if(is_ui_created) return;
		is_ui_created = true;
		Log.v("TommyView2 surface Created", "W="+width+", H="+height);
		initUI();
		recycle_area.setScoreXOffset(recycle_area_score_x_offset);
//		if(!is_inited) {
	//		initGamePlay();
	//	}
		if(game_state == GameState.PLAYING) { // Restoring from a game 
			populateSelectionTiles();
		}
		is_running = true;

		thread.start();
	}
	
	public void free() {
		Log.v("TommyView2", "free() called");
	    for(ArrayList<Integer> ai : measureHashes) {
	    	ai.clear();
	    }
	    measureHashes.clear();
	    bitmap_helper.free();
	    sheet.free();
	    sheet = null;
	    TommyView2.sheet1 = null;
	    is_freed = true;
	}
	@Override 
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
	   int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
	   int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
	   this.setMeasuredDimension(parentWidth, parentHeight);
	   width = parentWidth; height = parentHeight;
	   Log.v("TommyView2", "onMeasure, W="+width+", H="+height);
	   super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	   setMeasuredDimension(width, height);
	   onMeasureOrSurfaceCreated();
	}
}
