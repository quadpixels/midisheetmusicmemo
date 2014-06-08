package com.quadpixels.midisheetmusicmemo;

import java.util.zip.CRC32;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.midisheetmusicmemo.ChooseSongActivity;
import com.midisheetmusicmemo.ClefSymbol;
import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiOptions;
import com.midisheetmusicmemo.MidiPlayer;
import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.midisheetmusicmemo.R;
import com.midisheetmusicmemo.SettingsActivity;
import com.midisheetmusicmemo.SheetMusic;
import com.midisheetmusicmemo.SheetMusicActivity;
import com.midisheetmusicmemo.TimeSigSymbol;
import com.quadpixels.midisheetmusicmemo.TommyPopupView.HelpInfos;

// 2014-03-14 Currently rotating screen stops music (the user has to restart it) 

public class TommyIntroActivity extends Activity {
	public static TommyPopupView popupview; 
	TommyIntroView view;
	Bundle bundle;
	RelativeLayout relative_layout;
	byte[] midi_data;
	String midi_title, midi_uri_string;
	Context ctx;
	MidiPlayer player;
	MidiOptions options;
	MidiFile midi_file;
	long midiCRC;
	SheetMusic sheet0;
	SharedPreferences prefs_colorscheme, prefs_readme;
	int playcount, quizcount;
	String error_no_tracks_selected;
	boolean is_error_toast_shown = false;
	
	int color_scheme_idx = 0;
	
	// Midi Options 相关
	private static final int SETTINGS_REQUEST_CODE = 1;
	int last_seekbar_progress = 99;
	
	public void onCreate(Bundle _bundle) {
		super.onCreate(_bundle);
        
		bundle = _bundle;
		ctx = getApplicationContext();

        ClefSymbol.LoadImages(ctx);
        TimeSigSymbol.LoadImages(ctx);
        MidiPlayer.LoadImages(ctx);
		
		prefs_colorscheme  = ctx.getSharedPreferences("colorscheme", Context.MODE_PRIVATE);
		prefs_readme       = ctx.getSharedPreferences("readme", Context.MODE_PRIVATE);

		midi_data = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		midi_uri_string = this.getIntent().getStringExtra(TommyConfig.FILE_URI_ID);
		setTitle(midi_title);
		
		midi_file = new MidiFile(midi_data, midi_title);

		// Settings used by original MidiSheetMusic.
		options = new MidiOptions(midi_file);
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
		
		color_scheme_idx = prefs_colorscheme.getInt(midi_uri_string, 0);
		TommyConfig.setStyleIdx(color_scheme_idx);
		error_no_tracks_selected = this.getResources().getString(R.string.error_no_tracks_selected);
		createView();
	}
	
	public boolean isMidiPlayerPlaying() {
		if(player.getPlaystate() == player.playing) return true;
		else return false;
	}
	
	private void createView() {
		boolean readme_shown = prefs_readme.getBoolean("readme1_shown", false);
		
		if(relative_layout == null) {
			relative_layout = new RelativeLayout(ctx);
		}
		if(view == null) {
			view = new TommyIntroView(this, bundle, midi_data, midi_title, midi_uri_string, options, this);
		}
		if(relative_layout.getChildCount() == 0) {
			relative_layout.addView(view);
		}
		setContentView(relative_layout);

		// Create TommyIntroView first, then TommyIntroPopupView.
		if(!readme_shown) {
			popupview = new TommyPopupView(ctx, relative_layout, this.view, HelpInfos.INFO_FIRST_MEASURE);
			relative_layout.addView(popupview);
			popupview.view = this.view;
		}
		player = new MidiPlayer(this);
		player.SetMidiFile(midi_file, options, view.sheet);
	}
	
	public void stopMidiPlayer() {
		player.Stop();
        this.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}
	
	public void startMidiPlayer() {
		player.Play();
	}
	
	
	public void startPlay() {
		int tracks_shown = 0;
		for(int i=0; i<options.tracks.length; i++) {
			if(options.tracks[i] == true) 
				tracks_shown ++;
		}
		if(tracks_shown > 0) {
			Intent intent = new Intent();
	        intent.setClass(TommyIntroActivity.this, TommyView2Activity.class);
	        intent.putExtra(SheetMusicActivity.MidiDataID, midi_data);
	        intent.putExtra(SheetMusicActivity.MidiTitleID, midi_title.toString());
	        intent.putExtra(TommyConfig.FILE_URI_ID, midi_uri_string);
	        startActivity(intent);
	        player.Stop();
	        finish();
		} else {
			if(!is_error_toast_shown) {
				Toast.makeText(ctx, error_no_tracks_selected, Toast.LENGTH_LONG).show();
				is_error_toast_shown = true;
			}
		}
	}
	
	public void onDestroy() {
		super.onDestroy();
		if(isFinishing()) {
			player.Stop();
			player = null;
			if(view!=null) {
				view.free();
				view = null;
				MidiSheetMusicActivity.sheet0 = null;
			}
			ctx = null;
			midi_data = null;
			midi_title = null;
			System.gc();
			ChooseSongActivity.logHeap();
			finish();
		}
	}
	
	void changeSettings() {
		stopMidiPlayer();
        MidiOptions defaultOptions = new MidiOptions(view.midifile);
        Intent intent = new Intent(ctx, SettingsActivity.class);
        intent.putExtra(SettingsActivity.settingsID, options);
        intent.putExtra(SettingsActivity.defaultSettingsID, defaultOptions);
        intent.putExtra(TommyConfig.IS_FROM_TOMMY_ACTIVITY, 1);
        intent.putExtra(TommyConfig.FILE_URI_ID, midi_uri_string);
        intent.putExtra(TommyConfig.FILE_CHECKSUM_ID, midiCRC);
        startActivityForResult(intent, SETTINGS_REQUEST_CODE);
    }

	protected void onSaveInstanceState(Bundle bundle) {
		Log.v("TommyView2Activity", "onSaveInstanceState called");
		view.saveState(bundle);
	}
	
	@Override
    protected void onActivityResult (int requestCode, int resultCode, Intent intent) {
		Log.v("TommyIntroActivity", "OnActivityResult");
        if (requestCode != SETTINGS_REQUEST_CODE) {
            return;
        }
        options = (MidiOptions) 
            intent.getSerializableExtra(SettingsActivity.settingsID);

        // Check whether the default instruments have changed.
        for (int i = 0; i < options.instruments.length; i++) {
            if (options.instruments[i] !=  
                midi_file.getTracks().get(i).getInstrument()) {
                options.useDefaultInstruments = false;
            }
        }
        // Save the options. 
        SharedPreferences.Editor editor = getSharedPreferences(TommyConfig.TOMMY_PREF_NAME, MODE_PRIVATE).edit();
        editor.putBoolean("scrollVert", options.scrollVert);
        editor.putInt("shade1Color", options.shade1Color);
        editor.putInt("shade2Color", options.shade2Color);
        editor.putBoolean("showPiano", options.showPiano);
        String json = options.toJson();
        if (json != null) {
            editor.putString("" + midiCRC, json);
        }
        editor.commit();
        
        // Recreate Sheet Music
        ViewGroup vg = (ViewGroup)(view.getParent());
        view.free();
        vg.removeView(view);
		view = new TommyIntroView(this, null, midi_data, midi_title, midi_uri_string, options, this);
		Log.v("TommyIntroActivity", "Recreated View");
		setContentView(view);
		player.SetMidiFile(midi_file, options, view.sheet);
		is_error_toast_shown = false;
    }

	
	
	public void showPickColorSchemeDialog() {
		final String[] items = ctx.getResources().getStringArray(R.array.color_schemes);
		
		ListAdapter adapter = new ArrayAdapter<String>(getApplicationContext(), 
				R.layout.choose_color_scheme_item, items) {

			ViewHolder holder;
		    class ViewHolder {
		        TextView title;
		        TextView bk_tile, bk_separator;
		    }
			public View getView(int pos, View convertView, ViewGroup parent) {
				final LayoutInflater inflater = (LayoutInflater) getApplicationContext()
		                .getSystemService(
		                        Context.LAYOUT_INFLATER_SERVICE);

		        if (convertView == null) {
		            convertView = inflater.inflate(
		                    R.layout.choose_color_scheme_item, null);

		            holder = new ViewHolder();
		            holder.title = (TextView) convertView.findViewById(R.id.item_color_scheme_name);
		            holder.bk_tile = (TextView)convertView.findViewById(R.id.item_color_scheme_bk_tile);
		            holder.bk_separator = (TextView)convertView.findViewById(R.id.item_color_scheme_bk_separator);
		            convertView.setTag(holder);
		        } else {
		            // view already defined, retrieve view holder
		            holder = (ViewHolder) convertView.getTag();
		        }       
		        holder.title.setText(items[pos]);
		        MyStyle sty = TommyConfig.getStyleByIdx(pos);
		        holder.title.setBackgroundColor(sty.background_color);
		        convertView.setBackgroundColor(sty.background_color);
		        holder.bk_tile.setBackgroundDrawable(sty.tile_bk_upper);
		        holder.bk_separator.setBackgroundDrawable(sty.background_separator);
		        return convertView;
			}
		};
		
		android.app.AlertDialog.Builder bldr;
		bldr = new AlertDialog.Builder(TommyIntroActivity.this);
		bldr.setTitle(getResources().getString(R.string.select_color_scheme));
		bldr.setAdapter(adapter, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				color_scheme_idx = which;
				TommyConfig.setStyleIdx(which);
				SharedPreferences.Editor editor = prefs_colorscheme.edit();
				editor.putInt(midi_uri_string, color_scheme_idx);
				editor.commit();
				view.onColorSchemeChanged();
			}
		});
		bldr.show();
	}
	
	public void showPickGaugeDialog() {
		final String[] gauges = TommyConfig.GAUGE_LABELS;
		android.app.AlertDialog.Builder bldr;
		bldr = new AlertDialog.Builder(TommyIntroActivity.this);
		bldr.setTitle(getResources().getString(R.string.select_gauge));
		bldr.setItems(gauges, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				switch(which) {
					case 0: default:
						view.setGaugeMode(TommyIntroView.GaugeMode.NONE); break;
					case 1:
						view.setGaugeMode(TommyIntroView.GaugeMode.LAST_5_ATTEMPTS); break;
					case 2:
						view.setGaugeMode(TommyIntroView.GaugeMode.OCCURRENCES); break;
					case 3:
						view.setGaugeMode(TommyIntroView.GaugeMode.ACCURACY); break;
					case 4:
						view.setGaugeMode(TommyIntroView.GaugeMode.DELAY); break;
					case 5:
						view.setGaugeMode(TommyIntroView.GaugeMode.EFFECTIVE_DELAY); break;
					case 6:
						view.setGaugeMode(TommyIntroView.GaugeMode.MASTERY_LEVEL); break;
				}
			}
		});
		bldr.show();
	}
	
	public void showAdjustSpeedDialog() {
		final Dialog dlg = new Dialog(this);
		LayoutInflater inflater = (LayoutInflater)this.getSystemService(LAYOUT_INFLATER_SERVICE);
		View layout = inflater.inflate(R.layout.seekbar_dialog, (ViewGroup)findViewById(R.id.seekbar_dialog_root_element));
		dlg.setContentView(layout);
		
		final TextView tv =(TextView)layout.findViewById(R.id.seekbar_dialog_textview);
		final SeekBar sb = (SeekBar) layout.findViewById(R.id.seekbar_dialog_seekbar);
		final Button btn = (Button)  layout.findViewById(R.id.seekbar_dialog_button);
		
		sb.setMax(200);
		
		SeekBar.OnSeekBarChangeListener sb_chg_listener = new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress,
					boolean fromUser) {
				float ratio = ((progress + 1) / 100.0f);
				double inverse_tempo = 1.0 / midi_file.getTime().getTempo();
		        double inverse_tempo_scaled = inverse_tempo * ratio;
		        options.tempo = (int)(1.0 / inverse_tempo_scaled);
				tv.setText(String.format("Tempo=%d", (int)(options.tempo)));
			}
		};
		sb.setOnSeekBarChangeListener(sb_chg_listener);
		
		View.OnClickListener btn_clk_listener = new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				dlg.dismiss();
				last_seekbar_progress = sb.getProgress();
				boolean flag1 = false;
				int midx = view.curr_sheet_playing_measure_idx; 
				if(isMidiPlayerPlaying()) {
					stopMidiPlayer();
					flag1 = true;
				}
				if(midx >= 0) 
					player.MoveToMeasureBegin(midx);
				if(flag1) startMidiPlayer();
			}
		};
		btn.setOnClickListener(btn_clk_listener);
		sb.setProgress(last_seekbar_progress);
		
		dlg.setTitle(R.string.playback_speed);
		dlg.show();
	}
}
