/*
 * Copyright (c) 2011-2012 Madhav Vaidyanathan
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 */

package com.midisheetmusicmemo;

import com.midisheetmusicmemo.R;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.LinearLayout.LayoutParams;
import android.content.*;
import android.content.SharedPreferences.Editor;
import android.content.res.*;
import android.graphics.*;

/** @class MidiSheetMusicActivity
 * This is the launch activity for MidiSheetMusic.
 * It simply displays the splash screen, and a button to choose a song.
 */
public class MidiSheetMusicActivity extends Activity {
	static boolean DEBUG = false, USE_ORIGINAL_MSM = false, USE_FAST_RENDERING_METHOD = true, SHOW_README=true;
	static boolean IS_TOMMY = false;
	static SheetMusic sheet0;
	static float density;
	LinearLayout linear_layout;
	public static MidiSheetMusicActivity activity;
	private CheckBox cb, cb2, cb3;
    LinearLayout outer_container, inner_container, pad_left, pad_right, pad_top, pad_bottom;
    SharedPreferences prefs_readme;
    
    long last_click_millis;
    int num_click;
	
	void setPadding() {
		Display disp = this.getWindowManager().getDefaultDisplay();  
		int w = disp.getWidth(), h = disp.getHeight();
		Log.v("MidiSheetMusicActivity", "setPadding, w="+w+", h="+h);
     	int pad_h = 0, pad_v = 0;
     	if(w > h) {
     		pad_v = 0; pad_h = (w-h)/2;
     	} else {
     		pad_h = 0; pad_v = (h-w)/2;
     	}
     	outer_container.setBackgroundColor(0xFF666666);
     	LinearLayout.LayoutParams lp;
     	lp = (LayoutParams) pad_left.getLayoutParams();
     	lp.width = pad_h;
     	pad_left.setLayoutParams(lp);
     	lp = (LayoutParams) pad_right.getLayoutParams();
     	lp.width = pad_h;
     	pad_right.setLayoutParams(lp);
     	lp = (LayoutParams) pad_top.getLayoutParams();
     	lp.height = pad_v;
     	pad_top.setLayoutParams(lp);
     	lp = (LayoutParams) pad_bottom.getLayoutParams();
     	lp.height = pad_v;
     	pad_bottom.setLayoutParams(lp);
	}
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	Log.v("MidiSheetMusicActivity", "onCreate");
    	MidiSheetMusicActivity.activity = this;
        super.onCreate(savedInstanceState);
        MidiFile.loadStrings(this); // Beginning to support multiple languages

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        density = dm.density;
        Log.v("MidiSheetMusicActivity", "Density="+density);
        
        loadImages();
        setContentView(R.layout.main);
        prefs_readme = getApplicationContext().getSharedPreferences("readme", Context.MODE_PRIVATE);
        
        {
	        outer_container = (LinearLayout)findViewById(R.id.outer_container);
	        inner_container = (LinearLayout)findViewById(R.id.inner_container);
	        pad_left = (LinearLayout)findViewById(R.id.padding_left);
	        pad_right = (LinearLayout)findViewById(R.id.padding_right);
	        pad_top = (LinearLayout)findViewById(R.id.padding_top);
	        pad_bottom = (LinearLayout)findViewById(R.id.padding_bottom);
	        setPadding();
        }
        
        {
        	outer_container.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View arg0) {
					if(num_click == -999) return;
					long millis = System.currentTimeMillis();
					if(millis > last_click_millis + 400) {
						num_click = 0;
					}
					num_click += 1;
					if(num_click >= 7) {
						Toast.makeText(getApplicationContext(), "Developer options enabled!", Toast.LENGTH_SHORT).show();
						num_click = -999;
						cb.setVisibility(View.VISIBLE);
						cb2.setVisibility(View.VISIBLE);
						cb3.setVisibility(View.VISIBLE);
					}
					last_click_millis = millis;
				}
			});
        }
        
        Button button = (Button) findViewById(R.id.choose_song);
        button.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        chooseSong();
                    }
                }
        );
        
        cb = (CheckBox)findViewById(R.id.checkBox1);
        cb.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				USE_ORIGINAL_MSM = isChecked;
			}
        });

        cb2 = (CheckBox)findViewById(R.id.checkBox2);
        cb2.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				USE_FAST_RENDERING_METHOD = isChecked;
			}
        });
        
        cb3 = (CheckBox)findViewById(R.id.checkBox3);
        cb3.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				DEBUG = isChecked;
			}
        });
        
        Button btn_hlp = (Button)findViewById(R.id.button_help);
        btn_hlp.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showHelp();
			}
		});
        
        Button btn1 = (Button)findViewById(R.id.button_reset_readme);
        btn1.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(MidiSheetMusicActivity.this, TestActivity.class);
				startActivity(intent);
			}
		});
        
        Button btn_reset_readme = (Button)findViewById(R.id.reset_readme);
        btn_reset_readme.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				resetQuickReadme();
			}
		});
    }

    public static void resetQuickReadme() {
		Editor edt = activity.prefs_readme.edit();
		edt.putBoolean("readme1_shown", false);
		edt.putBoolean("readme2_shown", false);
		edt.commit();
    }
    
    /** Start the ChooseSongActivity when the "Choose Song" button is clicked */
    private void chooseSong() {
        Intent intent = new Intent(this, ChooseSongActivity.class);
        startActivity(intent);
    }

    /** Load all the resource images */
    private void loadImages() {
        ClefSymbol.LoadImages(this);
        TimeSigSymbol.LoadImages(this);
        MidiPlayer.LoadImages(this);
    }

    private void showHelp() {
    	Intent intent = new Intent(this, HelpActivity.class);
    	startActivity(intent);
    }
}

