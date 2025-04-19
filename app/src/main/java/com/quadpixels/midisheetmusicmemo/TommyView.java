// TO DO LIST:
// 1. Turning on/off rendering of clefs & accidentals on
//    non-first measure on a line
// 2. Specifying Total Line Width
// 3. Aligning groups
//  

// 为什么移动一个Piece的时候，是屏幕上的相对位置没有变，但是在移动背景的时候，屏幕上的相对位置就是有位移的了。
// 莫非……CamX因为某些编程错误表示的不是世界坐标系中的坐标。

//
// Fundamental change:
//  Graph-based model.... ?!
//
package com.quadpixels.midisheetmusicmemo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.midisheetmusicmemo.SheetMusic;
import com.midisheetmusicmemo.SheetMusic.Vec2;
import com.quadpixels.midisheetmusicmemo.R;
import com.quadpixels.midisheetmusicmemo.R.drawable;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.drawable.NinePatchDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

public class TommyView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
	SheetMusic sheet;
	byte[] midi_data; String midi_title;
	private int silhousette_width = 720, measures_max_height = 0;
	private boolean is_layout_randomize = false;
	private SurfaceHolder holder;
	private Thread thread;
	private Canvas canvas;
	private Paint paint;
	int frame_count = 0, screenW = 0, screenH=0;
	boolean is_running = true;
	private Context ctx;
	final private static int FRAME_DELAY = 25;
	final private static int TRIPLE_CLICK_DELAY = 200;
	ArrayList<ArrayList<Bitmap>> measures;
	ArrayList<MyPiece> pieces = new ArrayList<MyPiece>();
	ArrayList<LinkedList<MyPiece>> groups = new ArrayList<LinkedList<MyPiece>>(); // Groups. Their head shall be ...
	LinkedList<LinkedList<MyPiece>> groups_zordered = new LinkedList<LinkedList<MyPiece>>();
	boolean[] is_group_touched = null;
	boolean[] is_group_has_snap = null;
	boolean[] is_piece_touched = null;
	int[] zidx_groups, zidx_measures1; // For the purpose of swapping
	NinePatchDrawable shadow1;
	int touches_on_bk = 0; // How many touches are currently on the background
	
	// See this: http://stackoverflow.com/questions/4604003/synchronized-block-lock-more-than-one-object
	Object shared_lock = new Object();
	
	// pinch
	Vec2 pinch_center_prev = new Vec2();
	Vec2 pinch_center      = new Vec2();
	float pinch_orig_zoom = 1.0f, pinch_orig_dist = 1.0f;
	
	// Snapping
	final static int SNAP_RADIUS = 20; // Scales with density.
	LinkedList<SnapInfo> snapinfos = new LinkedList<SnapInfo>();
	class SnapInfo {
		MyPiece p1, p2;
		SnapNormal normal;
		Vec2 new_p1_p = new Vec2(), new_p2_p = new Vec2();
	}
	
	private void appendSnapInfo(MyPiece _p1, MyPiece _p2, SnapNormal _normal, Vec2 _new_p1_p, Vec2 _new_p2_p) {
		int idx_first_free = -999;
		SnapInfo to_add = null;
		for(SnapInfo s : snapinfos) {
			if((s.p1 == _p1 && s.p2 == _p2) || (s.p1 == _p2 && s.p2 == _p1)) {
				return;
			}
			if(s.p1 == null && s.p2 == null && idx_first_free == -999) {
				to_add = s;
			}
		}
		
		if(to_add != null) {
			to_add.p1 = _p1; to_add.p2 = _p2;
			to_add.normal = _normal;
			to_add.new_p1_p.assign(_new_p1_p);
			to_add.new_p2_p.assign(_new_p2_p);
		}
	}
	
	// 如果一邊在進行pinch，一邊按下了Zoom in或Zoom out
	// 那麼應讓pinch更優先
	private void calculatePinchCenter(boolean is_init) {
		int idx0 = -99999, idx1 = -99999;
		for(int i=0; i<invdpads.length; i++) {
			if(invdpads[i].isTouched()) {
				idx0 = i; break;
			}
		}
		for(int i=idx0+1; i<invdpads.length; i++) {
			if(invdpads[i].isTouched()) {
				idx1 = i; break;
			}
		}
		InvisibleDPad d0 = invdpads[idx0], d1 = invdpads[idx1];
		pinch_center.x = (d0.touchx + d1.touchx)/2;
		pinch_center.y = (d0.touchy + d1.touchy)/2;
		if(is_init) {
			{
				int dx = d0.touchx - d1.touchx, dy = d0.touchy - d1.touchy;
				pinch_orig_dist = (float)(Math.sqrt(dx*dx + dy*dy));
			}
			pinch_orig_zoom = zoom;
		} else {
			int dx = d0.touchx - d1.touchx, dy = d0.touchy - d1.touchy;
			float dist = (float)(Math.sqrt(dx*dx + dy*dy));
			zoom = pinch_orig_zoom * (dist / pinch_orig_dist);
			
			dx = pinch_center.x - pinch_center_prev.x;
			dy = pinch_center.y - pinch_center_prev.y;
			camX -= dx;
			camY -= dy;
		}
		pinch_center_prev.x = pinch_center.x;
		pinch_center_prev.y = pinch_center.y;
	}
	
	// Invisible is used for panning and zooming
	class InvisibleDPad {
		int touchx, touchy; // Screen coords
		int deltax, deltay;
		int touchid = -1;
		public void clearDeltaXY() { deltax = deltay = 0; }
		boolean touchDown(int id, int x, int y) {
			if(touchid == -1) {
				touchx = x; touchy = y;
				touchid = id;
				touches_on_bk ++;
				if(touches_on_bk == 2) {
					calculatePinchCenter(true);
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
	}
	InvisibleDPad[] invdpads = new InvisibleDPad[10];
	
	boolean pointInCircle(int cx0, int cy0, int r0, int x0, int y0) {
		float deltax = x0 - cx0, deltay = y0 - cy0;
		if(deltax*deltax + deltay*deltay > r0*r0) return false;
		return true;
	}
	
	protected class DPad {
		// Anchor point: Center
		int x = 0; // In units of 1 DP (1 px under 160 DPI)
		int y = 0; // In units of 1 DP (1 px under 160 DPI)
		int r = 40;
		int dx, dy, dr;
		
		int touchx = 0, touchy = 0;
		int touchid = -1;
		
		void refreshDensity(float d) {
			dx = (int) (x * density); dy = (int) (y * density); dr = (int) (r * density);
		}
		void draw(Canvas c) {
			paint.setAlpha(128);
			paint.setColor(0xFF808080);
			paint.setStyle(Style.FILL_AND_STROKE);
			c.drawCircle(dx, dy, dr, paint);
			
			paint.setAlpha(255);
			if(touchid != -1) {
				int tx = (int)((x+touchx) * density);
				int ty = (int)((y+touchy) * density);
				paint.setColor(0xFFFFFFFF);
				c.drawCircle(tx, ty, dr*0.25f, paint);
			}
		}
		boolean touchDown(int id, int screenx, int screeny) {
			if(touchid == -1) {
				;
			} else {
				if(touchid != id) return false;
			}
			
			{
				int x0 = (int)(screenx / density), y0 = (int)(screeny / density);
				boolean is_intersect = pointInCircle(x, y, r, x0, y0);
				if(!is_intersect) {
					touchid = -1;
					return false;
				} else {
					touchid = id;
					touchx = x0 - x; touchy = y0 - y;
					return true;
				}
			}
		}
		boolean touchMove(int id, int screenx, int screeny) {
			if(touchid != id) return false;
			{
				float x0 = screenx / density - x;
				float y0 = screeny / density - y;
				float dist_sq = x0*x0 + y0*y0;
				if(dist_sq > r*r) {
					float ratio = (float) (r / Math.sqrt(dist_sq));
					x0 = x0 * ratio;
					y0 = y0 * ratio;
				}
				touchx = (int)x0;
				touchy = (int)y0;
			}
			return true;
		}
		boolean touchUp(int id) {
			if(touchid == -1) return false;
			if(touchid == id) { 
				touchid = -1;
				touchx = -1; touchy = -1;
				return true;
			}
			return false;
		}
		boolean getDeltaXY(Vec2 out) {
			if(touchid == -1) { out.x = 0; out.y = 0; return false; }
			else {
				out.x = (int)(1.0f * touchx / r * 10);
				out.y = (int)(1.0f * touchy / r * 10);
				return true;
			}
		}
	};
	DPad dpad;
	
	protected class MyButton {
		int x = 0;
		int y = 0;
		int r = 20;
		int dx, dy, dr;
		int touchid = -1;
		int tag = 0;
		MyButton(int _tag) {
			tag = _tag;
		}
		void refreshDensity(float d) {
			dx = (int)(x * d); dy = (int)(y * d); dr = (int)(r * d);
		}
		void draw(Canvas c) {
			if(isPressed()) {
				paint.setColor(0xFFFFFFFF);
			} else {
				paint.setColor(0xFF808080);
			}
			paint.setStyle(Style.FILL_AND_STROKE);
			c.drawCircle(dx, dy, dr, paint);
		}
		public boolean isPressed() {
			return (touchid != -1);
		}
		boolean touchDown(int id, int screenx, int screeny) {
			if(touchid == -1) {;} else if(touchid != id) { return false; }
			int x0 = (int)(screenx / density), y0 = (int)(screeny / density);
			if(!pointInCircle(x, y, r, x0, y0)) {
				touchid = -1;
				return false;
			} else {
				touchid = id;
				return true;
			}
		}
		boolean touchMove(int id, int screenx, int screeny) {
			return false;
		}
		boolean touchUp(int id) {
			if(id == touchid) { touchid = -1; return true; }
			return false;
		}
	};
	MyButton btn_zoomin, btn_zoomout;
	
	// 接觸面法向是沿着X還是Y
	public enum SnapNormal {
		X, Y
	};
	public class MyPiece {
		Vec2 p;
		Vec2 ltw; // Last Touch World
		Bitmap bmp;
		int measure_hash;
		int measure_idx;
		int staff_idx;
		
		boolean is_cemented = false;
		
		boolean is_touched = false;
		int touchid;
		
		// 關於 Snapping position 的情況記錄 
		boolean has_snap_cand = false;
		Vec2 snapcand_pos;
		MyPiece snapcand;
		SnapNormal snapnormal;
		int group_id;
		
		// Triple Click to ungroup
		long last_touch_millis = 0;
		int click_counter = 0;
		
		// For usage per frame
		int top, left, right, bottom;	
		
		MyPiece() {
			p = new Vec2(0,0); ltw = new Vec2(0, 0); touchid = -1;
			snapcand_pos = new Vec2(0, 0);
			has_snap_cand = false;
		}
		int getWidthOnScreen() {
			return (int)(zoom * bmp.getWidth());
		}
		int getHeightOnScreen() {
			return (int)(zoom * bmp.getHeight());
		}
		
		void prepareToDraw() {
			Vec2 xy = worldToScreen(p);
			top = xy.y; left = xy.x; right = left + getWidthOnScreen(); bottom = top + getHeightOnScreen();
		}
		
		void drawShadow(Canvas canvas) {
			Rect b = new Rect(
				(int) (left-19*density),
				(int) (top-16*density), 
				(int) (right+23*density), 
				(int) (bottom+24*density)
			);
			shadow1.setBounds(b);
			shadow1.setTargetDensity((int)(density * 160));
			shadow1.setDither(true);
			shadow1.draw(canvas);
			b = null;
		}
		
		void draw(Canvas canvas) {
			if(bottom < 0 || top > screenH) return;
			if(left  > screenW || right < 0) return;
			Rect src  = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
			Rect dest = new Rect(left, top, right, bottom);
			
			
			canvas.drawBitmap(bmp, src, dest, paint);
			if(false) {
				paint.setStrokeWidth(1.0f);
				paint.setStyle(Style.STROKE);
				paint.setAntiAlias(true);
				canvas.drawText(String.format("Group%d Meas%d Staf%d", group_id, measure_idx, staff_idx), left, top, paint);
				paint.setAntiAlias(false);
			}
			
			if(is_cemented) {
				paint.setColor(0xFF80FF80);
				paint.setAlpha(128);
				paint.setStyle(Style.FILL);
				canvas.drawRect(dest, paint);
				paint.setAlpha(255);
			}
		}
		
		void drawSnapRect(Canvas canvas) {
			paint.setStyle(Style.STROKE);
			if(has_snap_cand) {
				paint.setStrokeWidth(2.0f*density);
				paint.setColor(0xFFC0C0F0);
				Vec2 xy1 = worldToScreen(snapcand_pos);
				canvas.drawRect(xy1.x, xy1.y, xy1.x+right-left, xy1.y+bottom-top, paint);
				canvas.drawRect(left,  top,   right,            bottom,           paint);
				paint.setStrokeWidth(1.0f*density);
			}
			if(is_touched) {
				paint.setStrokeWidth(2.0f*density);
				canvas.drawRect(left,  top,   right,            bottom,           paint);
				paint.setStrokeWidth(1.0f*density);
			}
		}
		void refreshTripleClickState(long millis) {
			if(millis - last_touch_millis > TRIPLE_CLICK_DELAY) {
				click_counter = 0;
			}
		}
		boolean touchDown(int id, int screenx, int screeny) {
			if(is_cemented) return false;
			Vec2 xy = new Vec2(screenx, screeny);
			xy = screenToWorld(xy);
			
			// Intersect AABB
			boolean is_intersect = true;
			{
				if(xy.x < p.x) { is_intersect = false; }
				if(xy.x > p.x + bmp.getWidth()) { is_intersect = false; }
				if(xy.y < p.y) { is_intersect = false; }
				if(xy.y > p.y + bmp.getHeight()) { is_intersect = false; } 
			}
			if(is_intersect) {
				boolean ignored = false;
				if((touchid != -1) && (id != touchid)) ignored = true;
				if(is_group_touched[group_id] == true) ignored = true;
				if(!ignored) {
					is_touched = true;
					touchid = id;
					is_group_touched[group_id] = true;
					ltw.x = screenx; ltw.y = screeny;
					ltw = screenToWorld(ltw);
				}
				
				{
					long curr_millis = System.currentTimeMillis();
					if(click_counter == 0) {
						click_counter = 1;
					} else {
						if(curr_millis - last_touch_millis < TRIPLE_CLICK_DELAY) {
							click_counter++;
						}
						
						if(click_counter == 3) {
							click_counter = 0;
							detachFromGroup();
						}
					}
					last_touch_millis = curr_millis;
				}
				
				return true;
			} else {
				return false;
			}
		}
		
		void detachFromGroup() {
			synchronized(shared_lock) {
				// Find first empty one
				int new_gid = 0;
				for(; new_gid < groups.size(); new_gid++) {
					if(groups.get(new_gid).size() == 0) {
						break;
					}
				}
				if(new_gid == groups.size()) return;
				Log.v("detachFromGroup", String.format("Group had %d elts", groups.get(group_id).size()));
				groups.get(group_id).remove(this);
				groups.get(new_gid).add(this);
				
				is_group_touched[group_id] = false;
				group_id = new_gid;
				is_group_touched[new_gid]  = false;
			}
		}
		
		boolean touchMove(int id, int screenx, int screeny) {
			if(is_cemented) return false;
			if(touchid != id || (!is_touched)) return false;
			{
				Vec2 xy = new Vec2(screenx, screeny);
				xy = screenToWorld(xy);
				int dx = xy.x - ltw.x;
				int dy = xy.y - ltw.y;
				LinkedList<MyPiece> lmp = groups.get(group_id);
				for(MyPiece p : lmp) {
					p.p.x += dx;
					p.p.y += dy;
				}
				if((frame_count%7) == 0) {
					for(MyPiece p : lmp) {
						findSnapCand(p);
					}
				}
				ltw.x = xy.x;
				ltw.y = xy.y;
			}
			return true;
		}
		
		boolean touchUp(int id) {
			if(id != touchid) return false;
			is_touched = false;
			touchid = -1;
			is_group_touched[group_id] = false;
			synchronized(shared_lock) {
				if(is_group_has_snap[group_id]) {
					
					// 1. Check if all delta x and delta y's are the same
					LinkedList<MyPiece> lmp = groups.get(group_id);
					boolean is_all_dxdy_same = true;
					
					int dx = -999, dy = -999;
					for(MyPiece p : lmp) {
						if(p.has_snap_cand) {
							int this_dx = p.snapcand_pos.x - p.p.x, this_dy = p.snapcand_pos.y - p.p.y;
							if(dx == -999) {
								dx = this_dx; dy = this_dy;
							} else {
								if(dx != this_dx) {
									is_all_dxdy_same = false;
									break;
								}
							}
						}
					}
					
					if(is_all_dxdy_same) {
						HashSet<Integer> rhs_groupids = new HashSet<Integer>();;
						for(MyPiece p : lmp) {
							if(p.has_snap_cand) {
								if(isAdjacentPieces(p, p.snapcand, p.snapnormal)) {
									rhs_groupids.add(p.snapcand.group_id);
								} else {
									Log.v("TommyView", "isAdjacentPieces returns false "+
												p.measure_idx+","+
												p.snapcand.measure_idx+","+
												p.snapnormal.toString()+"");
								}
							}
							p.has_snap_cand = false;
						}
						
						if(rhs_groupids.size() > 0) {
							for(MyPiece p : lmp) {
								p.p.x += dx; p.p.y += dy;
							}
							Toast.makeText(ctx, "Glued!", Toast.LENGTH_SHORT).show();
						}
						
						for(Integer i : rhs_groupids) {
							glueTwoGroups(group_id, i);
						}
					} else {
						for(MyPiece p : lmp) {
							p.has_snap_cand = false;
						}
					}
				}
			}
			is_group_has_snap[group_id] = false;
			return true;
		}
		
		void cement() { // Becomes immovable
			is_cemented = true;
		}
	};
	
	// Group grpid2 vanished!
	private void glueTwoGroups(int grpid1, int grpid2) {
		synchronized(shared_lock) {
			if(grpid1 == grpid2) return;
			LinkedList<MyPiece> lst1 = groups.get(grpid1);
			LinkedList<MyPiece> lst2 = groups.get(grpid2);
			Iterator<MyPiece> itr2 = lst2.iterator();
			for(; itr2.hasNext(); ) {
				MyPiece p2 = itr2.next();
				lst1.add(p2);
				p2.group_id = grpid1;
				itr2.remove();
			}
		}
	}
	
	private void findSnapCand(MyPiece me) {
		int me_left = me.p.x, me_right = me.p.x + me.bmp.getWidth(),
				me_top = me.p.y, me_bottom = me_top + me.bmp.getHeight();
		float SNAP_RADIUS_VIS = SNAP_RADIUS / zoom;
		
		boolean is_found = false; // At least 1 match is found?
		for(MyPiece x : pieces) {
			if(x.equals(me)) continue;
			if(x.group_id == me.group_id) continue;
			int x_left = x.p.x, x_right = x_left + x.bmp.getWidth(),
					x_top = x.p.y, x_bottom = x_top + x.bmp.getHeight();
			// 左對右
			if(Math.abs(x_left - me_right) < SNAP_RADIUS_VIS) {
				if(Math.abs(x_top - me_top) < SNAP_RADIUS_VIS) {
					me.snapcand_pos.x = x_left - me.bmp.getWidth();
					me.snapcand_pos.y = x_top;
					me.has_snap_cand = true;
					me.snapcand = x;
					me.snapnormal = SnapNormal.X;
					is_found = true;
					is_group_has_snap[me.group_id] = true;
					if(isAdjacentPieces(me, x, SnapNormal.X)) return;
				}
			}
			// 右對左
			if(Math.abs(x_right - me_left) < SNAP_RADIUS_VIS) {
				if(Math.abs(x_top - me_top) < SNAP_RADIUS_VIS) {
					me.snapcand_pos.x = x_right;
					me.snapcand_pos.y = x_top;
					me.has_snap_cand = true;
					me.snapcand = x;
					me.snapnormal = SnapNormal.X;
					is_found = true;
					is_group_has_snap[me.group_id] = true;
					if(isAdjacentPieces(me, x, SnapNormal.X)) return;
				}
			}
			// 上對下
			if(Math.abs(me_bottom - x_top) < SNAP_RADIUS_VIS) {
				if(Math.abs(me_left - x_left) < SNAP_RADIUS_VIS) {
					me.snapcand_pos.x = x_left;
					me.snapcand_pos.y = x_top - me.bmp.getHeight();
					me.has_snap_cand = true;
					me.snapcand = x;
					me.snapnormal = SnapNormal.Y;
					is_found = true;
					is_group_has_snap[me.group_id] = true;
					if(isAdjacentPieces(me, x, SnapNormal.Y)) return;
				}
			}
			
			// 下對上
			if(Math.abs(me_top - x_bottom) < SNAP_RADIUS_VIS) {
				if(Math.abs(me_left - x_left) < SNAP_RADIUS_VIS) {
					me.snapcand_pos.x = x_left;
					me.snapcand_pos.y = x_bottom;
					me.has_snap_cand = true;
					me.snapcand = x;
					me.snapnormal = SnapNormal.Y;
					is_found = true;
					is_group_has_snap[me.group_id] = true;
					if(isAdjacentPieces(me, x, SnapNormal.Y)) return;
				}
			}
		}
		if(!is_found) 
			me.has_snap_cand = false;
		else {
		}
	}
	

	int getHashMeasure(int midx) {
		int ret = 0;
		ArrayList<ArrayList<Integer>> hashes = sheet.getMeasureHashes();
		for(int i=0; i<hashes.size(); i++) {
			ret = ret ^ hashes.get(i).get(midx);
			ret = Util.smear(ret);
		}
		return ret;
	}
	
	boolean isAdjacentPieces(MyPiece p1, MyPiece p2, SnapNormal normal) {
		int sidx1 = p1.staff_idx, sidx2 = p2.staff_idx;
		int idx1 = p1.measure_idx, idx2 = p2.measure_idx;
		if(normal == SnapNormal.X) { // Temporal Match 
			if(sidx1 != sidx2) return false;
			if(idx1 > 0) {
				if(p2.measure_hash == sheet.getMeasureHashes().get(sidx1).get(idx1-1)) return true;
			}
			if((idx1+1) < measures.get(0).size()) {
				if(p2.measure_hash == sheet.getMeasureHashes().get(sidx2).get(idx1+1)) return true;
			}
		} else if(normal == SnapNormal.Y) {
			
			//
			// 問題：當低音譜内容置於上方、高音譜内容置於下方时依然可配對。應當改正。
			if(sidx1 == sidx2) return false;
			int hash1 = sheet.getMeasureHashes().get(sidx1).get(idx2),
					hash2 = sheet.getMeasureHashes().get(sidx2).get(idx1);
			if(hash2 == p2.measure_hash) {
				return true;
			}
			if(hash1 == p1.measure_hash) {
				return true;
			}
			Log.v("Not Matched....", String.format("Wanted: %X %X %X %X", 
					hash2, p2.measure_hash,
					hash1, p1.measure_hash));
		}
		return false;
	}
	
	// Camera 相關
	// Position of camera focus in world coordinates
	float camX = 100.0f, camY = 100.0f;
	float zoom = 0.5f;
	float density = 1.0f;
	
	// Touch
	Vec2[] touches = new Vec2[10];
	final private static int[] kolors = {Color.RED, Color.DKGRAY, 0xFF008000, 0xFF000080};
	
	private Vec2 worldToScreen(Vec2 w) {
		float dx = w.x - camX, dy = w.y - camY;
		dx = dx * zoom; dy = dy * zoom;
		float x = dx + screenW/2, y = dy + screenH/2;
		return new Vec2((int)x, (int)y);
	}
	
	private Vec2 screenToWorld(Vec2 s) {
		float dx = s.x - screenW/2, dy = s.y - screenH/2;
		float x  = dx/zoom + camX,   y = dy/zoom + camY;
		return new Vec2((int)x, (int)y);
	}
	
	private void makeshiftLayout() {
		int W = silhousette_width;
		int x = 0, x_next = 0;
		int tot_size = measures.size() * measures.get(0).size();
		zidx_groups = new int[tot_size];
		zidx_measures1 = new int[tot_size];
		is_piece_touched = new boolean[tot_size];
		is_group_has_snap = new boolean[tot_size];
		is_group_touched = new boolean[tot_size];
		
		int ybase = 0;
		int globalidx = 0;
		int ymax = 0;
		for(int i=0; i<measures.get(0).size(); i++) {
			Bitmap b1 = measures.get(0).get(i);

			// First measure: Indent left
			if(i == 0) {
				x = -b1.getWidth();
			}
			x_next = x+b1.getWidth();
			{
				if(x_next > W) {
					x = 0;
					x_next = b1.getWidth();
					for(int sidx = 0; sidx < measures.size(); sidx++) {
						ybase = ybase + measures.get(sidx).get(i).getHeight();
					}
				}
			}
			
			int y1 = ybase;
			for(int sidx = 0; sidx < measures.size(); sidx++) {
				Bitmap b = measures.get(sidx).get(i);
				MyPiece piece = new MyPiece();
				
				piece.p.x = x;
				piece.p.y = y1;
				piece.bmp = b;
				piece.measure_hash = sheet.getMeasureHashes().get(sidx).get(i);
				piece.measure_idx  = i;
				piece.staff_idx = sidx;
				piece.group_id = globalidx;
				if(i==0) piece.cement();
				
				pieces.add(piece); // 這個現在當作Pointer Array用。僅用於處理Touch時的Z order。
				LinkedList<MyPiece> newlist = new LinkedList<MyPiece>();
				newlist.add(piece);
				groups.add(newlist);
				groups_zordered.add(newlist);
				zidx_groups[globalidx] = globalidx;
				zidx_measures1[globalidx] = -999;
				
				y1 = y1 + b.getHeight();
				
				globalidx++;
			}
			x = x_next;
		}
		
		measures_max_height = ymax = ybase;
		// Randomize!
		
		if(is_layout_randomize == true) {
			
			Random rnd = new Random();
			
			Vec2 tmp = new Vec2(0, 0);
			for(int i=0; i<pieces.size(); i++) {
				for(int j=i; j<i+3 && j<pieces.size(); j++) {
					if(rnd.nextFloat() > 0.5) {
						tmp.assign(pieces.get(i).p);
						pieces.get(i).p.assign(pieces.get(j).p);
						pieces.get(j).p.assign(tmp);
					}
				}
			}
		}
	}
	
	private void initui() {
		dpad = new DPad();
		btn_zoomout = new MyButton(1);
		btn_zoomin  = new MyButton(2);
		for(int i=0; i<invdpads.length; i++) {
			invdpads[i] = new InvisibleDPad();
		}
	}
	
	private void init() {
		AssetManager mgr = ctx.getAssets();
		InputStream istream;
		
		MidiFile midifile = new MidiFile(midi_data, midi_title);
		sheet = new SheetMusic(ctx);
		sheet.getHolder().setFixedSize(200, 200);
		sheet.init(midifile, null);
		sheet.setVisibility(View.GONE);
		
		measures = sheet.RenderAllMeasures(silhousette_width - 1); // -1: To account for rounding errors
		pieces.clear();
		zidx_groups = null;
		zidx_measures1 = null;
		makeshiftLayout();
	}
	
	public void free() {
		for(MyPiece p : pieces) {
			p.bmp.recycle();
		}
		is_group_touched = null;
		is_piece_touched = null;
		sheet = null;
		holder.removeCallback(this);
		canvas = null;
		System.gc();
	}
	
	public TommyView(Context context, byte[] _data, String _title) {
		super(context);
		midi_data = _data;
		midi_title = _title;
		density = MidiSheetMusicActivity.density;
		ctx = context;
		init();
		initui();
		thread = new Thread(this);
		holder = this.getHolder();
		paint = new Paint();
		shadow1 = (NinePatchDrawable)(ctx.getResources().getDrawable(R.drawable.shadow1));
		for(int i=0; i<touches.length; i++) { 
			touches[i] = new Vec2(-1, -1);
		}
		holder.addCallback(this); // 估計是因為這個Callback註册以後才調用surfaceCreated
	}

	@Override
	public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3) {
		screenW = this.getWidth();
		screenH = this.getHeight();
		
		dpad.x = dpad.r + 5;
		dpad.y = (int) (screenH / density - dpad.r - 5);
		dpad.refreshDensity(density);
		

		btn_zoomin.x  = (int)(screenW / density - 20);
		btn_zoomin.y  = dpad.y;
		btn_zoomin.refreshDensity(density);
		
		btn_zoomout.x = (int)(screenW / density - 80);
		btn_zoomout.y = dpad.y;
		btn_zoomout.refreshDensity(density);
	}

	@Override
	public void surfaceCreated(SurfaceHolder arg0) {
		thread.start();
		is_running = true;
	}
	
	
	@Override
	public void surfaceDestroyed(SurfaceHolder arg0) {
		is_running = false;
	}

	private void draw() {
		frame_count ++;
		if(canvas == null)
			canvas = holder.lockCanvas();
		if(canvas != null) {
			canvas.drawColor(Color.WHITE);
			int H = canvas.getHeight(), W = canvas.getWidth();
			
			/*
			{
				paint.setFilterBitmap(false);
				paint.setDither(true);
				// Draw pieces!
				for(int i=0; i<zidx_measures.length; i++) {
					MyPiece piece = pieces.get(zidx_measures[i]);
					piece.drawShadow(canvas);
					piece.draw(canvas);
				}
			}
			*/
			
			// Draw groups.
			// TODO: 现在无视Z order，稍后加上。
			synchronized(shared_lock)
			{
				for(LinkedList<MyPiece> grp : groups_zordered) {
					for(MyPiece mp : grp) {
						mp.prepareToDraw();
					}
					for(MyPiece mp : grp) { mp.drawShadow(canvas); }
					paint.setFilterBitmap(true);
					for(MyPiece mp : grp) { mp.draw(canvas); }
					paint.setFilterBitmap(false);
					for(MyPiece mp : grp) { mp.drawSnapRect(canvas); }
				}
			}
			
			
			{
				// Draw Control Panel and buttons
				dpad.draw(canvas);
				btn_zoomin.draw(canvas);
				btn_zoomout.draw(canvas);
			}
			
			
			for(int i=0; i<touches.length; i++) {
				Vec2 xy = touches[i];
				if(xy.x > -1 && xy.y > -1) {
					paint.setColor(kolors[i%kolors.length]);
					canvas.drawLine(0, xy.y, W, xy.y, paint);
					canvas.drawLine(xy.x, 0, xy.x, H, paint);
				}
			}
			
			// Debug Info
			{
				paint.setColor(Color.BLUE);
				StringBuilder sb = new StringBuilder();
				
				sb.append(" Zoom=");
				sb.append(zoom);
				String s = sb.toString();
				paint.setTextAlign(Align.LEFT);
				canvas.drawText(s, 4, 15, paint);
				

				Vec2 w_topleft = new Vec2(0, 0);
				Vec2 w_bottomright = new Vec2(silhousette_width, measures_max_height);
				w_topleft = worldToScreen(w_topleft);
				w_bottomright = worldToScreen(w_bottomright);
				paint.setStyle(Style.STROKE);
				canvas.drawRect(w_topleft.x, w_topleft.y, w_bottomright.x, w_bottomright.y, paint);
			}
			
			holder.unlockCanvasAndPost(canvas);
			canvas = null;
		}
	}

	Vec2 dxy = new Vec2(0, 0);
	private void update() {
		// Panning by button
		{
			if(dpad.getDeltaXY(dxy) == true) {
				camX = camX + dxy.x * density / zoom;
				camY = camY + dxy.y * density / zoom;
			}
		}
		
		// Zooming by button
		{
			if(btn_zoomout.isPressed()) zoom = zoom * 0.99f;
			else if(btn_zoomin.isPressed()) zoom = zoom * 1.01f;
		}
		
		{
			if(touches_on_bk == 1) {
				int last_idx = -9999;
				for(int i=0; i<invdpads.length; i++) {
					if(invdpads[i].isTouched()) { 
						last_idx = i;
						break;
					}
				}
				InvisibleDPad d = invdpads[last_idx];
				int dx = d.deltax, dy = d.deltay;
				camX = camX - dx * 1.0f / zoom;
				camY = camY - dy * 1.0f / zoom;
				d.clearDeltaXY(); // UI update = 20 fps, Touch event = 60fps, so we lost 1/3 frames. We have to aggregate to fix this.
				// Must clear b/c if the pointer remains stationary...
				d.deltax = 0; d.deltay = 0;
			} else if(touches_on_bk == 2) {
				calculatePinchCenter(false);
			}
		}
		
		{
			long millis = System.currentTimeMillis();
			for(MyPiece mp : pieces) {
				mp.refreshTripleClickState(millis);
			}
		}
	}
	
	@Override
	public void run() {
		while(true) {
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
						draw();
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
	
	private void myTouchDown(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;

		// Step 1: Process U.I. elements (the D pad and the buttons)
		if(dpad.touchDown(id, x, y)) return;
		if(btn_zoomout.touchDown(id, x, y)) return;
		if(btn_zoomin.touchDown(id, x, y)) return;
		
		// Step 2: Process music puzzle pieces
		boolean is_touched_piece = false;
		{
			// 1. Preprocessing
			for(int i=0; i<is_piece_touched.length; i++) {
				is_piece_touched[i] = false;
			}
			for(int i=0; i<zidx_groups.length; i++) zidx_groups[i] = -999;
			int idx = 0;
			for(LinkedList<MyPiece> lmp : groups_zordered) {
				if(lmp.size() > 0) {
					zidx_groups[idx] = lmp.get(0).group_id;
					idx++;
				}
			}
			
			// 2. Process touch evt
			for(int i=zidx_groups.length-1; i>=0; i--) {
				idx = zidx_groups[i];
				if(idx == -999) continue;
				for(MyPiece p : groups.get(idx)) {
					if(p.touchDown(id, x, y)) {
						is_piece_touched[i] = true;
						is_group_touched[p.group_id] = true;
						
						synchronized(shared_lock) {
							LinkedList<MyPiece> ll = groups.get(p.group_id);
							groups_zordered.remove(ll);
							groups_zordered.add(ll);
						}
						
						is_touched_piece = true;
						break;
					} else {
						is_piece_touched[i] = false;
					}
				}
				if(is_touched_piece) return;
			}
		}
		if(is_touched_piece) return;
		
		// Step 3: Panning and zooming
		{
			for(int i=0; i<invdpads.length; i++) {
				if(invdpads[i].touchDown(id, x, y)) return;
			}
		}
	}
	
	private void myTouchUp(int id) {
		touches[id].x = -1; touches[id].y = -1;
		dpad.touchUp(id);
		btn_zoomout.touchUp(id);
		btn_zoomin.touchUp(id);
		for(MyPiece p : pieces) {
			p.touchUp(id);
		}
		for(InvisibleDPad idp : invdpads) {
			idp.touchUp(id);
		}
	}
	
	private void myTouchMove(int id, int x, int y) {
		touches[id].x = x; touches[id].y = y;
		dpad.touchMove(id, x, y);
		btn_zoomout.touchMove(id, x, y);
		btn_zoomin.touchMove(id, x, y);
		for(MyPiece p : pieces) {
			p.touchMove(id, x, y);
		}
		for(InvisibleDPad idp : invdpads) {
			idp.touchMove(id, x, y);
		}
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
		
		/*
		int action = evt.getActionMasked();
		int idx0 = -9999;
		switch(action) {
			case MotionEvent.ACTION_DOWN:
				idx0 = 0;
				myTouchDown(evt.getPointerId(0), (int)evt.getX(), (int)evt.getY());
				break;
			case MotionEvent.ACTION_UP:
			{
				idx0 = evt.getActionIndex();
				int id = evt.getPointerId(idx0);
				myTouchUp(id);
				break;
			}
			case MotionEvent.ACTION_POINTER_DOWN: {
				idx0 = evt.getActionIndex();
				int id = evt.getPointerId(idx0);
				myTouchDown(id, (int)evt.getX(idx0), (int)evt.getY(idx0));
				break;
			}
			case MotionEvent.ACTION_POINTER_UP: {
				idx0 = evt.getActionIndex();
				int id = evt.getPointerId(idx0);
				myTouchUp(id);
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				idx0 = 0;
				int id = evt.getPointerId(idx0);
				touches[id].x = (int) evt.getX(idx0);
				touches[id].y = (int) evt.getY(idx0);
				myTouchMove(id, (int)evt.getX(idx0), (int)evt.getY(idx0));
			}
			default:
				break;
		}
		
		if(evt.getPointerCount() >= 1) {
			for(int idx = 0; idx < evt.getPointerCount(); idx++) {
				if(idx == idx0) continue;
				int id = evt.getPointerId(idx);
				if(action == MotionEvent.ACTION_UP ||
						action == MotionEvent.ACTION_POINTER_UP) {
					myTouchUp(id);
				} else if(action == MotionEvent.ACTION_DOWN ||
						action == MotionEvent.ACTION_POINTER_DOWN ||
						action == MotionEvent.ACTION_MOVE) {
					touches[id].x = (int) evt.getX(idx);
					touches[id].y = (int) evt.getY(idx);
					if(action == MotionEvent.ACTION_MOVE) {
						myTouchMove(id, (int)evt.getX(idx), (int)evt.getY(idx));
					} else {
						myTouchDown(id, (int)evt.getX(idx), (int)evt.getY(idx));
					}
				}
			}
		}
		return true;
		*/
		return true;
	}
}
