package com.midisheetmusicmemo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.zip.CRC32;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
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
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.midisheetmusicmemo.SheetMusic.Vec2;

public class TommyIntroView extends View implements Runnable {
	// boilerplate stuff
	boolean is_freed = false;
	final int LINE_WIDTH = 800; // Line Width On The Virtual Bitmap
	TommyIntroActivity parent;
	private int width, height;
	Thread thread;
	Paint paint, bmp_paint;
	int frame_count, touches_on_bk;
	float density;
	Context ctx;
	private int BITMAP_MEMORY_BUDGET = 0;
	private static final int FRAME_DELAY = 25;
	private static final int LINE_HEIGHT = 13;
	private static boolean DEBUG = false;
	boolean need_redraw, is_running, is_inited;
	public SheetMusic sheet;
	private int last_sheet_playing_measure_idx = -1, curr_sheet_playing_measure_idx = -1;
	BitmapHelper bitmap_helper;
	byte[] midi_data; String midi_title;
	long checksum;
	// !!! I'm using `measure' and `tile' interchangeably, which I shouldn't !
	ArrayList<ArrayList<Integer>> measureHashes;
	ArrayList<Integer> measureWidths;
	ArrayList<Integer> measureHeights;
	int num_notes;
	int num_measures, num_staffs, num_tiles_total;
	SharedPreferences prefs_highscores, prefs_lastplayed, prefs_playcount;
	ArrayList<ArrayList<Long>> highscores = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Long>> timestamps = new ArrayList<ArrayList<Long>>();
	ArrayList<ArrayList<Integer>> right_clicks = new ArrayList<ArrayList<Integer>>();
	ArrayList<ArrayList<Integer>> wrong_clicks = new ArrayList<ArrayList<Integer>>();
	
	int num_played;

	void free() {
		bitmap_helper.free();
		sheet.free();
		sheet = null;
		MidiSheetMusicActivity.sheet0 = null;
		is_freed = true;
		is_running = false;
		thread = null;
		midi_data = null;
		measure_tiles.clear();
		paint = null;
		parent = null;
		for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
			highscores.get(i).clear();
			timestamps.get(i).clear();
			right_clicks.get(i).clear();
			wrong_clicks.get(i).clear();
		}
		highscores.clear();
		timestamps.clear();
		right_clicks.clear();
		wrong_clicks.clear();
		System.gc();
	}
	
	int y_offset = 0, y_offset_max = 100; // Imagine everything laid out on a plane. The View is a sliding window put at y=y_offset.
	int pan_accumulated = 0, PANNING_THRESHOLD;
	boolean is_panning_y = false;
	
	float vel_y = 0.0f;
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
		vel_y = vel_y * 0.95f;
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
	class BitmapHelper {
		SheetMusic sheet;
		TommyIntroView view;
		private int bytes_consumed;
		float bitmap_zoom;
		BitmapHelper(TommyIntroView _tv, SheetMusic _sheet, float _bz) {
			bytes_consumed = 0;
			view = _tv; sheet = _sheet;
			bitmap_zoom = _bz;
		}
		@SuppressLint("UseSparseArrays")
		HashMap<Integer, Bitmap> cached_bmps = new HashMap<Integer, Bitmap>();
		LinkedHashMap<Integer, Long>   last_access = new LinkedHashMap<Integer, Long>();
		
		Bitmap getTileBitmap(int staff_idx, int measure_idx) {
			int uid = view.getTileUIDFromMidSid(measure_idx, staff_idx);
			if(cached_bmps.containsKey(uid) == false) {
				Bitmap bmp = sheet.RenderTile(measure_idx, staff_idx, bitmap_zoom, bitmap_zoom);
				bytes_consumed += bmp.getHeight() * bmp.getWidth() * 2; // RGB 565, 1 pixel = 2 bytes
				synchronized(cached_bmps) {
					cached_bmps.put(uid, bmp);
				}
			}
			last_access.put(uid, System.currentTimeMillis());
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
			last_access.remove(key);
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
					
					if(view.isMeasureOutOfSight(midx, sidx)) {
						to_delete.add(x);
					}
				}
				for(Integer y : to_delete) {
					do_clearBmp(y);
				}
			}
		}
		public void clearOutOfSightBitmapsLRU(int size_cap) {
			// Sort by usage time
			synchronized(cached_bmps) {
				while(bytes_consumed > size_cap) {
					Integer key_min = -999;
					long min_millis = Long.MAX_VALUE;
					for(java.util.Map.Entry<Integer, Long> ety : last_access.entrySet()) {
						if(min_millis > ety.getValue()) {
							key_min = ety.getKey();
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
			cached_bmps = null;
			System.gc();
		}
	}
	//============================================================
	
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
	InvisibleDPad invdpads[] = new InvisibleDPad[10];
	
	// Sub-view components
	class ScoreHistoryPanel {
		int W, H, x, y, touchid = -1, touchx, touchy, pad_top, pad_bottom, pad_left;
		long last_click_millis;
		int pad_button, H_button, H_button_indicator;
		int dy_btnrow1, dy_btnrow2, dy_curves;
		final String[] btnrow1_labels = {"25%", "50%", "75%", "100%"};
		final String[] btnrow2_labels = {"Time", "Accuracy"}; // Radio button
		boolean[] btnrow1_flags = {true, true, true, true, true};
		
		int btnrow1_hl_idx = -1, btnrow2_hl_idx = -1;
		
		int btnrow2_choice_idx = 0;
		int[] btnrow1_x, btnrow1_w, btnrow2_x, btnrow2_w;
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
				W_curves = (int) (W - 3*density - 2*pad_left - paint.measureText(String.format("%.1fs", max_elapsed/10000.0f)));
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
			dy_curves = dy_btnrow2 + H_button + (int)(5*density);
			
			
			int lblcnt1 = btnrow1_labels.length;
			btnrow1_w = new int[lblcnt1]; btnrow1_x = new int[lblcnt1];
			int lblcnt2 = btnrow2_labels.length;
			btnrow2_w = new int[lblcnt2]; btnrow2_x = new int[lblcnt2];
			
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
			
			layoutCurveGraph();
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			int y0 = y-y_offset;

			NinePatchDrawable bk = TommyConfig.getCurrentStyle().background1;
			bk.setBounds(x, y0, x+W, y0+H);
			bk.draw(c);
			
			paint.setStyle(Style.STROKE);
			paint.setColor(Color.RED);
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
				String played = String.format("Played %d times", num_played);
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
			NinePatchDrawable btn_bk = TommyConfig.getCurrentStyle().background1;
			{
				y0 = y-y_offset + dy_btnrow1;
				for(int i=0; i<btnrow1_labels.length; i++) {
					int x1 = btnrow1_x[i];
					int w1 = btnrow1_w[i];
					paint.setColor(0xFF808080);
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
					paint.setColor(0xFF808080);
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
					ArrayList<Integer> right_clks = right_clicks.get(optidx);
					ArrayList<Integer> wrong_clks = wrong_clicks.get(optidx);
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
				}
			}
			btnrow1_hl_idx = -1;
			btnrow2_hl_idx = -1;
			need_redraw = true;
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
	}
	ScoreHistoryPanel score_history;
	
	class ButtonPanel {
		int W, H, x, y, touchid = -1, touchx, touchy;
		long last_click_millis;
		ButtonPanel(int _w, int _h, int _x, int _y) {
			W = _w; H = _h; x = _x; y = _y;
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			
			int y0 = y-y_offset;
			paint.setStyle(Style.STROKE);
			paint.setColor(Color.GREEN);
			paint.setStrokeWidth(density);
			
			NinePatchDrawable bk = TommyConfig.getCurrentStyle().background1;
			bk.setBounds(x, y0, x+W, y0+H);
			bk.setTargetDensity(c);
			bk.draw(c);
			
			if(isTouched()) {
				paint.setStyle(Style.FILL);
			} else paint.setStyle(Style.STROKE);
			c.drawRect(x, y0, x+W, y0+H, paint);
			paint.setStyle(Style.STROKE);
			
			{
				paint.setStyle(Style.FILL);
				paint.setColor(Color.BLACK);
				paint.setTextSize(16.0f * density);
				float txt_y = y0 + H/2 - (paint.ascent() + paint.descent())/2;
				paint.setTextAlign(Align.CENTER);
				c.drawText("Play", x+W/2, txt_y, paint);
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
					parent.startPlay();
				}
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
	}
	ButtonPanel button_panel;

	class MeasureTile {
		int W, H, x, y, touchid = -1, touchx, touchy;;
		int measure_idx, staff_idx;
		long last_click_millis;
		MeasureTile(int _w, int _h, int _x, int _y, int _midx, int _sidx) {
			W = _w; H = _h; x = _x; y = _y;
			measure_idx = _midx; staff_idx = _sidx;
		}
		void draw(Canvas c) {
			if(!is_visible()) return;
			
			int y0 = y-y_offset;
			
			// 20140305: Disable this code segment and the 30K memory leak still existed.
			Bitmap bmp = bitmap_helper.getTileBitmap(staff_idx, measure_idx);
			if(bmp != null) {
				paint.setFilterBitmap(true);
				int bw = bmp.getWidth(), bh = bmp.getHeight();
				Rect dst = new Rect(x,y0,x+W,y0+H);
				NinePatchDrawable bk;
				if(staff_idx == 0) {
					bk = TommyConfig.getCurrentStyle().tile_bk_upper;
				} else bk = TommyConfig.getCurrentStyle().tile_bk_lower;
				bk.setBounds(dst);
				bk.setTargetDensity(c);
				bk.draw(c);
				c.drawBitmap(bmp, new Rect(0,0,bw,bh), dst, bmp_paint);
			}
			
			if(isTouched()) {
				paint.setStyle(Style.STROKE);
				paint.setColor(Color.BLUE);
				paint.setStrokeWidth(density);
				c.drawRect(x, y0, x+W, y0+H, paint);  
				c.drawLine(x+3, y0+3, x+W-3, y0+H-3, paint);
				c.drawLine(x+W-3, y0+3, x+3, y0+H-3, paint);
			}
			
			if(measure_idx == curr_sheet_playing_measure_idx) {
				paint.setStyle(Style.FILL);
				paint.setColor(Color.YELLOW);
				paint.setAlpha(64);
				c.drawRect(x, y0, x+W, y0+H, paint);
				paint.setAlpha(255);
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
				if(is_panning_y == false) {
					if(parent.isMidiPlayerPlaying()) {
						parent.stopMidiPlayer();
					} else {
						parent.player.MoveToMeasureBegin(measure_idx);
						parent.startMidiPlayer();
					}
				}
			}
			return true;
		}
		boolean isTouched() { return (touchid!=-1); }
	}
	ArrayList<MeasureTile> measure_tiles;
	
	private void initMidiFile() {
		MidiFile midifile = new MidiFile(midi_data, midi_title);
		
		MidiSheetMusicActivity.sheet0 = new SheetMusic(ctx);
		sheet = MidiSheetMusicActivity.sheet0;
		sheet.is_tommy_linebreak = true; // Otherwise NullPointer Error
		sheet.is_first_measure_out_of_boundingbox = false;
		sheet.getHolder().setFixedSize(20, 20);
		sheet.init(midifile, null);
		sheet.setVisibility(View.GONE);
		sheet.tommyFree();
		CRC32 crc = new CRC32();
		crc.update(midi_data);
		checksum = crc.getValue();
		
		sheet.ComputeMeasureHashesNoRender(LINE_WIDTH);
		measureHashes = sheet.getMeasureHashes();
		measureHeights = sheet.getMeasureHeights();
		measureWidths = sheet.getMeasureWidths();
		num_notes = sheet.getNumNotes();
		
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
		}
		String hs_sz = prefs_highscores.getString(String.format("%x_HS", checksum), "");
		TommyConfig.populateHSTSArraysFromJSONString(highscores, timestamps, right_clicks, wrong_clicks, hs_sz);
		
		Log.v("TommyIntroView", "Highscore string = " + hs_sz);
		for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
			Log.v("TommyIntroView", String.format("HighScore %f = ", TommyConfig.BLANK_RATIOS[i]) + highscores.get(i));
		}
		
		bitmap_helper = new BitmapHelper(this, sheet, 1.0f);
		
	}
	
	
	public boolean isMeasureOutOfSight(int midx, int sidx) {
		int uid = getTileUIDFromMidSid(sidx, midx);
		return !(measure_tiles.get(uid).is_visible());
	}
	public TommyIntroView(Context context, Bundle bundle, byte[] _midi_data, String _midi_name, TommyIntroActivity tia) {
		super(context);
		parent = tia;
		ctx = context.getApplicationContext(); // To prevent memory leak??
		TommyConfig.init(ctx);
		midi_data = _midi_data;
		midi_title = _midi_name;
		density = MidiSheetMusicActivity.density;
		DEBUG = MidiSheetMusicActivity.DEBUG;
		paint = new Paint();
		paint.setAntiAlias(true);
		bmp_paint = new Paint();
		bmp_paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
		bmp_paint.setFilterBitmap(true);
		need_redraw = true;
		is_running = true;
		thread = new Thread(this);
		CRC32 crc = new CRC32();
		crc.update(midi_data);
		checksum = crc.getValue();
		for(int i=0; i<touches.length; i++) {
			touches[i] = new Vec2();
		}
		measure_tiles = new ArrayList<MeasureTile>();
		
		for(int i=0; i<TommyConfig.BLANK_RATIOS.length; i++) {
			highscores.add(new ArrayList<Long>());
			timestamps.add(new ArrayList<Long>());
			right_clicks.add(new ArrayList<Integer>());
			wrong_clicks.add(new ArrayList<Integer>());
		}
		
		prefs_highscores = ctx.getSharedPreferences("highscores", Context.MODE_PRIVATE);
		prefs_lastplayed = ctx.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
		prefs_playcount  = ctx.getSharedPreferences("playcounts", Context.MODE_PRIVATE);
		num_played = prefs_playcount.getInt(String.format("%x", checksum), 0);
		
		initMidiFile();
	}
	
	private void update() {
		synchronized(this) {
	//		need_redraw = false;
		//	long millis = System.currentTimeMillis();
			if(touches_on_bk == 1) {
				for(int i=0; i<invdpads.length; i++) {
					if(invdpads[i].isTouched()) {
						InvisibleDPad dpad = invdpads[i];
						pan_accumulated += Math.abs(dpad.deltay);
						if(pan_accumulated > PANNING_THRESHOLD) {
							is_panning_y = true;
							
							{
								score_history.touchUp(score_history.touchid);
								button_panel.touchUp(button_panel.touchid);
								for(MeasureTile mt : measure_tiles) {
									mt.touchUp(mt.touchid);
								}
							}
							
						}
						setYOffset(y_offset - dpad.deltay);
						need_redraw = true;
						dpad.clearDeltaXY();
					}
				}
			}
			
			int bytes_consumed = bitmap_helper.getBytesConsumed();
			if(bytes_consumed > BITMAP_MEMORY_BUDGET) {
//				bitmap_helper.clearOutOfSightBitmaps();
				bitmap_helper.clearOutOfSightBitmapsLRU(BITMAP_MEMORY_BUDGET*3/4);
			}
			
			yOffsetInertia();
			
			if(sheet != null) {
				last_sheet_playing_measure_idx = curr_sheet_playing_measure_idx;
				curr_sheet_playing_measure_idx = sheet.getCurrentPlayingMeasure();
				if(curr_sheet_playing_measure_idx != last_sheet_playing_measure_idx) {
					need_redraw = true;
				}
			} else {
				last_sheet_playing_measure_idx = curr_sheet_playing_measure_idx = -1;
			}
		}
	}
		
		@Override
		protected void onDraw(Canvas c) {
			synchronized(this) {
				frame_count++;
				c.drawColor(0xFFFFFFCC);
		
				
				score_history.draw(c);
				button_panel.draw(c);
				for(MeasureTile mt : measure_tiles) {
					mt.draw(c);
				}
		
				
				paint.setTextAlign(Align.CENTER);
				paint.setColor(Color.BLUE);
				paint.setTextSize(12 * density);
				paint.setStrokeWidth(density);
				paint.setStyle(Style.FILL);
				String sz = String.format("Frame %d, BMP=%.2fM/%.2fM",
						frame_count,
						bitmap_helper.getBytesConsumed()/1048576.0f,
						BITMAP_MEMORY_BUDGET/1048576.0f);
				c.drawText(sz, width/2, height-14*density, paint);
				
				need_redraw = false;
			}
			
	}
	
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
						last_millis = curr_millis;
						update();
						if(need_redraw) 
							postInvalidate();
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
		BITMAP_MEMORY_BUDGET = (int)(2 * width * height * 4.0f);
	}
	
	private void layout() {
		// Prepare a 2-screenful-sized buffer
		// Measurement of height here may not be accurate b/c the IME panel takes up half of the screen!!!
		updateBitmapMemoryBudget();
		
		bitmap_helper.bitmap_zoom = 1.0f * width / LINE_WIDTH;
		PANNING_THRESHOLD = (int)(12 * density);
		int y = 0;
		int h_sh = (int)(LINE_HEIGHT*24*density-2);
		int h_btn = (int)(LINE_HEIGHT*6*density-2);
		score_history = new ScoreHistoryPanel(width, h_sh, 0, y);
		y += h_sh;
		
		button_panel  = new ButtonPanel(width, h_btn, 0, y);
		y += h_btn;
		
		for(int i=0; i<invdpads.length; i++) {
			invdpads[i] = new InvisibleDPad();
		}
		
		float zoom = width * 1.0f / LINE_WIDTH
				
				
				;
		int x = 0, x0 = 0;
		for(int i=0; i<num_measures; i++) {
			int y1 = 0;
			int W = (int)(zoom*measureWidths.get(i)), H;
			  //取消了这个功能。
	/*		{
				if(i == 0) {
					x = -W;
					x0 -= measureWidths.get(0);
				}
			}
*/
			for(int j=0; j<num_staffs; j++) {
				H = (int)(measureHeights.get(j)*zoom);
				MeasureTile mt = new MeasureTile(
					W, H, x, (y+y1),i,j);
				y1 = y1 + H;
				measure_tiles.add(mt);
			}
			
			
			int next_w = 0;
			x = x + W;

			x0 = x0 + measureWidths.get(i);
			if(i < num_measures - 1) next_w = measureWidths.get(i+1);
			if(x0 + next_w > LINE_WIDTH) {
				y = y + y1;
				x = 0;
				x0 = 0;
			} else {
			}
			
			if(i == num_measures - 1) {
				y = y + y1;
			}
		}
		if(y_offset_max < y - height) {
			y_offset_max = y - height;
		}
	}
	
	private void afterMeasure() {
		if(is_inited) return;
		thread.start(); // 20140305 disabled this thread, 30K memory leak problem persisted.
		layout();
		is_inited = true;
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
		for(MeasureTile t : measure_tiles) {
			if(t.is_visible()) t.touchDown(id, x, y);
		}
	}
	
	private void myTouchUp(int id) {
		touches[id].x = -1; touches[id].y = -1;
		int last_dy = 0;
		for(InvisibleDPad idp : invdpads) {
			if(idp.isTouched()) last_dy = idp.deltay;
			idp.touchUp(id);
		}
		if(touches_on_bk == 0) { // start inertial
			vel_y = -last_dy;
			is_panning_y = false;
			pan_accumulated = 0;
		}
		score_history.touchUp(id);
		button_panel.touchUp(id);

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
	}

	public void pause() {
		is_running = false;
	}
	
	public void resume() {
		is_running = true;
	}
}
