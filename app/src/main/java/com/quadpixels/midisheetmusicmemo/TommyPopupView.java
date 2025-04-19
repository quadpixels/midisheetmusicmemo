// 2014-05-07 Start contemplating readme overlay view

package com.quadpixels.midisheetmusicmemo;

import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.quadpixels.midisheetmusicmemo.R;
import com.quadpixels.midisheetmusicmemo.R.string;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.text.Layout.Alignment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

public class TommyPopupView extends View {
	
	ViewGroup parent;
	View view;
	Context ctx;
	Rect bb1 = new Rect();
	TextPaint textpaint;
	
	enum HelpInfos {
		INFO_FIRST_MEASURE,
		INFO_BOTTOM_PANEL,
		INFO_START_HERE,
		INFO_STATISTICS,
		
		INFO_RECYCLE_ZONE,
		INFO_CHOICES_ZONE
	};
	
	boolean is_transparent = false;
	
	int width = 320, height = 480;
	Paint paint;
	StaticLayout txtlayout0 = null;
	String msg11, msg12, msg21, msg31, msg32, msg22, msg23;
	HelpInfos which_help;
	
	public TommyPopupView(Context context, ViewGroup _parent, View tiv, HelpInfos _which) {
		super(context);
		parent = _parent;
		paint = new Paint();
		ctx = context;
		msg12 = (String) ctx.getResources().getText(R.string.help12);
		msg21 = (String) ctx.getResources().getText(R.string.help21);
		msg22 = (String) ctx.getResources().getText(R.string.help22);
		msg23 = (String) ctx.getResources().getText(R.string.help23);
		msg31 = (String) ctx.getResources().getText(R.string.help31);
		msg32 = (String) ctx.getResources().getText(R.string.help32);
		which_help = _which;
		view = tiv;
		textpaint = new TextPaint(paint);
	}
	
	void dimExceptBB(Canvas c, Rect bb) {
		paint.setColor(0xCC000000);
		paint.setStyle(Style.FILL);
		c.drawRect(0, 0, bb.left, height, paint);
		c.drawRect(bb.right, 0, width, height, paint);
		c.drawRect(bb.left, 0, bb.right, bb.bottom, paint);
		c.drawRect(bb.left, bb.top, bb.right, height, paint);
	}
	
	@Override
	public void draw(Canvas c) {
		if(bb1.top == -1 && bb1.bottom == -1 && bb1.left == -1 && bb1.right == -1) {
			update();
		}
		
		paint.setAntiAlias(true);
		c.drawColor(0x00AAAAAA);
		
		Rect bb = bb1;
		
		if(is_transparent == false) {
			
			paint.setTextSize(MidiSheetMusicActivity.density * 16.0f);
			dimExceptBB(c, bb1);
			
			paint.setColor(0xFFFFFFFF);
			paint.setStyle(Style.FILL);
			paint.setStrokeWidth(1.0f);
			paint.setTextAlign(Align.CENTER);
			
			if(which_help == HelpInfos.INFO_START_HERE)
				c.drawText(msg12, (bb.left+bb.right)/2, bb.bottom - paint.descent(), paint);
			
			if(txtlayout0 != null) {
				c.save();
				int txt_hgt = txtlayout0.getHeight();
				if(bb.bottom <= txt_hgt) {
					c.translate(0, bb.top);
				} else {
					c.translate(0, bb.bottom - txt_hgt);
				}
				if(txtlayout0 != null)
				txtlayout0.draw(c);
				c.restore();
			}
		}
	}
	
	// Copied from that of TommyView2
	@Override 
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
	   int parentWidth = MeasureSpec.getSize(widthMeasureSpec);
	   int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
	   this.setMeasuredDimension(parentWidth, parentHeight);
	   width = parentWidth; height = parentHeight;
	   Log.v("TommyIntroView.onMeasure", String.format("W=%d, H=%d", width, height));
	   super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	   setMeasuredDimension(width, height);
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent evt) {
		int action = evt.getActionMasked();
		switch(action) {
		case MotionEvent.ACTION_UP:
			switch(which_help) {
			case INFO_FIRST_MEASURE:
				which_help = HelpInfos.INFO_BOTTOM_PANEL;
				update(); postInvalidate();
				break;
			case INFO_BOTTOM_PANEL:
				which_help = HelpInfos.INFO_START_HERE;
				update(); postInvalidate(); break;
			case INFO_STATISTICS:
				is_transparent = true;
				parent.removeView(this);
				setReadmeShownFlag("readme1_shown");
				break;
			case INFO_START_HERE:
				which_help = HelpInfos.INFO_STATISTICS;
				update(); postInvalidate(); break;
			case INFO_RECYCLE_ZONE:
				which_help = HelpInfos.INFO_CHOICES_ZONE;
				update();
				postInvalidate();
				break;
			case INFO_CHOICES_ZONE:
				is_transparent = true;
				parent.removeView(this);
				setReadmeShownFlag("readme2_shown");
				break;
			}
		}
		return !is_transparent;
	}
	
	private void setReadmeShownFlag(String key) {
		SharedPreferences prefs_readme = ctx.getSharedPreferences("readme", Context.MODE_PRIVATE);
		Editor edt = prefs_readme.edit();
		edt.putBoolean(key, true);
		edt.commit();
	}
	
	private void initMultilineText(String x) {
		textpaint.setAntiAlias(true);
		textpaint.setStyle(Style.FILL);
		textpaint.setTextSize(12.0f * MidiSheetMusicActivity.density);
		textpaint.setColor(Color.WHITE);
		txtlayout0 = new StaticLayout(x, textpaint, width, Alignment.ALIGN_CENTER, 1.0f, 1.0f, true);
		Log.v("tommypopupview initmultiline", x);
	}
	
	public void update() {
		// Message on the center of the screen.
		switch(which_help) {
		case INFO_FIRST_MEASURE:
			((TommyIntroView)view).help_getFirstLineVisibleMeasureBB(bb1);
			initMultilineText(msg21); break;
		case INFO_START_HERE:
			((TommyIntroView)view).help_getStartQuizBB(bb1);
			txtlayout0 = null;
			break;
		case INFO_BOTTOM_PANEL:
			((TommyIntroView)view).help_getStayBottomBB(bb1);
			initMultilineText(msg22); break;
		case INFO_STATISTICS:
			((TommyIntroView)view).help_getStatsButtonsBB(bb1);
			initMultilineText(msg23); break;
		case INFO_RECYCLE_ZONE:
			((TommyView2)view).help_getRecycleZoneBB(bb1);
			initMultilineText(msg31); break;
		case INFO_CHOICES_ZONE:
			((TommyView2)view).help_getSelectionZoneBB(bb1);
			initMultilineText(msg32); break;
		}
	}
}
