package com.quadpixels.midisheetmusicmemo1;

import com.midisheetmusicmemo.MidiSheetMusicActivity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.view.View;

public class TommyKeyboardView extends View {

	int lines[] = {
		0, 0, 164, 0, 
		
		// C
		0,  0,  0,  144,
		13, 0,  13, 94,
		13, 94, 28, 94,
		23, 94, 23, 144,
		
		// C#
		28, 0,  28, 94,
		
		// D
		42, 0, 42, 94,
		42, 94, 56, 94,
		47, 94, 47, 144,
		
		// D#
		56, 0, 56, 94,
		
		// E
		70, 0, 70, 144,
		
		// F
		82, 0, 82, 94,
		82, 94, 96, 94,
		93, 94, 93, 144,
		
		// F#
		96, 0, 96, 94,
		
		// G
		117, 94, 117, 144,
		109, 0, 109, 94,
		109, 94, 123, 94,
		
		// G#
		123, 0, 123, 94,
		
		// A
		136, 0, 136, 94,
		136, 94, 150, 94,
		140, 94, 140, 144,
		
		// A#
		150, 0, 150, 94,
		
		// B
		164, 0, 164, 144,

		0, 144, 164, 144
	};
	
	Paint paint = new Paint();
	float density;
	
	public TommyKeyboardView(Context context, AttributeSet attributes) {
		super(context);
		paint = new Paint();
		density = MidiSheetMusicActivity.density;
	}

	@Override
	public void draw(Canvas canvas) {
		paint.setColor(Color.BLUE);
		paint.setStyle(Style.STROKE);
		paint.setAntiAlias(true);
		paint.setStrokeWidth(2.0f * density);
		
		int W = this.getWidth(), H = this.getHeight();
		for(int i=0; i<lines.length; i=i+4) {
			float x0 = lines[i] * 1.0f * W / 164,
				  x1 = lines[i+2] * 1.0f * W / 164,
				  y0 = lines[i+1] * 1.0f * H / 144,
				  y1 = lines[i+3] * 1.0f * H / 144;
			canvas.drawLine(x0, y0, x1, y1, paint);
		}
	}
}
