package com.quadpixels.midisheetmusicmemo1;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/* loaded from: classes.dex */
public class SquareLinearLayout extends LinearLayout {
	public SquareLinearLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public SquareLinearLayout(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override // android.widget.LinearLayout, android.view.View
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int w = View.MeasureSpec.getSize(widthMeasureSpec);
		int h = View.MeasureSpec.getSize(heightMeasureSpec);
		if (w > h) {
			w = h;
		}
		super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY));
	}
}

