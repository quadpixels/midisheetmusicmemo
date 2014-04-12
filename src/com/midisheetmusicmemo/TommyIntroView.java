// 2014-03-18 Should implement per-line rendering (combine multiple small BMPs into a large BMP
//            for faster rendering.
// 2014-03-19 Added a Bitmap Worker thread (such that rendering to buffer is handled in the background
//            thread, relieving the Main thread, making it easier to maintain a high frame rate.
// 2014-03-29 Adding "re-render a line Synchronously for instant updating".

package com.midisheetmusicmemo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.CRC32;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.midisheetmusicmemo.SheetMusic.Vec2;

public class TommyIntroView extends View implements Runnable {
	// boilerplate stuff
	int color_scheme_idx = 0;
	final static int FRAME_DELAY = 17;
	final static float VEL_Y_THRESH = 30;
	boolean is_freed = false;
	int LINE_WIDTHS[] = {320, 480, 640, 800};
	int SEPARATOR_HEIGHTS[] = {15, 13, 11, 10};
	int MINOR_SEPARATOR_HEIGHTS[] = {12, 10, 10, 10};
	int line_width_idx = LINE_WIDTHS.length - 1;
	TommyIntroActivity parent;
	private int width, height;
	Thread updater_thread, bmpworker_thread;
	Paint paint, bmp_paint;
	int frame_count, touches_on_bk;
	float density;
	Context ctx;
	private int BITMAP_MEMORY_BUDGET = 0;
	private static final int TEXT_HEIGHT = 13;
	boolean need_redraw, is_running, is_inited;
	public SheetMusic sheet;
	public MidiOptions options;
	public int last_sheet_playing_measure_idx = -1, curr_sheet_playing_measure_idx = -1;
	MeasureBitmapHelper bitmap_helper;
	LineBitmapHelper line_bitmap_helper;
	byte[] midi_data; String midi_title, midi_uri_string; MidiFile midifile;
	long checksum;
	boolean need_load_state = false;
	Bundle bundle;
	
	ArrayList<Integer>     line_midx_start = new ArrayList<Integer>();
	ArrayList<Integer>     line_heights    = new ArrayList<Integer>();

	ArrayList<MeasureTile> measure_tiles;
	ArrayList<Boolean>     measure_played; int num_playable_measures_to_play;
	ArrayList<Integer>     y_separators;
	ArrayList<Integer>     y_separators_heights; 
	boolean is_show_sub_separators = true;
	
	// !!! I'm using `measure' and `tile' interchangeably, which I shouldn't !
	ArrayList<ArrayList<Integer>> measureHashes;
	ArrayList<Integer> measureWidths;
	ArrayList<Integer> measureHeights;
	int num_notes;
	int num_measures, num_staffs, num_tiles_total;
	SharedPreferences prefs_highscores, 
		prefs_lastplayed, prefs_playcount, prefs_quizcount, prefs_quizstats, prefs_finegrained,
		prefs_colorscheme;
	ArrayList<ArrayList<Long>> highscores = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Long>> timestamps = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Integer>> right_clicks_history = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> wrong_clicks_history = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> right_clicks_measures= new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> wrong_clicks_measures= new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Long>> delays_measures= new ArrayList<ArrayList<Long>>();
	ArrayList<Integer> actual_staff_idx = new ArrayList<Integer>();
	
	ArrayList<ArrayList<ArrayList<Float>>> measure_masteries = new ArrayList<ArrayList<ArrayList<Float>>>();
	
	// This array is to be read from conf file
	ArrayList<ArrayList<Integer>> measure_mastery_states       = new ArrayList<ArrayList<Integer>>();
	
	ArrayList<ArrayList<ArrayList<Boolean>>> okclicks_history_f = new ArrayList<ArrayList<ArrayList<Boolean>>>();
	ArrayList<ArrayList<ArrayList<Long>>> millis_history_f = new ArrayList<ArrayList<ArrayList<Long>>>();
	ArrayList<ArrayList<TommyMastery>> masteries = new ArrayList<ArrayList<TommyMastery>>();
	
	enum GaugeMode {
		NONE,
		LAST_5_ATTEMPTS,
		OCCURRENCES,
		ACCURACY,
		DELAY,
		EFFECTIVE_DELAY,
		MASTERY_LEVEL,
	}
	GaugeMode gauge_mode = GaugeMode.ACCURACY;
	int curr_gauge_color; // There 3 kinds of metrics. Type 1 = desirable = the more the better, e.g. Accuracy.
					//                            Type 2 = undesirable = the more the worse, e.g. Response time and effective response time.
					//							  Type 3 = neutral = no desirability, e.g. occurrences.
	String curr_gauge_label;
	
	int num_played, num_quiz;
	Rect src, dst;

	// I assert this function is called only when the user rotates the device.
	public void saveState(Bundle bundle) {
		MeasureTile first_visible = getFirstMeasureIntersectsTopOfScreen();
		int midx = -1;
		if(first_visible != null) {
			midx = getFirstMeasureIntersectsTopOfScreen().measure_idx;
		}
		bundle.putInt("first_visible_measure_idx", midx);
		bundle.putInt("curr_gauge_mode", gauge_mode.ordinal());
		bundle.putInt("line_width_idx", line_width_idx);
		bitmap_helper.free();
	}
	
	// This is called after layout is complete!
	public void loadState(Bundle bundle) {
		int midx = bundle.getInt("first_visible_measure_idx");
		if(midx != -1) {
			for(MeasureTile mt : measure_tiles) {
				if(mt.staff_idx == 0 && mt.measure_idx == midx) {
					y_offset = mt.y; // Do not smooth scroll. Smooth scrolling here only confuses the user.
					need_redraw = true;
					break;
				}
			}
		}
		gauge_mode = GaugeMode.values()[(bundle.getInt("curr_gauge_mode", 0))];
		line_width_idx = bundle.getInt("line_width_idx", LINE_WIDTHS.length-1);
		setGaugeMode(gauge_mode);
	}

	HashSet<Integer> line_idxes_requests = new HashSet<Integer>();
	HashSet<Integer> tile_uids_requests  = new HashSet<Integer>();
	String bitmap_rendering = null;
	
	void queueDrawingCacheRequestByLine(int lidx) { // This implies dependency on tile uids
		synchronized(bmpworker_thread) {
			line_idxes_requests.add(lidx);
		}
	}
	void queueDrawingCacheRequestByTileUID(int uid) {
		synchronized(bmpworker_thread) {
			tile_uids_requests.add(uid);
		}
	}
	void cancelAllPendingRequests() {
		synchronized(bmpworker_thread) {
			line_idxes_requests.clear();
			tile_uids_requests.clear();
		}
	}
	
	class BitmapWorkerRunnable implements Runnable {
		@Override
		public void run() {
			while(is_running) {

				try {
					Thread.sleep(100);
					boolean cache_updated = false;
					if(tile_uids_requests.size() > 0) { cache_updated = true; }
					if(line_idxes_requests.size() > 0) { cache_updated = true; }
					if(cache_updated) {
						// Reason for taking the lock on bmpworker thread:
						// Do not let the bmp cache be flushed when a line is being rendered in bkgrnd
						synchronized(bmpworker_thread) {
							synchronized(bitmap_helper.cached_bmps) {
								for(Integer uid : tile_uids_requests) {
									if(bitmap_helper.cached_bmps.containsKey(uid)) continue;
									int midsid[] = {-1, -1};
									getTileMidSidFromUID(uid, midsid);
									Bitmap bmp = sheet.RenderTile(midsid[0], midsid[1], bitmap_helper.bitmap_zoom, bitmap_helper.bitmap_zoom);
									bitmap_helper.insertBitmap(bmp, uid);
								}
							}
							for(Integer lidx : line_idxes_requests) { // May have concurrent modification exception here.
								line_bitmap_helper.renderLineAndCreateBuffer(lidx, true);
							}
							synchronized(line_idxes_requests) {
								line_idxes_requests.clear();
							}
							synchronized(tile_uids_requests) {
								tile_uids_requests.clear();
							}
							if(cache_updated==true) need_redraw = true;
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}	
	}
	
	void free() {
		synchronized(this) {
			bitmap_helper.free();
			line_bitmap_helper.free();
			sheet.free();
			sheet = null;
			is_freed = true;
			is_running = false;
			updater_thread = null;
			midi_data = null;
			measure_tiles.clear();
			paint = null;
			parent = null;
			for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
				highscores.get(i).clear();
				timestamps.get(i).clear();
				right_clicks_history.get(i).clear();
				wrong_clicks_history.get(i).clear();
			}
			highscores.clear();
			timestamps.clear();
			right_clicks_history.clear();
			wrong_clicks_history.clear();
			System.gc();
			
			// Save last used zoom level.
			// Load last used zoom level.
			SharedPreferences.Editor editor = prefs_playcount.edit();
			editor.putInt(midi_uri_string + "_last_zoom_idx", line_width_idx);
			editor.commit();
		}
	}
	
	int y_offset = 0, y_offset_max = 1; // Imagine everything laid out on a plane. The View is a sliding window put at y=y_offset.
	
	int pan_accumulated = 0, PANNING_THRESHOLD;
	
	// Panning Y has a higher priority than auto scrolling
	boolean is_panning_y = false;
	private long panning_y_start_millis = 0, panning_y_start_offset;
	private long autoscroll_y_start_millis = 0;
	private float autoscroll_y_completion = 0.0f;
	private int autoscroll_y_start, autoscroll_y_end;
	private final int AUTOSCROLL_DURATION = 500;
	private void autoScrollYTo(int y_end) {
		if(!is_panning_y) {
			if(y_end > y_offset_max) y_end = y_offset_max;
			if(y_end < 0) y_end = 0;
			autoscroll_y_end = y_end;
			autoscroll_y_start = y_offset;
			autoscroll_y_start_millis = System.currentTimeMillis();
			autoscroll_y_completion = 0.0f;
		}
	}
	private void updateAutoScrollY(long millis) {
		long delta = millis - autoscroll_y_start_millis;
		if(autoscroll_y_completion >= 1.0f) {
			return;
		} else {
			if(delta > AUTOSCROLL_DURATION) {
				autoscroll_y_completion = 1.0f;
			} else {
				float t = delta * 1.0f / AUTOSCROLL_DURATION;
				autoscroll_y_completion = 1.0f - (1.0f - t) * (1.0f - t);
				y_offset = (int)(autoscroll_y_start * (1.0f - autoscroll_y_completion) 
						+ autoscroll_y_end * autoscroll_y_completion);
				need_redraw = true;
			}
		}
	}
	private void stopAutoScrollY() {
		autoscroll_y_completion = 1.0f;
	}
	
	private MeasureTile getFirstMeasureIntersectsTopOfScreen() {
		int min_y = Integer.MAX_VALUE;
		MeasureTile ret = null;
		for(int i=0; i<measure_tiles.size(); i++) {
			MeasureTile mt = measure_tiles.get(i);
			if(mt.staff_idx == 0 && mt.y <= y_offset && mt.y + mt.H >= y_offset) {
				if(mt.y < min_y) {
				 ret = mt;
				 min_y = mt.y;
				}
			}
		}
		return ret;
	}
	
	private MeasureTile getFirstVisibleMeasureByID(int measure_idx) {
		for(MeasureTile x : measure_tiles) {
			if(x.staff_idx == 0 && x.measure_idx == measure_idx) return x;
		}
		return null;
	}
	
	private float vel_y = 0.0f;
	void checkVelY() {
		long delta = System.currentTimeMillis() - panning_y_start_millis;
		if(delta > 200) { vel_y = 0; }
		else {
			float vlimit = VEL_Y_THRESH * density;
			if(vel_y > vlimit) vel_y = vlimit;
			if(vel_y <-vlimit) vel_y = -vlimit;
			Log.v("TommyIntroView", "vel_y="+vel_y);
		}
	}
	
	void setYOffset(int yoffset) {
		boolean is_oob = false;
		if(yoffset < 0) { yoffset = 0; is_oob = true; }
		if(yoffset > y_offset_max) { yoffset = y_offset_max; is_oob = true; }
		if(is_oob) {
			vel_y = 0.0f;
		}
		y_offset = yoffset;
	}
	void yOffsetInertia() {
		if(Math.abs(vel_y) < 1.0f) {
			vel_y = 0.0f;
		} else {
			need_redraw = true;
		}
		setYOffset((int)(y_offset + vel_y));
		vel_y = (float)(vel_y * 0.95f);
		if(vel_y != 0) need_redraw = true;
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
	
	//============================================================
	class MeasureBitmapHelper {
		SheetMusic sheet;
		TommyIntroView view;
		private int bytes_consumed;
		float bitmap_zoom;
		MeasureBitmapHelper(TommyIntroView _tv, SheetMusic _sheet, float _bz) {
			bytes_consumed = 0;
			view = _tv; sheet = _sheet;
			bitmap_zoom = _bz;
		}
		@SuppressLint("UseSparseArrays")
		HashMap<Integer, Bitmap> cached_bmps = new HashMap<Integer, Bitmap>();
		LinkedHashMap<Integer, Long>   last_access = new LinkedHashMap<Integer, Long>();
		
		void requestTileBitmap(int staff_idx, int measure_idx) {
			int uid = view.getTileUIDFromMidSid(measure_idx, staff_idx);
			queueDrawingCacheRequestByTileUID(uid);
		}
		
		void insertBitmap(Bitmap bmp, int uid) {
			synchronized(cached_bmps) {
				if(cached_bmps.containsKey(uid)) {
					int x[] = {-1}; x[123]=123;
				}
				cached_bmps.put(uid, bmp);
				bytes_consumed += bmp.getHeight() * bmp.getWidth() * 2; // RGB 565, 1 pixel = 2 bytes;
				last_access.put(uid, System.currentTimeMillis());
			}
		}
		
		
		// Do this in a blocking fashion
		
		Bitmap getTileBitmapSynchronous(int staff_idx, int measure_idx) {
			int uid = view.getTileUIDFromMidSid(measure_idx, staff_idx);
			if(cached_bmps.containsKey(uid) == false) {
				Bitmap bmp = sheet.RenderTile(measure_idx, staff_idx, bitmap_zoom, bitmap_zoom);
				bytes_consumed += bmp.getHeight() * bmp.getWidth() * 2; // RGB 565, 1 pixel = 2 bytes
				synchronized(cached_bmps) {
					cached_bmps.put(uid, bmp);
				}
			}
			return cached_bmps.get(uid);
		}
		
		Bitmap getTileBitmap(int staff_idx, int measure_idx, boolean is_synchronous) {
			int uid = view.getTileUIDFromMidSid(measure_idx, staff_idx);
			if(cached_bmps.containsKey(uid) == false) {
				if(is_synchronous) {
					Bitmap bmp = sheet.RenderTile(measure_idx, staff_idx, bitmap_zoom, bitmap_zoom);
					bytes_consumed += bmp.getHeight() * bmp.getWidth() * 2; // RGB 565, 1 pixel = 2 bytes
					synchronized(cached_bmps) {
						cached_bmps.put(uid, bmp);
					}
				} else {
					requestTileBitmap(staff_idx, measure_idx);
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
		void do_clearBmp(int key) {
			synchronized(cached_bmps) {
				Bitmap bmp = cached_bmps.get(key);
				cached_bmps.remove(key);
				last_access.remove(key);
				bytes_consumed -= bmp.getHeight() * bmp.getWidth() * 2;
				bmp.recycle();
				bmp = null;
			}
		}
		public void clearOutOfSightBitmaps() {
			int midsid[] = {-1, -1};
			Set<Integer> keys = cached_bmps.keySet();
			HashSet<Integer> to_delete = new HashSet<Integer>();
			synchronized(cached_bmps) {
				for(Integer x : keys) {
					view.getTileMidSidFromUID(x, midsid);
					int midx = midsid[0], sidx = midsid[1];
					
					if(view.isMeasureOutOfSight(midx, sidx)) {
						to_delete.add(x);
					}
				}
				for(Integer y : to_delete) {
					do_clearBmp(y);
				}
			}
		}
		public void clearOutOfSightBitmapsLRU(int size_cap, boolean is_respect_uids_requested) {
			// Sort by usage time
			synchronized(cached_bmps) {
				while(bytes_consumed > size_cap) {
					Integer key_min = -999;
					long min_millis = Long.MAX_VALUE;
					for(java.util.Map.Entry<Integer, Long> ety : last_access.entrySet()) {
						Integer key = ety.getKey();
						if(is_respect_uids_requested == true) {
							if(line_bitmap_helper.uids_requested.contains(key)) continue;
						}
						if(min_millis > ety.getValue()) {
							key_min = key;
							min_millis = ety.getValue();
						}
					}
					if(key_min == -999) break;
					else {
						do_clearBmp(key_min);
					}
				}
			}
		}
		public void free() {
			Set<Integer> keys = cached_bmps.keySet();
			HashSet<Integer> to_delete = new HashSet<Integer>();
			for(Integer x : keys) { to_delete.add(x); }
			for(Integer x : to_delete) { do_clearBmp(x); }
			cached_bmps.clear();
			System.gc();
		}
	}
	//============================================================
	
	class LineBitmapHelper {
		ArrayList<Bitmap> line_bmps = new ArrayList<Bitmap>();
		ArrayList<Long>   created_ts= new ArrayList<Long>();
		HashSet<Integer>  uids_requested = new HashSet<Integer>();
		int num_bmps = 0;
		int bytes_consumed = 0;
		LineBitmapHelper(int line_num) {
			line_bmps.clear();
			for(int i=0; i<line_num; i++) {
				line_bmps.add(null);
				created_ts.add(0L);
			}
		}
		
		// 2014-03-19 15:33
		// This function is not guaranteed to complete successfully.
		// Reason: Imagine the user flings the screen extremely quickly. The viewport scans through
		// 100 lines and stops at the last line (the 100th line).
		// Then, all the 100 lines will be put to request.
		// However, 100 lines is well beyond the allowed bitmap budget.
		// So, some of the tile BMPs will be populated and then RECYCLED.
		// In this case, this function would fail and return FALSE
		private boolean renderLineAndCreateBuffer(int lidx, boolean is_synchronous) {
			if(line_bmps.get(lidx) != null) {
				Bitmap b = line_bmps.get(lidx);
				if(!b.isRecycled()) b.recycle();
				line_bmps.set(lidx, null);
			}
			
			int line_height = line_heights.get(lidx);
			int y1 = button_panel.y + button_panel.H;
			for(int i=0; i<lidx; i++) {
				y1 = y1 + line_heights.get(lidx);
			}
			int line_ymin = y1;
			
			Bitmap newbmp = Bitmap.createBitmap(width, line_height, Config.RGB_565);
			Canvas c = new Canvas(newbmp);
			c.drawColor(TommyConfig.getCurrentStyle().background_color);
			
			int idx0_start = line_midx_start.get(lidx) * num_staffs, idx0_end;
			if(lidx < line_midx_start.size() - 1) {
				idx0_end = line_midx_start.get(lidx+1) * num_staffs;
			} else idx0_end = measure_tiles.size();
			
			
			{
				c.save();
				c.translate(0, -line_ymin);
				NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;

				for(int i=0; i<y_separators.size(); i++) {
					int ys = y_separators.get(i);
					int h_sep = y_separators_heights.get(i);
					if(ys + h_sep > line_ymin && ys < line_ymin + height) {
						dst.set(0, (int)(ys), width, (int)(ys+h_sep));
						bk.setBounds(dst);
						bk.draw(c);
					}
				}
			
				boolean is_success = true;
				synchronized(bitmap_helper) {
					for(int idx0=idx0_start; idx0 < idx0_end; idx0++) {
						MeasureTile mt = measure_tiles.get(idx0);
						if(is_success) {
							mt.prepareToDraw(true, is_synchronous);
							mt.do_draw(c, true);
						}
						
						if(mt.bmp == null) {
							is_success = false;
						}
						
						if(mt.bmp != null && mt.bmp.isRecycled()) {
							is_success = false;
						}
						
						int uid = getTileUIDFromMidSid(mt.measure_idx, mt.staff_idx);
						uids_requested.remove(uid);
						bitmap_helper.do_clearBmp(uid);
					}
				}
					
				c.restore();
				
				if(!is_success) {
					newbmp.recycle();
					return false;
				}
			}
			
			line_bmps.set(lidx, newbmp);
			created_ts.set(lidx, System.currentTimeMillis());
			num_bmps ++;
			bytes_consumed += 2*newbmp.getHeight()*newbmp.getWidth();
			return true;
		}
		
		// THIS IS A TRANSACTION !!!!!!!!!!!!!!!!!!!!!!!!!!!
		// The availability of Bitmaps in the bitmap_helper
		//     should remain constant throughout this transaction
		// Beginning of this transaction is when this function is called in
		//   the main thread
		// End of this transaction is when the actual draw function is called from
		//   the bitmap worker thread and completes drawing.
		// 
		// Computer Architecture stuff may be just these things in bullet time.
		//
		void requestLineBitmapAndItsDependencies(int lidx) {
			queueDrawingCacheRequestByLine(lidx);
			int midx_start = line_midx_start.get(lidx), midx_end;
			if(lidx == line_midx_start.size() - 1) {
				midx_end = num_measures;
			} else midx_end = line_midx_start.get(lidx+1);
			
			int idx0_start = line_midx_start.get(lidx) * num_staffs, idx0_end;
			if(lidx < line_midx_start.size() - 1) {
				idx0_end = line_midx_start.get(lidx+1) * num_staffs;
			} else idx0_end = measure_tiles.size();
			
			for(int idx0 = idx0_start; idx0 < idx0_end; idx0++) {
				MeasureTile mt = measure_tiles.get(idx0);
				int uid = getTileUIDFromMidSid(mt.measure_idx, mt.staff_idx);
				if(bitmap_helper.cached_bmps.containsKey(uid) == false) {
					queueDrawingCacheRequestByTileUID(uid);
				}
				uids_requested.add(uid);
			}
		}
		
		Bitmap getLineBitmap(int lidx) {
			if(line_idxes_requests.contains(lidx)) return null; // There is a pending request not satisfied yet
			Bitmap ret = line_bmps.get(lidx); 
			if(ret != null) {
				if(!ret.isRecycled()) {
					return ret;
				}
			}
			requestLineBitmapAndItsDependencies(lidx);
			return null;
		}
		void free() {
			for(int i=0; i<line_bmps.size(); i++) {
				invalidateCacheAtLine(i);
			}
		}
		private void invalidateCacheAtLine(int midx) {
			if(line_bmps.get(midx) == null) return;
			else {
				if(line_bmps.get(midx).isRecycled()==false) {
					Bitmap victim = line_bmps.get(midx);
					bytes_consumed -= 2 * victim.getWidth() * victim.getHeight();
					line_bmps.get(midx).recycle();
					line_bmps.set(midx, null);
					created_ts.set(midx, 0L);
					num_bmps --;
				}
			}
		}
		void invalidateLRU(int until) {
			while(bytes_consumed > until && num_bmps > 0) {
				long smallest = Long.MAX_VALUE; int idx = 0;
				for(int i=0; i<line_bmps.size(); i++) {
					if(created_ts.get(i) > 0 && created_ts.get(i) < smallest) {
						idx = i; smallest = created_ts.get(i);
					}
				}
				invalidateCacheAtLine(idx);
			}
		}
	}
	
	class InvisibleDPad {
		final static int TRIPLECLICK_WINDOW = 300; // 300 ms
		final static int MOVE_VELOCITY_THRESHOLD = 5;
		final static int DELTA_Y_WINDOW_SIZE = 3; // # FRAMES
		int touchx, touchy; // Screen coordinates
		int deltax, deltay;
		int touchid = -1; long last_click_millis = 0;
		int click_count = 0;
		long window_timestamps[] = new long[DELTA_Y_WINDOW_SIZE];
		int  window_deltays[]  = new int[DELTA_Y_WINDOW_SIZE];
		int window_idx = 0;
		
		private void addWindowEntry(long timestamp, int deltay) {
			window_deltays[window_idx] = deltay;
			window_timestamps[window_idx] = timestamp;
			window_idx ++;
			if(window_idx >= DELTA_Y_WINDOW_SIZE) { window_idx = 0; }
		}
		
		private void clearWindowEntries() {
			for(int i=0; i<DELTA_Y_WINDOW_SIZE; i++) {
				window_deltays[i] = 0;
				window_timestamps[i] = 0;
			}
			window_idx = 0;
		}
		
		private float getWindowVelYPerMilliSecond() {
			int widx = window_idx - 1;
			if(widx < 0) widx = widx + DELTA_Y_WINDOW_SIZE;
			long max_tstamp = window_timestamps[widx], min_tstamp = max_tstamp;
			int sum_dy = window_deltays[widx];			
			for(int j=0; j<DELTA_Y_WINDOW_SIZE; j++) {
				widx --;
				if(widx < 0) widx += DELTA_Y_WINDOW_SIZE;
				
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
			return (float) (sum_dy * 1.0f / (max_tstamp - min_tstamp) * Math.pow(0.5, millis*1.0f/40.0f));
		}
		
		public void clearDeltaXY() { deltax = deltay = 0; }
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				touchx = x; touchy = y;
				touchid = id;
				touches_on_bk ++;
				last_click_millis = System.currentTimeMillis();
				addWindowEntry(last_click_millis, 0);
				click_count ++;
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
				addWindowEntry(System.currentTimeMillis(), 0);
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
				addWindowEntry(System.currentTimeMillis(), y - touchy);
				touchx = x;
				touchy = y;
			} else {
				;
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
		void update(long millis) {
			if(millis - last_click_millis > TRIPLECLICK_WINDOW) {
				click_count = 0;
			}
		}
	}
	InvisibleDPad invdpads[] = new InvisibleDPad[10];
	
	// Sub-view components
	class ScoreHistoryPanel {
		int W, H, x, y, touchid = -1, touchx, touchy, pad_top, pad_bottom, pad_left;
		long last_click_millis;
		int pad_button, H_button, H_button_indicator;
		int dy_btnrow1, dy_btnrow2, dy_btnrow3, dy_btnrow4, dy_curves;
		final String[] btnrow1_labels = {"25%", "50%", "75%", "100%"};
		final String[] btnrow2_labels = {"Time", "Accuracy"}; // Radio button
		final String[] btnrow3_labels = {"Date", "Index"};    // How X axis is scaled
		final String[] btnrow4_labels = {"Advanced..."};
		
		boolean[] btnrow1_flags = {true, true, true, true, true};
		
		int btnrow1_hl_idx = -1, btnrow2_hl_idx = -1, btnrow3_hl_idx = -1, btnrow4_hl_idx = -1;
		int btnrow2_choice_idx = 0, btnrow3_choice_idx = 0, btnrow4_choice_idx = 0;
		
		int[] btnrow1_x, btnrow1_w, btnrow2_x, btnrow2_w, btnrow3_x, btnrow3_w, btnrow4_x, btnrow4_w;
		float btn_text_size;
		
		// Computed layout information
		long min_timestamp, max_timestamp, min_elapsed, max_elapsed;
		int W_curves;
		float curve_graph_text_size;
		
		private void layoutCurveGraph() {
			// Layout the timestamps to be shown
			int idx = 0;
			min_elapsed = 0;
			max_elapsed = 0; 
			min_timestamp = Long.MAX_VALUE;
			max_timestamp = Long.MIN_VALUE;
			for(ArrayList<Long> tses : timestamps) {
				if(btnrow1_flags[idx] == false) continue;
				for(Long x : tses) {
					if(x < min_timestamp) min_timestamp = x;
					if(x > max_timestamp) max_timestamp = x;
				}
				idx++;
			}
			idx = 0;
			for(ArrayList<Long> elapses : highscores) {
				if(btnrow1_flags[idx] == false) continue;
				for(Long x : elapses) {
					if(x < min_elapsed) min_elapsed = x;
					if(x > max_elapsed) max_elapsed = x;
				}
				idx++;
			}
			
			paint.setTextSize(curve_graph_text_size);
			if(btnrow2_choice_idx == 0) // time
				W_curves = (int) (W - 3*density - 2*pad_left - paint.measureText(String.format("%.1fs", max_elapsed/1000.0f)));
			else {
				W_curves = (int) (W - 3 * density - 2*pad_left - paint.measureText("100%"));
			}
		}
		
		ScoreHistoryPanel(int _w, int _h, int _x, int _y) {
			W = _w; H = _h; x = _x; y = _y;
			pad_left = pad_top = (int)(5*density); pad_bottom = (int)(10 * density);
			pad_button = (int)(3.0f * density);
			min_timestamp = min_elapsed = Long.MAX_VALUE; max_elapsed = max_timestamp = Long.MIN_VALUE;
			H_button = (int)(35*density);
			H_button_indicator = (int)(5*density);
			btn_text_size = 13.0f * density;
			curve_graph_text_size = 10.0f * density;
			
			dy_btnrow1 = (int)(16*density*3);
			dy_btnrow2 = dy_btnrow1;// + H_button;
			dy_btnrow3 = dy_btnrow1 + H_button;
			dy_btnrow4 = dy_btnrow3;
			dy_curves = dy_btnrow4 + H_button + (int)(5*density);
			
			
			int lblcnt1 = btnrow1_labels.length;
			btnrow1_w = new int[lblcnt1]; btnrow1_x = new int[lblcnt1];
			int lblcnt2 = btnrow2_labels.length;
			btnrow2_w = new int[lblcnt2]; btnrow2_x = new int[lblcnt2];
			int lblcnt3 = btnrow2_labels.length;
			btnrow3_w = new int[lblcnt3]; btnrow3_x = new int[lblcnt3];
			int lblcnt4 = btnrow2_labels.length;
			btnrow4_w = new int[lblcnt4]; btnrow4_x = new int[lblcnt4];
			
			
			paint.setTextSize(btn_text_size);
			
			// Layout row 1 buttons 
			int x = pad_left;
			for(int i=0; i<btnrow1_labels.length; i++) {
				btnrow1_x[i] = x;
				int w = (int)(2*pad_button + paint.measureText(btnrow1_labels[i]));
				btnrow1_w[i] = w;
				x = x + w + 1;
			}
			
			// Layout row 2 buttons
			x = pad_left;
			int w2 = 0;
			for(int i=0; i<btnrow2_labels.length; i++) {
				btnrow2_x[i] = x;
				int w = (int)(2*pad_button + paint.measureText(btnrow2_labels[i]));
				w2 += w;
				btnrow2_w[i] = w;
				x = x + w + 1;
			}
			// Let Btn Row 2 be on the same line as Btn Row 1
			for(int i=0; i<btnrow2_labels.length; i++) {
				btnrow2_x[i] += (W-2*pad_left-w2);
			}
			
			// Layout row 3 buttons
			x = pad_left;
			int w3 = 0;
			for(int i=0; i<btnrow3_labels.length; i++) {
				btnrow3_x[i] = x;
				int w = (int)(2*pad_button + paint.measureText(btnrow3_labels[i]));
				w3 += w;
				btnrow3_w[i] = w;
				x = x + w + 1;
			}
			
			// Layout row 4 buttons
			x = pad_left;
			int w4 = 0;
			for(int i=0; i<btnrow4_labels.length; i++) {
				btnrow4_x[i] = x;
				int w = (int)(2*pad_button + paint.measureText(btnrow4_labels[i]));
				w4 += w;
				btnrow4_w[i] = w;
				x = x + w + 1;
			}
			for(int i=0; i<btnrow4_labels.length; i++) {
				btnrow4_x[i] += (W-2*pad_left-w4);
			}
			
			layoutCurveGraph();
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			int y0 = y-y_offset;

			NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;
			bk.setBounds(x, y0, x+W, y0+H);
			bk.draw(c);
			
			paint.setStyle(Style.STROKE);
			paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
			paint.setStrokeWidth(density);
			if(isTouched()) {
				paint.setStyle(Style.FILL);
			} else {
				paint.setStyle(Style.STROKE);
			}
			
			paint.setStyle(Style.FILL);
			// Text here
			{
				paint.setTextAlign(Align.CENTER);
				float txt_hgt0 = 16.0f*density, txt_hgt;
				paint.setTextSize(txt_hgt0);
				float w0 = paint.measureText(midi_title);
				if(w0 > W) {
					txt_hgt = txt_hgt0 * W / w0;
				} else txt_hgt = txt_hgt0;
				paint.setTextSize(txt_hgt);
				y0 = (int)(y0 + txt_hgt);
				c.drawText(midi_title, x+W/2, y0, paint);
				
				y0 = (int)(y0 + txt_hgt0);
				String played = String.format("%d Plays, %d Quizzees", num_played, num_quiz);
				paint.setTextSize(txt_hgt0);
				c.drawText(played, x+W/2, y0, paint);
				
				y0 = (int)(y0 + txt_hgt0);
			}
			
			// Some Button here
			//
			// ---------------------------
			// | 25% | 50% | 75% | 100% |
			// ---------------------------
			// | Time | Accuracy |
			// ---------------------------
			//
			// First row buttons
			NinePatchDrawable btn_bk = TommyConfig.getCurrentStyle().background_separator;
			{
				y0 = y-y_offset + dy_btnrow1;
				for(int i=0; i<btnrow1_labels.length; i++) {
					int x1 = btnrow1_x[i];
					int w1 = btnrow1_w[i];
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					paint.setStrokeWidth(density);
					paint.setStyle(Style.STROKE);
					paint.setTextAlign(Align.LEFT);
					paint.setTextSize(btn_text_size);

					btn_bk.setBounds(x1, y0, x1+w1, y0+H_button);
					btn_bk.setTargetDensity(c);
					btn_bk.draw(c);
					if(btnrow1_hl_idx == i) {
						paint.setStyle(Style.FILL);
						c.drawRect(x1, y0, x1+w1, y0+H_button, paint);
					}
					else paint.setStyle(Style.STROKE);
					paint.setStyle(Style.FILL);
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					c.drawText(btnrow1_labels[i], x1+pad_button, y0+(H_button-H_button_indicator)/2-(paint.ascent()+paint.descent())/2, paint);
					if(btnrow1_flags[i] == true) {
						paint.setStyle(Style.FILL);
					} else paint.setStyle(Style.STROKE);
					if(i < TommyConfig.BLANK_RATIOS.length) {
						paint.setColor(TommyConfig.CURVE_COLORS[i]);
					} else paint.setColor(0xFF808080);
					c.drawRect(x1+2*density, y0+H_button-2*density-H_button_indicator, x1+w1-2*density, y0+H_button-2*density, paint);
				}
			}
			
			// Second row buttons
			{
				y0 = y-y_offset + dy_btnrow2;
				for(int i=0; i<btnrow2_labels.length; i++) {
					int x2 = btnrow2_x[i];
					int w2 = btnrow2_w[i];
					
					// Outline of button
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					paint.setStrokeWidth(density);
					paint.setStyle(Style.STROKE);
					paint.setTextAlign(Align.LEFT);
					paint.setTextSize(btn_text_size);
					
					btn_bk.setBounds(x2, y0, x2+w2, y0+H_button);
					btn_bk.setTargetDensity(c);
					btn_bk.draw(c);
					if(btnrow2_hl_idx == i) {
						paint.setStyle(Style.FILL);
						c.drawRect(x2, y0, x2+w2, y0+H_button, paint);
					}
					else paint.setStyle(Style.STROKE);
					
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint.setStyle(Style.FILL);
					c.drawText(btnrow2_labels[i], x2+pad_button, y0+(H_button-H_button_indicator)/2-(paint.ascent()+paint.descent())/2, paint);
					if(i == btnrow2_choice_idx) {
						paint.setStyle(Style.FILL);
					} else paint.setStyle(Style.STROKE);
					c.drawRect(x2+2*density, y0+H_button-2*density-H_button_indicator, x2+w2-2*density, y0+H_button-2*density, paint);
				}
			}
			
			// Third row buttons
			{
				y0 = y-y_offset + dy_btnrow3;
				for(int i=0; i<btnrow3_labels.length; i++) {
					int x2 = btnrow3_x[i];
					int w2 = btnrow3_w[i];
					
					// Outline of button
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					paint.setStrokeWidth(density);
					paint.setStyle(Style.STROKE);
					paint.setTextAlign(Align.LEFT);
					paint.setTextSize(btn_text_size);
					
					btn_bk.setBounds(x2, y0, x2+w2, y0+H_button);
					btn_bk.setTargetDensity(c);
					btn_bk.draw(c);
					if(btnrow3_hl_idx == i) {
						paint.setStyle(Style.FILL);
						c.drawRect(x2, y0, x2+w2, y0+H_button, paint);
					}
					else paint.setStyle(Style.STROKE);
					
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint.setStyle(Style.FILL);
					c.drawText(btnrow3_labels[i], x2+pad_button, y0+(H_button-H_button_indicator)/2-(paint.ascent()+paint.descent())/2, paint);
					if(i == btnrow3_choice_idx) {
						paint.setStyle(Style.FILL);
					} else paint.setStyle(Style.STROKE);
					c.drawRect(x2+2*density, y0+H_button-2*density-H_button_indicator, x2+w2-2*density, y0+H_button-2*density, paint);
				}
			}
			
			// Fourth row buttons
			{
				y0 = y-y_offset + dy_btnrow4;
				for(int i=0; i<btnrow4_labels.length; i++) {
					int x2 = btnrow4_x[i];
					int w2 = btnrow4_w[i];
					
					// Outline of button
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					paint.setStrokeWidth(density);
					paint.setStyle(Style.STROKE);
					paint.setTextAlign(Align.LEFT);
					paint.setTextSize(btn_text_size);
					
					btn_bk.setBounds(x2, y0, x2+w2, y0+H_button);
					btn_bk.setTargetDensity(c);
					btn_bk.draw(c);
					if(btnrow4_hl_idx == i) {
						paint.setStyle(Style.FILL);
						c.drawRect(x2, y0, x2+w2, y0+H_button, paint);
					}
					else paint.setStyle(Style.STROKE);
					
					paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint.setStyle(Style.FILL);
					c.drawText(btnrow4_labels[i], x2+pad_button, y0+(H_button-H_button_indicator)/2-(paint.ascent()+paint.descent())/2, paint);
					paint.setStyle(Style.STROKE);
					c.drawRect(x2+2*density, y0+H_button-2*density-H_button_indicator, x2+w2-2*density, y0+H_button-2*density, paint);
				}
			}

			// High score panel here
			{
				y0 = y-y_offset + dy_curves;
				int h_hs = H - pad_bottom - (y0 - (y-y_offset));
				paint.setColor(0xFF808080);
				paint.setStyle(Style.STROKE);
				c.drawRect(x+pad_left, y0, x+pad_left+W_curves, h_hs + y0, paint);
				
				// Ticks
				{
					int min_tick_y = (int)(y0 + h_hs - paint.descent());
					paint.setTextAlign(Align.RIGHT);
					paint.setStrokeWidth(density);
					if(btnrow2_choice_idx == 0) {
						c.drawText("0.0s", x+W-pad_left, min_tick_y, paint);
					} else {
						c.drawText("0%", x+W-pad_left, min_tick_y, paint);
					}
					
					int max_tick_y = (int)(y0 - paint.ascent());
					if(btnrow2_choice_idx == 0) {
						c.drawText(String.format("%.1fs", max_elapsed/10000.0f), x+W-pad_left, max_tick_y, paint);
					} else {
						c.drawText("100%", x+W-pad_left, max_tick_y, paint);
					}					
				}
				
				paint.setTextAlign(Align.LEFT);
				paint.setTextSize(curve_graph_text_size);
				float txt_y = y0 + h_hs - paint.descent();
				c.drawText("Begin", x+pad_left, txt_y, paint);

				paint.setTextAlign(Align.RIGHT);
				c.drawText("End", x+pad_left+W_curves, txt_y, paint);
				
				paint.setStrokeWidth(density);
				paint.setStyle(Style.FILL);
				for(int optidx = 0; optidx < TommyConfig.BLANK_RATIOS.length; optidx++) {
					if(btnrow1_flags[optidx] == false) continue;
					ArrayList<Long> elapsed = highscores.get(optidx);
					ArrayList<Long> tstamp  = timestamps.get(optidx);
					ArrayList<Integer> right_clks = right_clicks_history.get(optidx);
					ArrayList<Integer> wrong_clks = wrong_clicks_history.get(optidx);
					float last_dx = -999.0f, last_dy = -999.0f;
					paint.setColor(TommyConfig.CURVE_COLORS[optidx]);
					for(int etyidx = 0; etyidx < elapsed.size(); etyidx++) {
						long t_elapsed = elapsed.get(etyidx), t_tstamp = tstamp.get(etyidx);
						float dx, dy;
						if(max_timestamp == min_timestamp) {
							dx = this.x + this.W/2;
						} else {
							dx = this.x+pad_left + (((t_tstamp - min_timestamp) * 1.0f) / (max_timestamp - min_timestamp)) * (W_curves);
						}
						if(btnrow2_choice_idx == 0) { // Show time
							if(max_elapsed == min_elapsed) {
								dy = y0 + h_hs/2;
							} else {
								dy = y0 + h_hs - h_hs * (t_elapsed * 1.0f / max_elapsed);
							}
						} else {
							dy = y0 + h_hs * (1.0f - right_clks.get(etyidx)*1.0f / (wrong_clks.get(etyidx)+right_clks.get(etyidx)));
						}
						c.drawCircle(dx, dy, 3.0f*density, paint);
						if(last_dy != -999.0f) {
							c.drawLine(dx, dy, last_dx, last_dy, paint);
						}
						last_dy = dy; last_dx = dx;
					}
				}
			}
			
			paint.setStyle(Style.STROKE);
		}
		boolean is_visible() {
			if(y_offset < y-height) return false;
			if(y_offset > y+H) return false;
			return true;
		}

		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				int y0 = y_offset+y-this.y;
				if(y0 > 0 && y0 < H && x > this.x && x < this.x+W) {
					touchx = x; touchy = y0;
					touchid = id;
					
					// Process button events
					{
						btnrow1_hl_idx = getBtnRow1HighlightIdx();
						btnrow2_hl_idx = getBtnRow2HighlightIdx();
						btnrow3_hl_idx = getBtnRow3HighlightIdx();
						btnrow4_hl_idx = getBtnRow4HighlightIdx();
					}
					
					return true;
				} else {
					return false;
				}
			} else {
				if(touchid != id) {
					return false;
				}
			}
			return true;
		}
		
		private int getBtnRow1HighlightIdx() {
			if(touchy > dy_btnrow1 && touchy < dy_btnrow1 + H_button) {
				for(int i=0; i<btnrow1_labels.length; i++) {
					if(touchx > btnrow1_x[i] && touchx < btnrow1_x[i]+btnrow1_w[i]) {
						return i;
					}
				}
			}
			return -1;
		}
		private int getBtnRow2HighlightIdx() {
			if(touchy > dy_btnrow2 && touchy < dy_btnrow2 + H_button) {
				for(int i=0; i<btnrow2_labels.length; i++) {
					if(touchx > btnrow2_x[i] && touchx < btnrow2_x[i]+btnrow2_w[i]) {
						return i;
					}
				}
			}
			return -1;
		}
		private int getBtnRow3HighlightIdx() {
			if(touchy > dy_btnrow3 && touchy < dy_btnrow3 + H_button) {
				for(int i=0; i<btnrow3_labels.length; i++) {
					if(touchx > btnrow3_x[i] && touchx < btnrow3_x[i]+btnrow3_w[i]) {
						return i;
					}
				}
			}
			return -1;
		}
		private int getBtnRow4HighlightIdx() {
			if(touchy > dy_btnrow4 && touchy < dy_btnrow4 + H_button) {
				for(int i=0; i<btnrow4_labels.length; i++) {
					if(touchx > btnrow4_x[i] && touchx < btnrow4_x[i]+btnrow4_w[i]) {
						return i;
					}
				}
			}
			return -1;
		}
		
		boolean touchUp(int id) {
			if(id == touchid) {
				touchid = -1;
				if(!is_panning_y) {
					if(btnrow1_hl_idx != -1) {
						if(btnrow1_hl_idx == getBtnRow1HighlightIdx()) {
							btnrow1_flags[btnrow1_hl_idx] = !btnrow1_flags[btnrow1_hl_idx];
							layoutCurveGraph();
						}
					}
					if(btnrow2_hl_idx != -1) {
						if(btnrow2_hl_idx == getBtnRow2HighlightIdx()) {
							btnrow2_choice_idx = btnrow2_hl_idx;
							layoutCurveGraph();
						}
					}
					if(btnrow3_hl_idx != -1) {
						if(btnrow3_hl_idx == getBtnRow3HighlightIdx()) {
							btnrow3_choice_idx = btnrow3_hl_idx;
							layoutCurveGraph();
						}
					}
					if(btnrow4_hl_idx != -1) {
						if(btnrow4_hl_idx == getBtnRow4HighlightIdx()) {
							btnrow4_choice_idx = btnrow4_hl_idx;
							// Show advanced dialog
						}
					}
				}
			}
			btnrow1_hl_idx = -1;
			btnrow2_hl_idx = -1;
			btnrow3_hl_idx = -1;
			btnrow4_hl_idx = -1;
			need_redraw = true;
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
	}
	ScoreHistoryPanel score_history;
	
	class ButtonPanel {
		int W, H, x, y, touchid = -1, touchx, touchy, H_button, H_button_indicator, btn_textsize;
		final static int NUM_BUTTONS = 3;
		int buttons_x[] = new int[NUM_BUTTONS], buttons_y[] = new int[NUM_BUTTONS];
		int buttons_w[] = new int[NUM_BUTTONS], buttons_h[] = new int[NUM_BUTTONS];
		String button_labels[] = new String[NUM_BUTTONS];
		Bitmap button_icons[]  = new Bitmap[NUM_BUTTONS];
		int btn_hl_idx = -1; // Button Highlight Index
		long last_click_millis;
		ButtonPanel(int _w, int _h, int _x, int _y) {
			W = _w; H = _h; x = _x; y = _y;
			H_button = (int)(_h*0.8);
			H_button_indicator = (int)(5*density);
			btn_textsize = (int)(28 * density);
			int pad = (int)(5*density);
			int W_btnall = (W-2*pad);
			buttons_x[0] = (int)(3*density);
			buttons_w[0] = (int)(W_btnall / 6);
			buttons_x[1] = (int)(buttons_x[0] + buttons_w[0] + 4*density);
			buttons_w[1] = (int)(W_btnall / 6);
			buttons_x[2] = (int)(buttons_x[1] + buttons_w[1] + 4*density);
			buttons_w[2] = W_btnall - buttons_x[2];
			buttons_h[0] = buttons_h[1] = buttons_h[2] = H_button;
			buttons_y[0] = buttons_y[1] = buttons_y[2] = (_h-H_button)/2;
			button_labels[0] = "";
			button_labels[1] = "";
			button_labels[2] = ctx.getResources().getString(R.string.intro_button2);
			button_icons[0]  = TommyConfig.bmp_settings;
			button_icons[1]  = TommyConfig.bmp_palette;
			button_icons[2]  = null;
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			
			int y0 = y-y_offset;
			paint.setStyle(Style.STROKE);
			paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
			paint.setStrokeWidth(density);
			
			NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;
			bk.setBounds(x, y0, x+W, y0+H);
			bk.setTargetDensity(c);
			bk.draw(c);
			
			for(int i=0; i<NUM_BUTTONS; i++) {
				dst.set(buttons_x[i], y0+buttons_y[i], 
						buttons_x[i]+buttons_w[i], y0+buttons_y[i]+buttons_h[i]);
				bk.setBounds(dst);
				bk.draw(c);
				
				int ind_buttom_y = (int)(y0+buttons_y[i]+buttons_h[i] - 2*density); 
				src.set((int)(buttons_x[i] + 2*density), (int)(ind_buttom_y-H_button_indicator),
						(int)(buttons_x[i]+buttons_w[i]-2*density), ind_buttom_y);
				paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
				paint.setStyle(Style.STROKE);
				c.drawRect(src, paint);

				paint.setColor(TommyConfig.getCurrentStyle().btn_text_color);
				paint.setStyle(Style.FILL);
				paint.setTextAlign(Align.CENTER);
				paint.setTextSize(btn_textsize);
				paint.getTextBounds(button_labels[i], 0, button_labels[i].length(), src);
				c.drawText(button_labels[i], buttons_x[i]+buttons_w[i]/2, 
						y0+buttons_y[i] + (H_button - H_button_indicator)/2 - src.top -  src.height()/2,
						paint);
				
				Bitmap b = button_icons[i];
				if(b != null) {
					int dh = (int)(H_button - H_button_indicator - 4*density);
					int dw = (int)(buttons_w[i] - 4*density);
					if(dw < dh) dh = dw;
					else dw = dh;
					src.set(0, 0, b.getWidth(), b.getHeight());
					int x_mid = buttons_x[i] + buttons_w[i]/2, 
						y_mid = (int)(buttons_y[i] + (buttons_h[i] - H_button_indicator)/2 + y0);
					dst.set(x_mid-dw/2, y_mid-dh/2, x_mid+dw/2, y_mid+dh/2);
					c.drawBitmap(b, src, dst, paint);
				}
				
				if(i == btn_hl_idx) {
					dst.set(buttons_x[i], y0+buttons_y[i], 
							buttons_x[i]+buttons_w[i], y0+buttons_y[i]+buttons_h[i]);
					paint.setAlpha(128);
					paint.setStyle(Style.FILL);
					c.drawRect(dst, paint);
					paint.setAlpha(255);
				}
			}
			paint.setStyle(Style.STROKE);
		}
		boolean is_visible() {
			if(y_offset < y-height) return false;
			if(y_offset > y+H) return false;
			return true;
		}

		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				int y0 = y_offset+y-this.y;
				if(y0 > 0 && y0 < H && x > this.x && x < this.x+W) {
					touchx = x; touchy = y0;
					touchid = id;
					need_redraw = true;
					getButtonHighlightIndex();
					return true;
				} else {
					return false;
				}
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
				need_redraw = true;
				if(is_panning_y == false) {
					switch(btn_hl_idx) {
					case 0:
						parent.changeSettings();
						break;
					case 1:
						parent.showPickColorSchemeDialog();
						break;
					case 2:
						parent.startPlay();
						break;
					default:
						break;
					} 
				}
				btn_hl_idx = -1;
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
		private void getButtonHighlightIndex() {
			for(int i=0; i<NUM_BUTTONS; i++) {
				if(touchx > buttons_x[i] && touchx < buttons_x[i]+buttons_w[i] &&
				   touchy > buttons_y[i] && touchy < buttons_y[i]+buttons_h[i])  {
					btn_hl_idx = i; return;
				}
			}
			btn_hl_idx = -1;
		}
		
	}
	ButtonPanel button_panel;

	class StayBottomButtonPanel {
		int y_visible_begin;
		int W, H, x, touchx, touchy, touchid = -1, btn_textsize, btn_hl_idx = -1, H_button_indicator;
		int buttons_x[], buttons_y[], buttons_w[], buttons_h[];
		boolean need_redraw = false;
		final static int NUM_BUTTONS = 4;
		String buttons_labels[] = new String[NUM_BUTTONS];
		public StayBottomButtonPanel(int _w, int _h, int _x) {
			W = _w; H = _h; x = _x;
			
			H_button_indicator = (int)(3*density);
			
			buttons_x = new int[NUM_BUTTONS];
			buttons_y = new int[NUM_BUTTONS];
			buttons_w = new int[NUM_BUTTONS];
			buttons_h = new int[NUM_BUTTONS];
			
			// Button 1 is the zoom in button
			int pad = (int)(3*density);
			buttons_x[0] = pad;
			buttons_w[0] = (W-NUM_BUTTONS*pad)/NUM_BUTTONS;
			buttons_x[1] = buttons_x[0]+buttons_w[0]+1;
			buttons_w[1] = (W-NUM_BUTTONS*pad)/NUM_BUTTONS;
			buttons_x[2] = buttons_x[1]+buttons_w[1]+1;
			buttons_w[2] = (W-NUM_BUTTONS*pad)/NUM_BUTTONS;
			buttons_x[3] = buttons_x[2]+buttons_w[2]+1;
			buttons_w[3] = (W-NUM_BUTTONS*pad)/NUM_BUTTONS;
			buttons_h[0] = buttons_h[1] = buttons_h[2] = buttons_h[3] = (_h - 2*pad);
			buttons_y[0] = buttons_y[1] = buttons_y[2] = buttons_y[3] = pad;
			
			buttons_labels[0] = ctx.getResources().getString(R.string.zoom_out);
			buttons_labels[1] = ctx.getResources().getString(R.string.zoom_in);
			buttons_labels[2] = null;
			buttons_labels[3] = ctx.getResources().getString(R.string.playback_speed);
		}
		private boolean isVisible() {
			int limit0 = y_visible_begin;
			if(y_offset < limit0) return false;
			return true;
		}
		private int getDisplayY() {
			int limit0 = y_visible_begin + H;
			if(y_offset >= limit0) return height - H;
			else return (height - H + (limit0 - y_offset));
		}
		public void draw(Canvas c) {
			if(isVisible()) {
				NinePatchDrawable bk = TommyConfig.getCurrentStyle().background_separator;
				int y0 = getDisplayY();
				dst.set(x, y0, x+W, y0+H);
				bk.setBounds(dst);
				bk.draw(c);
				
				for(int i=0; i<NUM_BUTTONS; i++) {
					int y1 = buttons_y[i]+y0;
					dst.set(buttons_x[i], y1, buttons_x[i]+buttons_w[i], y1+buttons_h[i]);
					bk.setBounds(dst);
					bk.draw(c);
					src.set(buttons_x[i]+(int)(2*density),
							y1+buttons_h[i]-(int)(2*density)-H_button_indicator,
							buttons_x[i]+buttons_w[i]-(int)(2*density),
							y1+buttons_h[i]-(int)(2*density));
					paint.setStyle(Style.STROKE);
					paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
					c.drawRect(src, paint);
					
					{ // Text
						paint.setTextSize(10*density);
						paint.setStyle(Style.FILL);
						paint.setTextAlign(Align.CENTER);
						String txt;
						if(buttons_labels[i] != null) {
							txt = buttons_labels[i];
						} else txt = curr_gauge_label;
						c.drawText(txt, buttons_x[i]+buttons_w[i]/2, 
								y0+buttons_h[i]-H_button_indicator-paint.descent(), paint);
						
					}
					
					if(btn_hl_idx == i) {
						paint.setAlpha(128);
						paint.setStyle(Style.FILL);
						c.drawRect(dst, paint);
						paint.setAlpha(255);
					}
				}
				need_redraw = false;
			}
		}
		private boolean isIntercept(int tx, int ty) {
			int y0 = 0;
			if(ty > y0 && ty < y0 + H && tx > x && tx < x + W) return true;
			return false;
		}
		int getButtonHighlightIndex() {
			for(int i=0; i<NUM_BUTTONS; i++) {
				if(touchx > buttons_x[i] && touchx < buttons_x[i] + buttons_w[i] &&
				   touchy > buttons_y[i] && touchy < buttons_y[i] + buttons_h[i]) {
					return i;
				}
			}
			return -1;
		}
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				int y0 = y - getDisplayY();
				if(isIntercept(x, y0)) {
					touchx = x; touchy = y0;
					touchid = id;
					need_redraw = true;
					btn_hl_idx = getButtonHighlightIndex();
					return true;
				} else {
					return false;
				}
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
				need_redraw = true;
				boolean is_zoom_changed = false;
				if(is_panning_y == false) {
					switch(btn_hl_idx) {
					case 1: // ZOOM IN
						if(line_width_idx > 0) {
							line_width_idx --; 
							is_zoom_changed = true;
							if(!MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD)
								prepareAllTilesForDrawing();
						}
						break;
					case 0:
						if(line_width_idx < LINE_WIDTHS.length-1) {
							line_width_idx ++;
							is_zoom_changed = true;
							if(!MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD)
								prepareAllTilesForDrawing();
						}
						break;
					case 2:
					{
						parent.showPickGaugeDialog();
						break;
					}
					case 3: //
					{
						parent.showAdjustSpeedDialog();
						break;
					}
					default:
						break;
					} 
				}
				if(is_zoom_changed) {
					cancelAllPendingRequests();
					prepareSheetMusicTiles(LINE_WIDTHS[line_width_idx]);
					MeasureTile first_visible = getFirstMeasureIntersectsTopOfScreen();
					layoutTiles(button_panel.y + button_panel.H);
					int scroll_to_idx = curr_sheet_playing_measure_idx;
					if(scroll_to_idx == -1) { 
						if(first_visible != null) {
							scroll_to_idx = first_visible.measure_idx;
						}
					}
					if(scroll_to_idx != -1) {
						MeasureTile x = getFirstVisibleMeasureByID(scroll_to_idx);
						y_offset = x.y;
					}
					need_redraw = true;
				}
				btn_hl_idx = -1;
			}
			return true;
		}
	}
	StayBottomButtonPanel staybottom_bottom_panel;
	
	public void setGaugeMode(GaugeMode mode) {
		boolean last_show_subseps = is_show_sub_separators;
		gauge_mode = mode;
		switch(gauge_mode) {
		case ACCURACY:
		case EFFECTIVE_DELAY:
		case DELAY:
		case NONE:
		case OCCURRENCES:
			computeMeasureTileGauge();
			need_redraw = true;
			is_show_sub_separators = false;
			break;
		case LAST_5_ATTEMPTS:
		case MASTERY_LEVEL:
			for(MeasureTile mt : measure_tiles) mt.gauge = 0.0f;
			computeMeasureTileGauge();
			is_show_sub_separators = true;
			need_redraw = true;
		default:
			break;
		}
		
		// Gauge color
		switch(gauge_mode) {
		case EFFECTIVE_DELAY:
		case DELAY:
			is_show_sub_separators = false;
			curr_gauge_color = TommyConfig.getCurrentStyle().gauge_color_undesirable; break;
		case LAST_5_ATTEMPTS:
			is_show_sub_separators = true;
			curr_gauge_color = TommyConfig.getCurrentStyle().gauge_color_undesirable; break;
		case OCCURRENCES:
			is_show_sub_separators = false;
			curr_gauge_color = TommyConfig.getCurrentStyle().gauge_color_neutral; break;
		case ACCURACY:
			is_show_sub_separators = false;
			curr_gauge_color = TommyConfig.getCurrentStyle().gauge_color_desirable; break;
		case MASTERY_LEVEL:
			is_show_sub_separators = true;
			curr_gauge_color = TommyConfig.getCurrentStyle().gauge_color_desirable; break;
		default: break;
		}
		
		if(is_show_sub_separators != last_show_subseps) {
			layout();
		}
		
		curr_gauge_label = TommyConfig.GAUGE_LABELS[mode.ordinal()];
		if(MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD) line_bitmap_helper.free();
	}
	
	private void prepareFineGrainedHistory() {
		for(MeasureTile mt : measure_tiles) {
			for(int age = 0; age < TommyConfig.FINEGRAINED_HISTORY_LENGTH; age++) {
				int sidx = actual_staff_idx.get(mt.staff_idx);
				mt.millis_hist[age] = millis_history_f.get(age).get(sidx).get(mt.measure_idx);
				mt.okclicks_hist[age]=okclicks_history_f.get(age).get(sidx).get(mt.measure_idx);
			}
		}
	}
	
	// This is called from the updater thread.
	private void onMeasurePlayed(int midx) {
		if(measure_played.get(midx) == false) {
			measure_played.set(midx, true);
			num_playable_measures_to_play --;
			if(num_playable_measures_to_play == 0) {
				incrementSheetPlayCount();
				for(int i=0; i<num_measures; i++) {
					measure_played.set(i, false);
					num_playable_measures_to_play = num_measures;
				}
			}
		}
		
		if(MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD) {
			int lidx = -999;
			if(midx != num_measures - 1) {
				for(int x = 1; x < line_midx_start.size(); x++) {
					int m_max = line_midx_start.get(x);
					int m_min = line_midx_start.get(x-1);
					if(midx >= m_min && midx < m_max) {
						lidx = x-1; break;
					}
					/*
					if(m_max-1 == midx) {
						lidx = x-1;
						break;
					}*/
				}
			} else { 
				lidx = line_midx_start.size()-1;
			}
			
			if(lidx != -999) {
				line_bitmap_helper.invalidateCacheAtLine(lidx);
				line_bitmap_helper.renderLineAndCreateBuffer(lidx, true);
			}
		}
	}
	
	private void incrementSheetPlayCount() {
		num_played += 1;
		SharedPreferences.Editor editor = prefs_playcount.edit();
		editor.putInt(midi_uri_string, num_played);
		editor.commit();
	}
	
	private void computeMeasureTileGauge() {
		if(gauge_mode == GaugeMode.NONE) {
			for(MeasureTile mt : measure_tiles) mt.gauge = 0.0f;
			return;
		}
		
		for(MeasureTile mt : measure_tiles) {
			int actual_sidx = actual_staff_idx.get(mt.staff_idx);
			int midx = mt.measure_idx;
			int n_right = right_clicks_measures.get(actual_sidx).get(midx);
			int n_wrong = wrong_clicks_measures.get(actual_sidx).get(midx);
			long delay  = delays_measures.get(actual_sidx).get(midx);
			switch(gauge_mode) {
			case ACCURACY:
				if(n_right + n_wrong == 0) {
					mt.gauge = 0.0f;
				} else {
					mt.gauge = 1.0f * (n_right) / (n_wrong + n_right);
				}
				break;
			case DELAY:{
					mt.gauge = delay;
					break;
				}
			case EFFECTIVE_DELAY:
				if(n_right > 0) {
					mt.gauge = (1.0f * delay * (n_wrong + n_right)) / (1.0f * n_right);
				} else mt.gauge = 0.0f;
				break;
			case OCCURRENCES:
				mt.gauge = n_wrong + n_right;
				break;
			case MASTERY_LEVEL:
				{
					float mlvl = masteries.get(actual_sidx).get(midx).getMasteryLevel();
					mt.gauge = mlvl;
					break;
				}
			default:
				break;
			}
		}
		
		if(gauge_mode == GaugeMode.EFFECTIVE_DELAY || gauge_mode == GaugeMode.DELAY ||
				gauge_mode == GaugeMode.OCCURRENCES) {
			float max_val = 0.0f;
			for(MeasureTile mt : measure_tiles) {
				if(mt.gauge > max_val) max_val = mt.gauge;
			}
			if(max_val > 0.0f) {
				for(MeasureTile mt : measure_tiles) {
					mt.gauge /= max_val;
				}
			}
		}
	}

	Rect dst_mt = new Rect(), src_mt = new Rect();
	Paint paint_measure = new Paint();
	class MeasureTile {
		final long CLICK_THRESHOLD = 50, FRAME_CONUT_THRESHOLD = 2; // Should hold for ~50 microseconds to be counted as 
		int W, H, x, y, touchid = -1, touchx, touchy;
		int measure_idx, staff_idx;
		long last_click_millis; int last_click_framecount;
		float gauge = 0.0f;
		boolean is_longpress1_ok = false;
		boolean okclicks_hist[] = new boolean[TommyConfig.FINEGRAINED_HISTORY_LENGTH];
		long    millis_hist[]   = new long[TommyConfig.FINEGRAINED_HISTORY_LENGTH];
		Bitmap bmp;
		MeasureTile(int _w, int _h, int _x, int _y, int _midx, int _sidx) {
			W = _w; H = _h; x = _x; y = _y;
			measure_idx = _midx; staff_idx = _sidx;
		}
		
		void prepareToDraw(boolean is_ignore_visibility, boolean is_synchronous) {
			if(is_visible() || is_ignore_visibility) {
				bmp = bitmap_helper.getTileBitmap(staff_idx, measure_idx, is_synchronous);
			}
		}
		
		void drawHighlight(Canvas c) {
			int y0 = y - y_offset;
			if(measure_idx == curr_sheet_playing_measure_idx) {
				paint.setStyle(Style.FILL);
				paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
				paint.setAlpha(64);
				int y1 = (y0+H > height) ? height : y0+H;
				c.drawRect(x, y0, x+W, y1, paint);
				paint.setAlpha(255);
			}
		}
		
		void do_draw(Canvas c, boolean is_draw_to_buffer) {
			
			// Override: If this measure is hi-lighted, get its bitmap SYNCHRONOUSLY
			if(measure_idx == curr_sheet_playing_measure_idx) {
				bmp = bitmap_helper.getTileBitmapSynchronous(staff_idx, measure_idx);
			}
			
			int sep_h = SEPARATOR_HEIGHTS[line_width_idx];
			int sep_h_minor = MINOR_SEPARATOR_HEIGHTS[line_width_idx];
			int y0;
			int deltay = 0;
			if(is_draw_to_buffer == false) {
				y0 = y-y_offset;
				if(is_longpress1_ok && isTouched()) { deltay = (int)(density * 2); }
			} else {
				y0 = y;
			}
			
			synchronized(dst_mt) {
				// 20140305: Disable this code segment and the 30K memory leak still existed.
				if(bmp != null && !bmp.isRecycled()) {
					bmp_paint.setFilterBitmap(true);
					int bw = bmp.getWidth(), bh = bmp.getHeight();
					dst_mt.set(x+deltay,y0+deltay,x+W-deltay,y0+H-deltay);
					NinePatchDrawable bk;
					if(staff_idx == 0) {
						bk = TommyConfig.getCurrentStyle().tile_bk_upper;
					} else bk = TommyConfig.getCurrentStyle().tile_bk_lower;
					bk.setBounds(dst_mt);
					bk.setTargetDensity(c);
					paint.setColor(TommyConfig.getCurrentStyle().background_color);
					bk.draw(c);
					src_mt.set(0, 0, bw, bh);
					c.drawBitmap(bmp, src_mt, dst_mt, bmp_paint);
				}
				
				if(gauge_mode != GaugeMode.NONE) {
					if(gauge > 0.0f) {
						int y1 = (int)(y0+deltay+(H-2*deltay)*(1.0f-gauge));
						int y2 = y0+H-deltay;
						paint_measure.setColor(curr_gauge_color);
						paint_measure.setStyle(Style.FILL);
						paint_measure.setAlpha(64);
						c.drawRect(x+deltay, y1, x+W-deltay, y2, paint_measure);
						paint_measure.setAlpha(255);
					}
				}
				
				drawHighlight(c);
				
				// Measure #
				{
					float xoffset = 0;
					if(measure_played.get(measure_idx) == true) {
						paint_measure.setColor(TommyConfig.getCurrentStyle().highlight_color);
					} else paint_measure.setColor(TommyConfig.getCurrentStyle().btn_text_color);
					paint_measure.setAntiAlias(true);
					paint_measure.setStrokeWidth(1.0f);
					paint_measure.setStyle(Style.FILL);
					paint_measure.setTextSize((sep_h-1)*density);
					paint_measure.setTextAlign(Align.LEFT);
					String midx_txt = "" + (1+measure_idx);
					if(staff_idx == 0) {
						c.drawText(midx_txt, x, y0-paint_measure.descent(), paint_measure);
					}
					xoffset = paint_measure.measureText(midx_txt);
					
					// Last Five
					int five = TommyConfig.FINEGRAINED_HISTORY_LENGTH;
					
					if(is_show_sub_separators) {
						if(gauge_mode == GaugeMode.LAST_5_ATTEMPTS) {
							paint_measure.setStyle(Style.FILL);				
							float x1 = paint.measureText(midx_txt) + x + 2;
							float increment = (sep_h_minor-4)*density;
							if(increment * five > (W - (x1-x))) { increment = (W-(x1-x))*1.0f/five; }
							for(int age = 0; age < TommyConfig.FINEGRAINED_HISTORY_LENGTH; age++) {
								dst.set((int)(x1), (int)(y0-sep_h_minor*density+2), (int)(x1+increment-2), y0-2);
								if(millis_hist[age] <= 0) {
									paint_measure.setColor(0xFF888888);
									c.drawRect(dst, paint_measure);
								} else {
									if(okclicks_hist[age] == true) {
										paint_measure.setColor(0xFF33FF33);
									} else paint_measure.setColor(0xFFFF3333);
									c.drawRect(dst, paint_measure);
								}
								x1 += increment;
							}
						}
						if(gauge_mode == GaugeMode.MASTERY_LEVEL) {
							paint_measure.setColor(TommyConfig.getCurrentStyle().btn_text_color);
							int actual_sidx = actual_staff_idx.get(staff_idx);
							StringBuilder sb = new StringBuilder();
							
							for(int i=0; i<measure_masteries.size(); i++) {
								int age = i;
								float m = measure_masteries.get(age).get(staff_idx).get(measure_idx);
								if(m!=-999.0f) {
									if(i>0) sb.append(",");
									else sb.append("M:");
									sb.append(m);
								} else break;
							}
							sb.append("|S:");
							sb.append(measure_mastery_states.get(actual_sidx).get(measure_idx));
							c.drawText(sb.toString(), x+xoffset, y0-paint_measure.descent(), paint_measure);
						}
					}
				}
			}
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			do_draw(c, false);
		}
		void drawOverDrawingCache(Canvas c, boolean is_ignore_visibility) {
			if(!is_visible() && !is_ignore_visibility) return;
			if(isTouched() || measure_idx == curr_sheet_playing_measure_idx) {
				paint.setStyle(Style.FILL);
				paint.setColor(TommyConfig.getCurrentStyle().background_color);
				c.drawRect(x, y-y_offset+H, x+W, y-y_offset, paint);
				prepareToDraw(false, true);
				do_draw(c, false);
			}
		}
		boolean is_visible() {
			if(y_offset < y-height) return false;
			if(y_offset > y+H) return false;
			return true;
		}
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				int y0 = y_offset+y-this.y;
				if(y0 > 0 && y0 < H && x > this.x && x < this.x+W) {
					touchx = x; touchy = y0;
					touchid = id;
					need_redraw = true;
					last_click_millis = System.currentTimeMillis();
					last_click_framecount = frame_count;
					is_longpress1_ok = false;
					bmp = bitmap_helper.getTileBitmapSynchronous(staff_idx, measure_idx);
					return true;
				} else {
					return false;
				}
			} else {
				if(touchid != id) {
					return false;
				}
			}
			return true;
		}
		boolean touchUp(int id) {
			if(id == touchid) {
				need_redraw = true;
				touchid = -1;
				update(System.currentTimeMillis());
				if(is_panning_y == false) {
					if(parent.isMidiPlayerPlaying()) {
						parent.stopMidiPlayer();
					} else {
						if(is_longpress1_ok) {
							parent.player.MoveToMeasureBegin(measure_idx);
							parent.startMidiPlayer();
						}
					}
				}
				is_longpress1_ok = false;
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
		void free() {
			bmp = null;
		}
		void update(long millis) {
			long delta = millis - last_click_millis;
			int delta_fc = frame_count - last_click_framecount;
			if(delta > CLICK_THRESHOLD && delta_fc > FRAME_CONUT_THRESHOLD) {
				is_longpress1_ok = true;
			}
		}
	}
	
	private synchronized void prepareSheetMusicTiles(int line_width) {
		sheet.ComputeMeasureHashesNoRender(line_width);
		float zoom = width * 1.0f / line_width;
		if(zoom > 1) zoom = 1;
		if(bitmap_helper != null) {
			bitmap_helper.bitmap_zoom = zoom;
		}
		measureHashes = sheet.getMeasureHashes();
		measureHeights = sheet.getMeasureHeights();
		measureWidths = sheet.getMeasureWidths();
		num_notes = sheet.getNumNotes();
	}
	
	private void initMidiFile() {
		midifile = new MidiFile(midi_data, midi_title);
		
		if(MidiSheetMusicActivity.sheet0 == null) {
			MidiSheetMusicActivity.sheet0 = new SheetMusic(ctx);
			sheet = MidiSheetMusicActivity.sheet0;
			sheet.is_tommy_linebreak = true; // Otherwise NullPointer Error
			sheet.is_first_measure_out_of_boundingbox = false;
			sheet.getHolder().setFixedSize(20, 20);
			sheet.init(midifile, options);
			sheet.setVisibility(View.GONE);
			sheet.tommyFree();
		} else {
			sheet = MidiSheetMusicActivity.sheet0;
			sheet.init(midifile, options);
		}
		
		CRC32 crc = new CRC32();
		crc.update(midi_data);
		checksum = crc.getValue();
		
		prepareSheetMusicTiles(LINE_WIDTHS[line_width_idx]);
		
		// Construct Boolean Array
		{
			num_measures = sheet.getNumMeasures();
			num_staffs   = sheet.getNumStaffs();
			num_tiles_total = 0;
			for(int i=0; i<num_staffs; i++) {
				for(int j=0; j<num_measures; j++) {
					num_tiles_total ++;
				}
			}
			for(int i=0; i<num_measures; i++) measure_played.add(false);
			num_playable_measures_to_play = num_measures; // Exclude the first
		}
		String hs_sz = prefs_highscores.getString(String.format("%x_HS", checksum), "");
		TommyConfig.populateHSTSArraysFromJSONString(highscores, timestamps, right_clicks_history, wrong_clicks_history, hs_sz);
		
		Log.v("TommyIntroView", "Highscore string = " + hs_sz);
		for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
			Log.v("TommyIntroView", String.format("HighScore %f = ", TommyConfig.BLANK_RATIOS[i]) + highscores.get(i));
		}
		
		
		bitmap_helper = new MeasureBitmapHelper(this, sheet, 1.0f);
	}
	
	
	public boolean isMeasureOutOfSight(int midx, int sidx) {
		int uid = getTileUIDFromMidSid(sidx, midx);
		return !(measure_tiles.get(uid).is_visible());
	}
	public TommyIntroView(Context context, Bundle bundle, byte[] _midi_data, 
			String _midi_name, String _midi_uri_string, MidiOptions _options,  
			TommyIntroActivity tia) {
		super(context);
		// Initialize buffer
		parent = tia;
		src = new Rect(); dst = new Rect();
		ctx = context.getApplicationContext(); // To prevent memory leak??
		this.options = _options;
		TommyConfig.init(ctx);
		midi_data = _midi_data;
		midi_title = _midi_name;
		midi_uri_string = _midi_uri_string;
		density = MidiSheetMusicActivity.density;
		paint = new Paint();
		paint.setAntiAlias(true);
		bmp_paint = new Paint();
		bmp_paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
		bmp_paint.setFilterBitmap(true);
		need_redraw = true;
		is_running = true;
		updater_thread = new Thread(this);
		updater_thread.setName("TommyIntroViewUpdaterThread");
		bmpworker_thread = new Thread(new BitmapWorkerRunnable());
		bitmap_rendering = ctx.getResources().getString(R.string.bitmap_loading);
		CRC32 crc = new CRC32();
		crc.update(midi_data);
		checksum = crc.getValue();
		for(int i=0; i<touches.length; i++) {
			touches[i] = new Vec2();
		}
		measure_tiles = new ArrayList<MeasureTile>();
		measure_played= new ArrayList<Boolean>();
		y_separators  = new ArrayList<Integer>();
		y_separators_heights = new ArrayList<Integer>();
		
		for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
			highscores.add(new ArrayList<Long>());
			timestamps.add(new ArrayList<Long>());
			right_clicks_history.add(new ArrayList<Integer>());
			wrong_clicks_history.add(new ArrayList<Integer>());
		}
		
		prefs_highscores = ctx.getSharedPreferences("highscores", Context.MODE_PRIVATE);
		prefs_lastplayed = ctx.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
		prefs_playcount  = ctx.getSharedPreferences("playcounts", Context.MODE_PRIVATE);
		prefs_quizcount  = ctx.getSharedPreferences("quizcounts", Context.MODE_PRIVATE);
		prefs_quizstats  = ctx.getSharedPreferences("quizstats",  Context.MODE_PRIVATE);
		prefs_finegrained= ctx.getSharedPreferences("finegrained", Context.MODE_PRIVATE);
		prefs_colorscheme= ctx.getSharedPreferences("colorscheme", Context.MODE_PRIVATE);
		String cksm = String.format("%x", checksum);
		num_played = prefs_playcount.getInt(midi_uri_string, 0);
		// Load last used zoom level.
		{
			line_width_idx = prefs_playcount.getInt(midi_uri_string+"_last_zoom_idx", LINE_WIDTHS.length - 1);
			if(line_width_idx < 0) line_width_idx = 0;
			if(line_width_idx > LINE_WIDTHS.length-1) line_width_idx = LINE_WIDTHS.length - 1;
		}
		num_quiz = prefs_quizcount.getInt(midi_uri_string, 0);
		
		initMidiFile();
		
		// Depends on Sheet. So, should execute after initMidiFile.
		int NT = sheet.getActualNumberOfTracks();
		{
			for(int i=0; i<NT; i++) {
				right_clicks_measures.add(new ArrayList<Integer>());
				wrong_clicks_measures.add(new ArrayList<Integer>());
				delays_measures.add(new ArrayList<Long>());
			}
			String quiz_stat_sz = prefs_quizstats.getString(cksm, "");
			Log.v("TommyIntroView", "quiz_stat_sz="+quiz_stat_sz+", NT="+NT);
			TommyConfig.populateQuizCoarseStatisticsFromJSONString(NT, num_measures, right_clicks_measures, wrong_clicks_measures,
				delays_measures, quiz_stat_sz);
			sheet.getVisibleActualTrackIndices(actual_staff_idx);
		}
		
		// Mastery States
		boolean is_mastery_status_except = false;
		{
			String mssz = prefs_quizstats.getString(cksm+"_mastery_states", "");
			for(int i=0; i<NT; i++) {
				measure_mastery_states.add(new ArrayList<Integer>());
			}
			is_mastery_status_except = TommyConfig.populateMasteryStateArrayFromJSONString(NT, num_measures, measure_mastery_states, mssz);
		}
		
		// Fine-grained history
		// If this takes a couple of seconds, shall we move it to another updater_thread?
		{
			String fghist = prefs_finegrained.getString(cksm, "");
			TommyConfig.populateQuizFineStaticsFromJSONString(NT, num_measures, okclicks_history_f, millis_history_f, fghist);
			prepareFineGrainedHistory();
		}
		readAllMasteryHistory();
		
		if(is_mastery_status_except) {
			reComputeAllMasteryHistory();
			for(int i=0; i<NT; i++) {
				for(int j=0; j<num_measures; j++) {
					measure_mastery_states.get(i).set(j, masteries.get(i).get(j).getMasteryState());
				}
			}
		}
		
		if(bundle != null) {
			this.bundle = bundle;
			need_load_state = true;
		}
		setGaugeMode(gauge_mode);
	}
	
	private void update() {
		synchronized(this) {
			long millis = System.currentTimeMillis();
			
			for(MeasureTile mt : measure_tiles) {
				mt.update(millis);
			}
			
			if(touches_on_bk == 1) {
				for(int i=0; i<invdpads.length; i++) {
					if(invdpads[i].isTouched()) {
						InvisibleDPad dpad = invdpads[i];
						pan_accumulated += Math.abs(dpad.deltay);
						if(pan_accumulated > PANNING_THRESHOLD) {
							if(is_panning_y == false) {
								is_panning_y = true;
								stopAutoScrollY();
								panning_y_start_millis = System.currentTimeMillis();
								panning_y_start_offset = y_offset;
							}
							{
								score_history.touchUp(score_history.touchid);
								button_panel.touchUp(button_panel.touchid);
								for(MeasureTile mt : measure_tiles) {
									mt.touchUp(mt.touchid);
								}
							}
						}
						
						if(Math.abs(dpad.deltay) > InvisibleDPad.MOVE_VELOCITY_THRESHOLD * density) {
							panning_y_start_millis = System.currentTimeMillis();
						}
						
						setYOffset(y_offset - dpad.deltay);
						need_redraw = true;
						dpad.clearDeltaXY();
					}
				}
			}
			
			int bytes_consumed = bitmap_helper.getBytesConsumed() + line_bitmap_helper.bytes_consumed;
			if(bytes_consumed > BITMAP_MEMORY_BUDGET) {
				Log.v("TommyIntroView", "Clearing Drawing Cache");
				synchronized(bmpworker_thread) {
					if(MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD == true) {
						bitmap_helper.clearOutOfSightBitmapsLRU(BITMAP_MEMORY_BUDGET*1/4, true);
						line_bitmap_helper.invalidateLRU(BITMAP_MEMORY_BUDGET*1/4);
					} else {
						bitmap_helper.clearOutOfSightBitmapsLRU(BITMAP_MEMORY_BUDGET*1/4, false);
					}
				}
			}
			
			yOffsetInertia();
			updateAutoScrollY(millis);
			
			if(sheet != null) {
				last_sheet_playing_measure_idx = curr_sheet_playing_measure_idx;
				curr_sheet_playing_measure_idx = sheet.getCurrentPlayingMeasure();
				if(curr_sheet_playing_measure_idx != last_sheet_playing_measure_idx) {
					if(parent.isMidiPlayerPlaying() || last_sheet_playing_measure_idx == num_measures-1) {
						if(last_sheet_playing_measure_idx >= 0 && last_sheet_playing_measure_idx < num_measures)
							onMeasurePlayed(last_sheet_playing_measure_idx);
					}
					need_redraw = true;
					// Focus on current measure
					int hl_measure_ymin = Integer.MAX_VALUE, hl_measure_ymax = Integer.MIN_VALUE;
					for(MeasureTile mt : measure_tiles) {
						if(mt.measure_idx == curr_sheet_playing_measure_idx) {
							if(mt.staff_idx == 0) {
								hl_measure_ymin = mt.y;
							} else if(mt.staff_idx == num_staffs - 1) {
								hl_measure_ymax = mt.y + mt.H;
							}
						}
					}
					
					// Out of screen upwards
					int scroll_to = (int)(hl_measure_ymin - SEPARATOR_HEIGHTS[line_width_idx] * density);
					if(hl_measure_ymin < y_offset) {
						autoScrollYTo(scroll_to);
					} else if(y_offset + height < hl_measure_ymax) {
						autoScrollYTo(scroll_to);
					}
				}
			} else {
				last_sheet_playing_measure_idx = curr_sheet_playing_measure_idx = -1;
			}
			need_redraw |= staybottom_bottom_panel.need_redraw;
		}
	}
		
	protected void do_draw(Canvas c) {
		frame_count++;
		c.drawColor(TommyConfig.getCurrentStyle().background_color);
		
		score_history.draw(c);
		button_panel.draw(c);
	
		if(MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD==false) {
			// draw separators
			NinePatchDrawable sep = TommyConfig.getCurrentStyle().background_separator;
			
			
			for(int i=0; i<y_separators.size(); i++) {
				int ys = y_separators.get(i);
				int h_sep = y_separators_heights.get(i);
				if(ys + h_sep > y_offset && ys < y_offset + height) {
					dst.set(0, (int)(ys-y_offset), width, (int)(ys-y_offset+h_sep));
					sep.setBounds(dst);
					sep.draw(c);
				}
			}
			
			for(MeasureTile mt : measure_tiles) {
				mt.draw(c);
			}
		} else {
			synchronized(paint) {
				int y = button_panel.y + button_panel.H - y_offset;
				for(int lidx=0; lidx<line_midx_start.size(); lidx++) {
					int lh = line_heights.get(lidx);
					if(!(y > height || y+lh < 0)) {
						Bitmap linebmp = line_bitmap_helper.getLineBitmap(lidx);
						if(linebmp != null && !linebmp.isRecycled()) {
							c.drawBitmap(linebmp, 0, y, paint);
						} else {
							paint.setColor(TommyConfig.getCurrentStyle().highlight_color);
							paint.setStyle(Style.FILL);
							paint.setTextAlign(Align.CENTER);
							paint.setTextSize(16 * density);
							c.drawText(bitmap_rendering, width/2, y+lh/2-(paint.ascent()+paint.descent()), paint);
						}
					}
					y = y + lh;
					
				}
				for(MeasureTile mt : measure_tiles) {
					mt.drawOverDrawingCache(c, true);
				}
			}
		}
		
		staybottom_bottom_panel.draw(c);

		// Draw Cache Status.
		if(MidiSheetMusicActivity.DEBUG) {
			synchronized(paint)
			{
				paint.setTextAlign(Align.CENTER);
				paint.setColor(Color.BLUE);
				paint.setTextSize(12 * density);
				paint.setStrokeWidth(density);
				paint.setStyle(Style.FILL);
				String sz = String.format("Frame %d, BMP=%.2fM+%.2fM/%.2fM bmpzoom=%.2f",
						frame_count,
						bitmap_helper.getBytesConsumed()/1048576.0f,
						line_bitmap_helper.bytes_consumed/1048576.0f,
						BITMAP_MEMORY_BUDGET/1048576.0f,
						bitmap_helper.bitmap_zoom
				);
				c.drawText(sz, width/2, height-14*density, paint);
			
				paint.setAntiAlias(false);
				{
				
					int y0 = (int)(height - 32*density);
					float x_increment = width * 1.0f / num_measures;
					float y_increment = 5 * density;
					paint.setColor(0xFF00FFFF);
					for(int i=0; i<num_staffs; i++) {
						float y1 = y0 + y_increment*i, y2 = y1 + y_increment;
						for(int j=0; j<num_measures; j++) {
							dst.set((int)(j*x_increment), (int)(y1), (int)((j+1)*x_increment), (int)(y2));
							int uid = getTileUIDFromMidSid(j, i);
							paint.setStyle(Style.STROKE);
							c.drawRect(dst, paint);
							if(bitmap_helper.cached_bmps.containsKey(uid)) {
								paint.setStyle(Style.FILL);
								paint.setColor(0xFF00DDFF);
								c.drawRect(dst, paint);
							} else {
							}
						}
					}
					
					y0 = (int)(y0 - y_increment - 4);
					for(int i=0; i<line_midx_start.size(); i++) {
						float x0 = line_midx_start.get(i) * x_increment, x1;
						if(i == line_midx_start.size() - 1) {
							x1 = num_measures * x_increment;
						}
						else x1 = line_midx_start.get(i+1) * x_increment;
						dst.set((int)(x0), (int)(y0), (int)(x1), (int)(y0+y_increment));
						Bitmap bmp = line_bitmap_helper.line_bmps.get(i);
						paint.setStyle(Style.STROKE);
						c.drawRect(dst, paint);
						if(bmp != null && bmp.isRecycled()==false) {
							paint.setStyle(Style.FILL);
							paint.setColor(0xFF00DDFF);
							c.drawRect(dst, paint);
						} else {
						}
					}
				}
				paint.setAntiAlias(true);
			}
		}

		
		need_redraw = false;
	}
	
	@Override
	protected void onDraw(Canvas c) {
		synchronized(this) {
			do_draw(c);
		}		
	}
	
	private synchronized void prepareAllTilesForDrawing() { 
		for(MeasureTile mt : measure_tiles) { mt.prepareToDraw(false, false); }
	}
	
	public void run() {
		while(!is_freed) {
			if(is_running) {
				long last_update_millis = 0;
				try {
					while(true) {
						long delta = System.currentTimeMillis() - last_update_millis;
						if(delta < FRAME_DELAY) {
							Thread.sleep(FRAME_DELAY - delta);
						}
						last_update_millis = System.currentTimeMillis(); 
						update();
						if(need_redraw) {
							if(!MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD) {
								prepareAllTilesForDrawing();
							}
							postInvalidate();
						}
						if(!is_running) break;
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
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
	
	private void updateBitmapMemoryBudget() {
		BITMAP_MEMORY_BUDGET = (int)(2 * width * height * 5.0f);
	}
	
	
	// Start layout of tiles from Y
	// Also, when LINE_WIDTH changed, this also needs be re-executed.
	private synchronized void layoutTiles(int y) {
		line_midx_start.clear();
		line_heights.clear();
		y_separators.clear();
		y_separators_heights.clear();
		// Separator before the first one
		y_separators.add(y);
		int SEPARATOR_HEIGHT = SEPARATOR_HEIGHTS[line_width_idx];
		y_separators_heights.add(SEPARATOR_HEIGHT);
		y = (int)(y  + SEPARATOR_HEIGHT*density);
		
		
		y_offset_max = 0;
		bitmap_helper.free();
		measure_tiles.clear();
		float zoom = width * 1.0f / LINE_WIDTHS[line_width_idx];
		int x = 0, x0 = 0;
		int sep_h_minor = (int)(MINOR_SEPARATOR_HEIGHTS[line_width_idx] * density);
		
		line_midx_start.add(0);
		
		for(int i=0; i<num_measures; i++) {
			int y1 = 0;
			int W = (int)(zoom*measureWidths.get(i)), H;

			for(int j=0; j<num_staffs; j++) {
				H = (int)(measureHeights.get(j)*zoom);
				MeasureTile mt = new MeasureTile(
					W, H, x, (y+y1),i,j);
				y1 = y1 + H;
				if(is_show_sub_separators) {
					if(j != num_staffs-1)
						y1 = y1 + sep_h_minor;
				}
				measure_tiles.add(mt);
			}
			
			
			int next_w = 0;
			x = x + W;

			x0 = x0 + measureWidths.get(i);
			if(i < num_measures - 1) {
				next_w = measureWidths.get(i+1);
				if(x0 + next_w > LINE_WIDTHS[line_width_idx]) {
					
					if(is_show_sub_separators) {
						int y2 = y;
						for(int k=0; k<num_staffs-1; k++) {
							y2 = y2 + (int)(measureHeights.get(k)*zoom);
							y_separators.add(y2);
							y_separators_heights.add(sep_h_minor);
							y2 = y2 + sep_h_minor;
						}
					}
					
					
					y = (int)(y + y1);
					y_separators.add(y);
					int sh = (int)(SEPARATOR_HEIGHT*density);
					y_separators_heights.add(sh);
					y = y + sh;
					x = 0;
					x0 = 0;
					line_midx_start.add(i+1);
					line_heights.add(sh + y1);
				}
			} 

			if(i == num_measures - 1) {
				y = y + y1;
				y_separators.add(y);
				int sh = (int)(SEPARATOR_HEIGHT*density);
				y_separators_heights.add(sh);
				y = y + sh;
				line_heights.add(sh + y1);
			}
		}
		if(y_offset_max < y - height) {
			y_offset_max = y - height;
		}
		
		y_offset_max += staybottom_bottom_panel.H;
		if(y_offset > y_offset_max) y_offset = y_offset_max;
		int y_visible_begin = button_panel.y + button_panel.H - staybottom_bottom_panel.H;
		if(y_visible_begin > y_offset_max - height) y_visible_begin = 0;
		staybottom_bottom_panel.y_visible_begin = y_visible_begin;
		
		need_redraw = true;
		computeMeasureTileGauge();
		prepareFineGrainedHistory();
		if(MidiSheetMusicActivity.USE_FAST_RENDERING_METHOD) {
		} else {
			for(MeasureTile mt : measure_tiles) { mt.prepareToDraw(false, false); }
		}

		Log.v("Fast Rendering Method", line_heights.size() + ", " + line_midx_start.size());
		line_bitmap_helper = new LineBitmapHelper(line_heights.size());
	}
	
	private synchronized void layout() {
		// Prepare a 2-screenful-sized buffer
		// Measurement of height here may not be accurate b/c the IME panel takes up half of the screen!!!
		updateBitmapMemoryBudget();
		MeasureTile firstmt = getFirstMeasureIntersectsTopOfScreen();
		
		bitmap_helper.bitmap_zoom = 1.0f * width / LINE_WIDTHS[line_width_idx];
		PANNING_THRESHOLD = (int)(12 * density);
		int y = 0;
		int h_sh = (int)(TEXT_HEIGHT*24*density-2);
		int h_btn = (int)(TEXT_HEIGHT*6*density-2);
		score_history = new ScoreHistoryPanel(width, h_sh, 0, y);
		y += h_sh;
		
		button_panel  = new ButtonPanel(width, h_btn, 0, y);
		y += h_btn;
		
		int h_btn_1 = (int)(TEXT_HEIGHT*3*density-2);
		staybottom_bottom_panel = new StayBottomButtonPanel(width, h_btn_1, 0);
		
		for(int i=0; i<invdpads.length; i++) {
			invdpads[i] = new InvisibleDPad();
		}
		layoutTiles(y);
		computeMeasureTileGauge();
		
		if(firstmt == null) setYOffset(0);
		else setYOffset(firstmt.y);
	}
	
	private void afterMeasure() {
		if(is_inited) return;
		updater_thread.start(); // 20140305 disabled this updater_thread, 30K memory leak problem persisted.
		bmpworker_thread.start();
		layout();
		is_inited = true;
	}
	
	private void readAllMasteryHistory() {
		int hlen = okclicks_history_f.size(); // available history length
		measure_masteries.clear();
		masteries.clear();
		for(int i=0; i<num_staffs; i++) {
			ArrayList<TommyMastery> m1 = new ArrayList<TommyMastery>();
			for(int j=0; j<num_measures; j++) {
				m1.add(new TommyMastery(measure_mastery_states.get(i).get(j)));
			}
			masteries.add(m1);
		}
		
		Log.v("Compute All MEasure Mastery History", "age="+hlen);
		for(int age=0; age<hlen; age++) {
			ArrayList<ArrayList<Float>> this_age = new ArrayList<ArrayList<Float>>();
			for(int i=0; i<num_staffs; i++) {
				ArrayList<Float> this_staff = new ArrayList<Float>();
				for(int j=0; j<num_measures; j++) {
					TommyMastery tm = masteries.get(i).get(j);
					this_staff.add(tm.getMasteryLevel());
				}
				this_age.add(this_staff);
			}
			measure_masteries.add(this_age);
		}
	}
	
	private void reComputeAllMasteryHistory() {
		int hlen = okclicks_history_f.size(); // available history length
		measure_masteries.clear();
		
		Log.v("Compute All MEasure Mastery History", "age="+hlen);
		for(int age=0; age<hlen; age++) {
			ArrayList<ArrayList<Float>> this_age = new ArrayList<ArrayList<Float>>();
			for(int i=0; i<num_staffs; i++) {
				ArrayList<Float> this_staff = new ArrayList<Float>();
				for(int j=0; j<num_measures; j++) {
					boolean isok = okclicks_history_f.get(age).get(i).get(j);
					long millis  = millis_history_f.get(age).get(i).get(j);
					if(millis > 0) {
						TommyMastery tm = masteries.get(i).get(j);
						tm.appendOutcome(isok);
						this_staff.add(tm.getMasteryLevel());
					} else {
						this_staff.add(-999.0f);
					}
				}
				this_age.add(this_staff);
			}
			measure_masteries.add(this_age);
		}
	}
	
	// Touch events handling

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
	

	/* ------------- Touch U.I. --------------- */
	Vec2[] touches = new Vec2[10];

	private void myTouchDown(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;

		{
			for(int i=0; i<invdpads.length; i++) {
				if(invdpads[i].touchDown(id, x, y)) break;
			}
		}
		
		if(!is_panning_y) {
			if(score_history.touchDown(id, x, y)) return;
			if(button_panel.touchDown(id, x, y)) return;
		}
		
		if(staybottom_bottom_panel.touchDown(id, x, y)) return;
		
		for(MeasureTile t : measure_tiles) {
			if(t.is_visible()) {
				t.touchDown(id, x, y);
				vel_y = 0;
			}
		}
	}
	
	private void myTouchUp(int id) {
		touches[id].x = -1; touches[id].y = -1;
		float speculative_vely = 0.0f;
		for(InvisibleDPad idp : invdpads) {
			if(idp.isTouched()) {
//				delta_t = System.currentTimeMillis() - idp.last_click_millis;
				speculative_vely = -idp.getWindowVelYPerMilliSecond() * 16.0f;
				idp.clearWindowEntries();
			}
			idp.touchUp(id);
		}
		if(touches_on_bk == 0) { // start inertial
			vel_y = speculative_vely;
			checkVelY();
			is_panning_y = false;
			pan_accumulated = 0;
		}
		score_history.touchUp(id);
		button_panel.touchUp(id);
		staybottom_bottom_panel.touchUp(id);

		for(MeasureTile t : measure_tiles) {
			if(t.is_visible()) t.touchUp(id);
		}
	}
	
	private void myTouchMove(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;
		for(InvisibleDPad idp : invdpads) {
			idp.touchMove(id, x, y);
		}
	}
	
	// Copied from that of TommyView2
	@Override 
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
	   int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
	   int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
	   this.setMeasuredDimension(parentWidth, parentHeight);
	   width = parentWidth; height = parentHeight;
	   Log.v("TommyIntroView.onMeasure", String.format("W=%d, H=%d", width, height));
	   super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	   setMeasuredDimension(width, height);
	   updateBitmapMemoryBudget();
	   afterMeasure();
	   if(need_load_state) {
		   loadState(this.bundle);
		   need_load_state = false;
		   this.bundle = null;
	   }
	}

	public void pause() {
		is_running = false;
	}
	
	public void resume() {
		is_running = true;
	}
	
	// Refer to this issue here:
	// http://stackoverflow.com/questions/18284988/android-4-3-paint-paintstyle-being-ignored-sometimes
	//
	// Some drawing commands are GONE on my TouchPad running 4.3  :((((((
	// Some are disappearing on One X, too (What is happening!!!!)
	//
	
	/*
	@SuppressLint("InlinedApi")
	@Override
	public void onAttachedToWindow() {
		int api_ver = android.os.Build.VERSION.SDK_INT;
		if(api_ver == 0x12) { // 4.3
			setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		} else {
			super.onAttachedToWindow();			
		}
	}
	@SuppressLint("InlinedApi")
	@Override
	public void onDetachedFromWindow() {
		int api_ver = android.os.Build.VERSION.SDK_INT;
		if(api_ver == 0x12) { // 4.3
			setLayerType(View.LAYER_TYPE_NONE, null);
		} else {
			super.onDetachedFromWindow();			
		}
	}*/
}
