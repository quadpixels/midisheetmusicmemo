package com.midisheetmusicmemo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.CRC32;

import org.json.JSONArray;
import org.json.JSONException;

import com.midisheetmusicmemo.SheetMusic.Vec2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;

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
	SheetMusic sheet;
	BitmapHelper bitmap_helper;
	byte[] midi_data; String midi_title;
	long checksum;
	// !!! I'm using `measure' and `tile' interchangeably, which I shouldn't !
	ArrayList<ArrayList<Integer>> measureHashes;
	ArrayList<Integer> measureWidths;
	ArrayList<Integer> measureHeights;
	ArrayList<Long> highscores = new ArrayList<Long>();
	int num_notes;
	int num_measures, num_staffs, num_tiles_total;
	SharedPreferences prefs_highscores, prefs_lastplayed, prefs_playcount;
	ArrayList<Long> highscores_25 = new ArrayList<Long>(), 
			highscores_50 = new ArrayList<Long>(), 
			highscores_75 = new ArrayList<Long>(), 
			highscores_100 =new ArrayList<Long>();

	ArrayList<Long> timestamps_25 = new ArrayList<Long>(),
			timestamps_50 = new ArrayList<Long>(),
			timestamps_75 = new ArrayList<Long>(),
			timestamps_100= new ArrayList<Long>();
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
		highscores.clear();
		paint = null;
		parent = null;
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
		Bitmap getTileBitmap(int staff_idx, int measure_idx) {
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
					
					if(view.isMeasureOutOfSight(midx, sidx)) {
						to_delete.add(x);
					}
				}
				for(Integer y : to_delete) {
					do_clearBmp(y);
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
		int W, H, x, y, touchid = -1, touchx, touchy;
		long last_click_millis;
		ScoreHistoryPanel(int _w, int _h, int _x, int _y) {
			W = _w; H = _h; x = _x; y = _y;
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
			}
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
			if(isTouched()) {
				paint.setStyle(Style.FILL);
			} else paint.setStyle(Style.STROKE);
			c.drawRect(x, y0, x+W, y0+H, paint); 
			c.drawLine(x, y0, x+W, y0+H, paint);
			c.drawLine(x+W, y0, x, y0+H, paint);
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
//				ArrayList<MeasureStatus> blah = new ArrayList<MeasureStatus>();
				for(int j=0; j<num_measures; j++) {
					num_tiles_total ++;
				}
			}
		}
		highscores = new ArrayList<Long>();
		String hs_sz = prefs_highscores.getString(String.format("%x_HS", checksum), "");
		String[] sep = hs_sz.split(":");
		if(sep.length == 4) {
			TommyConfig.populateHSTSArraysFromJSONString(highscores_25, timestamps_25, sep[0]);
			TommyConfig.populateHSTSArraysFromJSONString(highscores_50, timestamps_50, sep[1]);
			TommyConfig.populateHSTSArraysFromJSONString(highscores_75, timestamps_75, sep[2]);
			TommyConfig.populateHSTSArraysFromJSONString(highscores_100, timestamps_100, sep[3]);
		} else {
			
		}

		Log.v("TommyIntroView", "Highscore string = " + hs_sz);
		Log.v("TommyIntroView", "Highscore 25 = " + highscores_25.toString());
		Log.v("TommyIntroView", "Highscore 50 = " + highscores_50.toString());
		Log.v("TommyIntroView", "Highscore 75 = " + highscores_75.toString());
		Log.v("TommyIntroView", "Highscore 100= " + highscores_100.toString());
		
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
					bitmap_helper.clearOutOfSightBitmaps();
				}
				
				yOffsetInertia();
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
				String sz = String.format("Frame %d, BMP=%.2fM/%.2fM",
						frame_count,
						bitmap_helper.getBytesConsumed()/1048576.0f,
						BITMAP_MEMORY_BUDGET/1048576.0f);
				c.drawText(sz, width/2, 14*density, paint);
				
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
	
	private void layout() {
		// Prepare a 2-screenful-sized buffer
		BITMAP_MEMORY_BUDGET = (int)(2 * width * height * 2.0f);
		
		bitmap_helper.bitmap_zoom = 1.0f * width / LINE_WIDTH;
		PANNING_THRESHOLD = (int)(12 * density);
		int y = 0;
		int h_sh = (int)(LINE_HEIGHT*6*density-2);
		score_history = new ScoreHistoryPanel(width, h_sh, 0, y);
		y += h_sh;
		button_panel  = new ButtonPanel(width, h_sh, 0, y);
		y += h_sh;
		
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
	   afterMeasure();
	}

	public void pause() {
		is_running = false;
	}
	
	public void resume() {
		is_running = true;
	}
}
