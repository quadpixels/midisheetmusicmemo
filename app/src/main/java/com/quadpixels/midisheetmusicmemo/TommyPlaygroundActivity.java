package com.quadpixels.midisheetmusicmemo;

import java.util.ArrayList;
import java.util.Random;
import java.util.zip.CRC32;

import com.midisheetmusicmemo.ChordSymbol;
import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiOptions;
import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.midisheetmusicmemo.MusicSymbol;
import com.quadpixels.midisheetmusicmemo.R;
import com.midisheetmusicmemo.SheetMusic;
import com.midisheetmusicmemo.SheetMusicActivity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

public class TommyPlaygroundActivity extends Activity {
	
	byte[] midi_data;
	String midi_title, midi_uri_string;
	SheetMusic sheet;
	MidiFile midi_file; MidiOptions options;
	long midiCRC;
	ImageView image_view;
	TextView text_view;
	Bitmap curr_tile = null;
	int measure_idx = 0, staff_idx = 0, nupds;
	int NUM_MEASURES = 0, NUM_STAFFS = 0;
	Button btnU, btnD, btnL, btnR, btnBMK;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.test1);
		
		//
		midi_data    =    this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title   =    this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		midi_uri_string = this.getIntent().getStringExtra(TommyConfig.FILE_URI_ID);
		setTitle(midi_title);
		
		midi_file = new MidiFile(midi_data, midi_title);
		options = new MidiOptions(midi_file);
		Context ctx = getApplicationContext();
		
		{
			CRC32 crc = new CRC32();
			crc.update(midi_data);
			SharedPreferences settings = getSharedPreferences(TommyConfig.TOMMY_PREF_NAME, MODE_PRIVATE);
			midiCRC = crc.getValue();
			String json = settings.getString("" + midiCRC, null);
	        MidiOptions savedOptions = MidiOptions.fromJson(json);
	        if (savedOptions != null) {
	            options.merge(savedOptions);
	        } else {
	        	Log.v("TommyIntroActivity", "Saved MIDI preference is null");
	        }
		}
		
		midi_file = new MidiFile(midi_data, midi_title);
		
		if(MidiSheetMusicActivity.sheet0 == null) {
			MidiSheetMusicActivity.sheet0 = new SheetMusic(ctx);
			sheet = MidiSheetMusicActivity.sheet0;
			sheet.is_tommy_linebreak = true; // Otherwise NullPointer Error
			sheet.is_first_measure_out_of_boundingbox = false;
			sheet.getHolder().setFixedSize(20, 20);
			sheet.init(midi_file, options);
			sheet.setVisibility(View.GONE);
			sheet.tommyFree();
		} else {
			sheet = MidiSheetMusicActivity.sheet0;
			sheet.init(midi_file, options);
		}
		
		sheet.is_tommy_linebreak = true; // Otherwise NullPointer Error
		sheet.init(midi_file, options);
		sheet.setVisibility(View.GONE);
		sheet.tommyFree();
		sheet.ComputeMeasureHashesNoLineBreakNoRender();
		NUM_MEASURES = sheet.getNumMeasures();
		NUM_STAFFS   = sheet.getNumStaffs();
		
		image_view = (ImageView) findViewById(R.id.imageView1);
		text_view  = (TextView)  findViewById(R.id.textView1);
		
		refreshImageViewAndText();
		
		btnU = (Button) findViewById(R.id.test1ButtonUp);
		btnD = (Button) findViewById(R.id.test1ButtonDown);
		btnL = (Button) findViewById(R.id.test1ButtonLeft);
		btnR = (Button) findViewById(R.id.test1ButtonRight);
		btnBMK = (Button)findViewById(R.id.test1ButtonBmk);
		
		btnU.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View arg0) {
				onStaffMeasureIdxesChange(-1, 0);
			}
		});
		
		btnD.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				onStaffMeasureIdxesChange(1, 0);
			}
		});
		
		btnL.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				onStaffMeasureIdxesChange(0, -1);
			}
		});
		
		btnR.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				onStaffMeasureIdxesChange(0, 1);
			}
		});
		
		btnBMK.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Thread bmk_thd = new Thread(new Runnable() {
					@Override
					public void run() {
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								btnU.setEnabled(false); btnD.setEnabled(false);
								btnL.setEnabled(false); btnR.setEnabled(false);
								btnBMK.setEnabled(false);
							}
						});
						
						Runnable set_img_runnable = new Runnable() {
							@Override
							public void run() {
								image_view.setImageBitmap(curr_tile);
							}
						};
						
						Random rnd = new Random();
						long millis = System.currentTimeMillis();
						nupds = 0;
						final int DURATION = 1000;
						while (true) {
							if(System.currentTimeMillis() > millis + DURATION) { break; }
							measure_idx = rnd.nextInt(NUM_MEASURES);
							staff_idx   = rnd.nextInt(NUM_STAFFS);
							curr_tile = sheet.RenderTile(measure_idx, staff_idx, 1.0f, 1.0f);
							runOnUiThread(set_img_runnable);
							nupds ++;
						}
						
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								btnU.setEnabled(true); btnD.setEnabled(true);
								btnL.setEnabled(true); btnR.setEnabled(true);
								btnBMK.setEnabled(true);
								text_view.setText(String.format("%.1f FPS",
									1.0*nupds/(DURATION/1000.0)));
							}
						});
					}
				});
				
				bmk_thd.start();
			}
		});
	}
	
	void onStaffMeasureIdxesChange(int delta_sidx, int delta_midx) {
		int old_sidx = staff_idx, old_midx = measure_idx;
		staff_idx += delta_sidx; measure_idx += delta_midx;
		if(staff_idx >= NUM_STAFFS) { staff_idx = NUM_STAFFS - 1; }
		if(staff_idx < 0) { staff_idx = 0; }
		if(measure_idx >= NUM_MEASURES) { measure_idx = NUM_MEASURES - 1; }
		if(measure_idx < 0) { measure_idx = 0; }
		if(staff_idx != old_sidx || measure_idx != old_midx) { refreshImageViewAndText(); }
	}
	
	void refreshImageViewAndText() {
		if(curr_tile != null && curr_tile.isRecycled() == false) { curr_tile.recycle(); }
		curr_tile = sheet.RenderTile(measure_idx, staff_idx, 1.0f, 1.0f);
		image_view.setImageBitmap(curr_tile);
		String x = String.format("Staff %d Measure %d", staff_idx, measure_idx);
		ArrayList<MusicSymbol> syms = sheet.getSymbolsInMeasure(measure_idx, staff_idx);
		StringBuilder sb = new StringBuilder();
		boolean is_first = true;
		for(MusicSymbol ms : syms) { 
			if(ms instanceof ChordSymbol) {
				ChordSymbol cs = (ChordSymbol) ms;
				if(is_first == false) sb.append(" ");
				sb.append(cs.toMyString());
				is_first = false;
			}
		}
		sb.append("\n");
		sb.append(x);
		text_view.setText(sb.toString());
	}
}
