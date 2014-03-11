package com.midisheetmusicmemo;

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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.midisheetmusicmemo.SheetMusic.Vec2;

// 2014-03-02: Refactor to prepare for more refactoring --- bitmap cache
// 2014-03-03: Tested on Android 4.3 and attempting to find the cause of the memory leak
// 2014-03-06: Cause of memory leak bug = Forgot to finish the updater thread

public class TommyView2 extends View implements Runnable {
	boolean is_freed = false;
	Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
	private boolean DEBUG = MidiSheetMusicActivity.DEBUG;
	private boolean is_running;
	private final int FRAME_DELAY = 25;
	float density;
	Random rnd = new Random();
	boolean is_inited = false,
			is_ui_created = false;
	long elapsed_millis, last_update_millis;
	
	int AREA1_HGT, AREA1_bitmap_hgt0; // Recycling Area Height
	int width, height;
	float AREA1_zoom_x, AREA1_zoom_y; // Preset zoom, can be adjusted by user.
	float blanks_ratio = 0.5f;
	int frame_count = 0;
	
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
	int num_measures, num_staffs, num_tiles_total, num_tiles_hidden, num_notes;
	String midi_title = "No title";
	byte[] midi_data;
	int recycle_area_score_x_offset = 0; // for state change only
	ArrayList<TileInAnimation> tiles_in_animation = new ArrayList<TileInAnimation>();
	
	boolean need_redraw = false;
	SharedPreferences prefs_highscores, prefs_lastplayed, prefs_playcount;
	int num_times_played = 0; long checksum;
	ArrayList<Long> highscores_25 = new ArrayList<Long>(), 
			highscores_50 = new ArrayList<Long>(), 
			highscores_75 = new ArrayList<Long>(), 
			highscores_100 =new ArrayList<Long>();
	ArrayList<Long> timestamps_25 = new ArrayList<Long>(),
			timestamps_50 = new ArrayList<Long>(),
			timestamps_75 = new ArrayList<Long>(),
			timestamps_100= new ArrayList<Long>();
	
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
		bundle.putInt("recycle_area_score_x_offset", recycle_area.score_x_offset);
		bundle.putInt("num_notes", num_notes);
		bundle.putString("title", midi_title);
		bundle.putLong("last_update_millis", last_update_millis);
		bundle.putLong("elapsed_millis", elapsed_millis);
		bundle.putSerializable("game_state", game_state);
		bundle.putLong("checksum", checksum);
		bundle.putInt("num_times_played", num_times_played);
		bundle.putSerializable("highscores_25", highscores_25);
		bundle.putSerializable("highscores_50", highscores_50);
		bundle.putSerializable("highscores_75", highscores_75);
		bundle.putSerializable("highscores_100", highscores_100);
		bundle.putSerializable("measure_heights", measureHeights);
		bundle.putSerializable("measure_widths", measureWidths);
		bundle.putFloat("blanks_ratio", blanks_ratio);
		is_running = false;
	}
	
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
		recycle_area_score_x_offset = bundle.getInt("recycle_area_score_x_offset");
		num_notes = bundle.getInt("num_notes");
		midi_title = bundle.getString("title");
		last_update_millis = bundle.getLong("last_update_millis");
		game_state = (GameState)(bundle.getSerializable("game_state"));
		elapsed_millis = bundle.getLong("elapsed_millis");
		checksum = bundle.getLong("checksum");
		num_times_played = bundle.getInt("num_times_played");
		highscores_25 = (ArrayList<Long>)bundle.getSerializable("highscores_25");
		highscores_50 = (ArrayList<Long>)bundle.getSerializable("highscores_50");
		highscores_75 = (ArrayList<Long>)bundle.getSerializable("highscores_75");
		highscores_100= (ArrayList<Long>)bundle.getSerializable("highscores_100");
		measureHeights = (ArrayList<Integer>)bundle.getSerializable("measure_heights");
		measureWidths  = (ArrayList<Integer>)bundle.getSerializable("measure_widths");
		sheet = MidiSheetMusicActivity.sheet0;
		bitmap_helper = new BitmapHelper(this, sheet, AREA1_zoom_x, AREA1_zoom_y);
		blanks_ratio = bundle.getFloat("blanks_ratio");
		Toast.makeText(ctx, "Loaded state", Toast.LENGTH_SHORT).show();
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
				Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
				Rect dst = new Rect(x, y, x+w, y+h);
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
			Toast.makeText(ctx, "Game finished! Congrats!", Toast.LENGTH_LONG).show();
			game_state = GameState.FINISHED;
			SharedPreferences.Editor editor = prefs_highscores.edit();
			SharedPreferences.Editor editor_playcount = prefs_playcount.edit();
			SharedPreferences.Editor editor_lastplay  = prefs_lastplayed.edit();
			
			String key1 = String.format("%x", checksum), key2 = midi_title;
			
			// Update # plays of the current file.
			num_times_played ++;
			editor_playcount.putInt(key1, num_times_played);
			editor_playcount.putInt(key2, num_times_played);
			editor_playcount.commit();
			
			long last_play_us = System.currentTimeMillis();
			editor_lastplay.putLong(key1, last_play_us);
			editor_lastplay.putLong(key2, last_play_us);
			editor_lastplay.commit();
			
			long curr_millis = System.currentTimeMillis(); 
			
			// Update histories of the current file.
			if(blanks_ratio == 0.25f) { 
				highscores_25.add(elapsed_millis);
				timestamps_25.add(curr_millis);
			} else if(blanks_ratio == 0.50f) { 
				highscores_50.add(elapsed_millis);
				timestamps_50.add(curr_millis);
			} else if(blanks_ratio == 0.75f) { 
				highscores_75.add(elapsed_millis);
				timestamps_75.add(curr_millis);
			} else if(blanks_ratio == 1.00f) { 
				highscores_100.add(elapsed_millis);
				timestamps_100.add(curr_millis);
			}
			
			StringBuilder hssb = new StringBuilder();
			hssb.append(TommyConfig.HSTSArraysToJSONString(highscores_25, timestamps_25));
			hssb.append(":");
			hssb.append(TommyConfig.HSTSArraysToJSONString(highscores_50, timestamps_25));
			hssb.append(":");
			hssb.append(TommyConfig.HSTSArraysToJSONString(highscores_75, timestamps_75));
			hssb.append(":");
			hssb.append(TommyConfig.HSTSArraysToJSONString(highscores_100, timestamps_100));
			Log.v("TommyView2", "New HS String="+hssb.toString());
			
			editor.putString(String.format("%x_HS", checksum), hssb.toString());
			editor.commit();
		}
	}
	
	// Widgets.
	InvisibleDPad[] invdpads = new InvisibleDPad[10];
	int touches_on_bk = 0;
	RecycleArea recycle_area = null;
	ArrayList<SelectionTile> tiles = new ArrayList<SelectionTile>();
	
	class InvisibleDPad {
		final static int TRIPLECLICK_WINDOW = 300; // 300 ms
		int touchx, touchy; // Screen coordinates
		int deltax, deltay;
		int touchid = -1; long last_click_millis = 0;
		int click_count = 0;
		public void clearDeltaXY() { deltax = deltay = 0; }
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				touchx = x; touchy = y;
				touchid = id;
				touches_on_bk ++;
				last_click_millis = System.currentTimeMillis();
				click_count ++;
				if(click_count == 3) {
					click_count = 0;
					recycle_area.toggleZoom();
				}
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
			if(millis - last_click_millis > TRIPLECLICK_WINDOW) {
				click_count = 0;
			}
		}
	}
	
	// For making choice.
	class SelectionTile {
		boolean need_redraw = false;
		int W, H, x, y, touchid, touchx, touchy, staff_idx, measure_idx, pad;
		int shake_delta_x = 0;
		int bmp_x, bmp_y, bmp_hw, bmp_hh;
		float zoom;
		long shake_begin_millis = 0;
		final static long SHAKE_DURATION = 500;
		boolean is_shaking = false;
		int id = 0;
		
		public SelectionTile(int _x, int _y, int w, int h) {
			x = _x; y = _y; W = w; H = h; pad = (int)(4*density);
			staff_idx = measure_idx = -1;
			touchid = -1;
			shake_delta_x = 0;
		}
		
		public void setMeasure(int _staff_idx, int _measure_idx) {
			need_redraw = true;
			if(_staff_idx == -1 || _measure_idx == -1) {; }
			else {
				measure_idx = _measure_idx; staff_idx = _staff_idx;
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
				
				measures_status.get(_staff_idx).set(_measure_idx, MeasureStatus.IN_SELECTION);
			}
			Log.v("SelectionTile", String.format("X=%d Y=%d zoom=%f", x, y, zoom));
		}
		
		public void draw(Canvas c) {
			if(touchid != -1) {
				paint.setColor(0xFFC0FFC0);
				paint.setStyle(Style.FILL);
				c.drawRect(x, y, x+W, y+H, paint);
			}
			
			if(staff_idx != -1 && measure_idx != -1) {
				Bitmap bmp = bitmap_helper.getTileBitmap(staff_idx, measure_idx);
				paint.setFilterBitmap(true);
				Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
				Rect dst = new Rect(bmp_x + shake_delta_x, bmp_y, bmp_x+2*bmp_hw + shake_delta_x, bmp_y+2*bmp_hh);
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
					paint.setColor(0xFFC0C0C0);
					String x;
					switch(id) {
					case 0:
						x = "25% blank"; break;
					case 1:
						x = "50% blank"; break;
					case 2:
						x = "75% blank"; break;
					case 3:
						x = "All blank"; break;
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
			
			need_redraw = false;
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
		
		private void onTileChosenInGame() {
			if(staff_idx != -1 && measure_idx != -1) {
					int hash_my = measureHashes.get(staff_idx).get(measure_idx),
						hash_ans = measureHashes.get(curr_hl_staff).get(curr_hl_measure);
					Log.v("touchUp", String.format("hash=%x, %x", hash_my, hash_ans));
					if(hash_ans == hash_my) {
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
	
							{
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
		
		int W, H, x, y, touchx, touchy, touchid, deltax, deltay, touch_count;
		float vel_x;
		long last_inertia_movt_millis, last_touch_millis;
		boolean is_inertia;
		
		private int score_x_offset; // 自譜子的bmp的x=score_x_offset處開始顯示。
		int score_x_offset_max, score_x_offset_min;
		float zoom_y, zoom_0, zoom_1;
		int next_zoom_idx = 1;
		float zoom_x, zoom_x_start, zoom_x_end;
		
		// Intro and Outro
		// UNIT: SCREEN
		int intro_width, outro_width;
		
		// Animation
		float zoom_start, zoom_end;
		int score_x_offset_start, score_x_offset_end;
		long anim_begin_millis, zoom_begin_millis;
		static final int ANIM_DURATION = 500; // 500 ms
		boolean is_in_fling = false, is_in_zooming = false;
		
		public RecycleArea(int _W, int _H, float _zoom_x, float _zoom_y) {
			W = _W; H = _H; 
			zoom_x = zoom_0 = _zoom_x;
			zoom_y = _zoom_y;
			zoom_1 = zoom_0 * 0.5f;
			x = 0; y = 0; vel_x = 0; touchid = -1;
			intro_width = H*2; outro_width = H*2;
			touch_count = 0;
			Log.v("RecycleArea", String.format("W=%d H=%d", W, H));
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
			recycle_area_score_x_offset = score_x_offset = score_x_offset_min = (int)(-intro_width / zoom_x);
		}
		
		void getTileXYOffsetZoom(int idx_staff, int idx_measure, int[] xy) {
			int x = tiles_x.get(idx_measure);
			int y = 0;
			if(idx_staff == 1) y += bitmap_helper.getTileBitmapHeight(0, 0);
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
						paint.setColor(0xFFC0C0FF);
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
						Rect src = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
						Rect dst = new Rect(x, y, x+dx, y+dy);
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
			paint.setColor(Color.GRAY);
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
				Rect bb = new Rect();
				
				long delta = elapsed_millis;
				float seconds_elapsed = delta / 1000.f;
				
				if(isIntroVisible()) {
					int intro_x0 = (int)(-score_x_offset * zoom_x - intro_width);
					paint.setStrokeWidth(2.0f*density);
					c.drawRect(intro_x0, y, intro_x0+intro_width, y+H, paint);
					paint.setStrokeWidth(1.0f*density);
					
					{
						paint.setTextSize(H/6.0f);
						paint.setStyle(Style.FILL);
						int y1 = y + pad + (int)(12*density);
						String s = midi_title;
						paint.getTextBounds(s, 0, s.length(), bb);
						c.drawText(s, intro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
						y1 = y1 + bb.bottom - bb.top;
						
						s = String.format("%d measures, %d notes", num_measures, num_notes);
						paint.getTextBounds(s, 0, s.length(), bb);
						c.drawText(s, intro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
						y1 = y1 + bb.bottom - bb.top;

						s = String.format("%.1f seconds", seconds_elapsed);
						paint.getTextBounds(s, 0, s.length(), bb);
						c.drawText(s, intro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
						y1 = y1 + bb.bottom - bb.top;

						s = String.format("%d plays", num_times_played);
						paint.getTextBounds(s, 0, s.length(), bb);
						c.drawText(s, intro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
					}
				}
				if(isOutroVisible()) {
					int outro_x0 = (int)((score_x_offset_max - score_x_offset) * zoom_x);
					c.drawRect(outro_x0, y, outro_x0+outro_width, y+H, paint);
					paint.setTextSize(H/6.0f);
					paint.setStyle(Style.FILL);

					int y1 = y + pad + (int)(12*density);
					String s;
					if(game_state == GameState.PLAYING) {
						s = String.format("%d / %d tiles to go!", num_tiles_total, num_tiles_hidden);
					} else if(game_state == GameState.NOT_STARTED) {
						s = "Please make a choice";
					} else {
						s = "Well done!";
					}
					paint.getTextBounds(s, 0, s.length(), bb);
					c.drawText(s, outro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
					y1 = y1 + bb.bottom - bb.top;
					
					s = String.format("%.1f seconds", seconds_elapsed);
					paint.getTextBounds(s, 0, s.length(), bb);
					c.drawText(s, outro_x0 + pad, y1 + (bb.bottom-bb.top)/2, paint);
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
		public boolean panScoreByScreenX(int dx) {
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
			long delta = millis - last_inertia_movt_millis;
			last_inertia_movt_millis = millis;
			float vel_dec = delta / 10;
			if(vel_x > 0) vel_dec = vel_dec * (-1);
			float vel_x_new = vel_x + vel_dec;
			if(vel_x > 0) {
				if(vel_x_new < 0) {
					vel_x_new = 0;
					is_inertia = false;
				}
			} else {
				if(vel_x_new > 0) {
					vel_x_new = 0;
					is_inertia = false;
				}
			}
			if(panScoreByScreenX((int)vel_x)) vel_x_new = 0.0f; // If true then Out Of Bounds.
			vel_x = vel_x_new;
		}
		
		private void startAnimMoveTo(int score_x_target) {
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
			if(!is_in_fling) {
				if(touchid == -1) {
					recycle_area.updateInertialMovement(millis);
				} else {
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
		
		
		boolean touchDown(int id, int tx, int ty) {
			if(tx > x && tx < x+W && ty > y && ty < y+H) {
				touchx = tx; touchy = ty;
				touchid = id;
				vel_x = 0;
				last_touch_millis = System.currentTimeMillis();
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
				deltay += y - touchy;
				touchx = x;
				touchy = y;
			} else {
				;
			}
			return true;
		}
		
		boolean touchUp(int id) {
			if(id == touchid) {
				int dx = deltax;//, dy = d.deltay;
				recycle_area.vel_x = dx;
				recycle_area.panScoreByScreenX(dx);
				touchid = -1;
				return true;
			}
			return false;
		}

		public void setScoreXOffset(int x) {
			if(x < score_x_offset_min) x = score_x_offset_min;
			if(x > score_x_offset_max) x = score_x_offset_max;
			score_x_offset = x;
		}
	};
	
	private void layout1() {
		AREA1_zoom_x = AREA1_zoom_y = 1.0f * AREA1_HGT / AREA1_bitmap_hgt0;
		recycle_area = new RecycleArea(width, AREA1_HGT, AREA1_zoom_x, AREA1_zoom_y);
		// 2 times the num of pixels * color depth (16b)
		BITMAP_MEM_BUDGET = (int)(2 * 2 *width * height * (1.0f*AREA1_bitmap_hgt0 / AREA1_HGT));
		
		recycle_area.computeXPositions();
		final int N_COLS = 4, N_ROWS = 2;
		final int TILEW = width/N_COLS, TILEH = (height*2/3)/2;
		tiles.clear();
		int tile_id = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = j * TILEW, y = i * TILEH + height/3;
				SelectionTile st = new SelectionTile(x, y, TILEW, TILEH);
				st.id = tile_id;
				tile_id ++;
				tiles.add(st);
			}
		}
		
		bitmap_helper.bitmap_zoom_x = 1.0f;
		bitmap_helper.bitmap_zoom_y = 1.0f;
	}
	
	private void layout2() {
		AREA1_zoom_x = AREA1_zoom_y = 1.0f * AREA1_HGT / AREA1_bitmap_hgt0;
		recycle_area = new RecycleArea(width, (int)(AREA1_HGT), AREA1_zoom_x, AREA1_zoom_y);
		BITMAP_MEM_BUDGET = (int)(2 * 2 * width * height * (1.0f*AREA1_bitmap_hgt0 / AREA1_HGT));

		recycle_area.computeXPositions();
		final int N_COLS = 2, N_ROWS = 4;
		final int TILEW = width/N_COLS, TILEH = (int)((height-AREA1_HGT)/N_ROWS);
		tiles.clear();
		int tile_id = 0;
		for(int i=0; i<N_ROWS; i++) {
			for(int j=0; j<N_COLS; j++) {
				int x = j * TILEW, y = i * TILEH + (int)(AREA1_HGT);
				SelectionTile st = new SelectionTile(x, y, TILEW, TILEH);
				st.id = tile_id;
				tile_id ++;
				tiles.add(st);
			}
		}

		bitmap_helper.bitmap_zoom_x = 1.0f;
		bitmap_helper.bitmap_zoom_y = 1.0f;
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
		MidiSheetMusicActivity.sheet0 = new SheetMusic(ctx);
		sheet = MidiSheetMusicActivity.sheet0;
		sheet.getHolder().setFixedSize(200, 200);
		sheet.init(midifile, null);
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

		String hs_sz = prefs_highscores.getString(String.format("%x_HS", checksum), "");
		Log.v("initMidiFile", "Historical Scores: " + hs_sz);
		num_times_played = prefs_playcount.getInt(String.format("%x", checksum), 0);
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
				layout1();
			} else {
				AREA1_HGT = (int)(height * 0.25f);
				layout2();
			}
		}
	}
	
	public TommyView2(Context context, Bundle icicle, byte[] _data, String _title) {
		super(context);
		midi_data = _data; midi_title = _title;
		density = MidiSheetMusicActivity.density;
		ctx = context;
		prefs_highscores = ctx.getSharedPreferences("highscores", Context.MODE_PRIVATE);
		prefs_lastplayed = ctx.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
		prefs_playcount  = ctx.getSharedPreferences("playcounts", Context.MODE_PRIVATE);
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
		last_heap_memory_alloc = Debug.getNativeHeapAllocatedSize();
	}

	@Override
	public void run() {
		while(!is_freed) {
			if(is_running) {
				long last_millis = 0;
				try {
					while(true) {
						long curr_millis = System.currentTimeMillis();
						long delta = curr_millis - last_millis;
						if(delta < FRAME_DELAY) {
							Thread.sleep(FRAME_DELAY - delta);
						}
						if(frame_count > 1 && game_state == GameState.PLAYING) {
							elapsed_millis += (curr_millis - last_millis);
						}
						last_millis = curr_millis;
						update();
						if(DEBUG) {
							if(frame_count % 100 == 0) {
								last_heap_memory_alloc = Debug.getNativeHeapAllocatedSize();
							}
						}
						if(need_redraw) 
							postInvalidate();
						if(!is_running) break; // fixed on 20140306
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
	
	private void do_draw(Canvas c) {
		frame_count ++;
		c.drawColor(Color.GRAY);
		int w = this.getWidth();

		if(recycle_area != null) recycle_area.draw(c);
		
		for(SelectionTile st : tiles) st.draw(c);
		
		synchronized(tiles_in_animation) {
			for(TileInAnimation tia : tiles_in_animation) tia.draw(c);
		}
		
		if(DEBUG)
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
		do_draw(c);
	}
	
	private void update() {
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
		
		for(InvisibleDPad ivd : invdpads) {
			if(ivd != null) 
			ivd.update(millis); 
		}
		
		int bytes_consumed = bitmap_helper.getBytesConsumed();
		if(bytes_consumed > BITMAP_MEM_BUDGET) {
			bitmap_helper.clearOutOfSightBitmaps();
		}
	}
	
	
	/* ------------- Touch U.I. --------------- */
	Vec2[] touches = new Vec2[10];

	private void myTouchDown(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;

		if(recycle_area.touchDown(id, x, y)) return;
		
		// Step 2: Process music puzzle pieces
		{
			for(SelectionTile st : tiles) {
				if(st.touchDown(id, x, y)) return;
			}
		}
		
		// Step 3: Panning and zooming
		{
			for(int i=0; i<invdpads.length; i++) {
				if(invdpads[i].touchDown(id, x, y)) return;
			}
		}
		
	}
	
	private void myTouchUp(int id) {
		touches[id].x = -1; touches[id].y = -1;
		for(InvisibleDPad idp : invdpads) {
			idp.touchUp(id);
		}
		if(touches_on_bk == 0) { // start inertial
			recycle_area.last_inertia_movt_millis = System.currentTimeMillis();
			recycle_area.is_inertia = true;
		}
		for(SelectionTile st  : tiles) st.touchUp(id);
		recycle_area.touchUp(id);
	}
	
	private void myTouchMove(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;
		for(InvisibleDPad idp : invdpads) {
			idp.touchMove(id, x, y);
		}
		for(SelectionTile st : tiles) { st.touchMove(id, x, y); }
		recycle_area.touchMove(id, x, y);
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
	    MidiSheetMusicActivity.sheet0 = null;
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
