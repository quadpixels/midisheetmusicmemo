package com.quadpixels.midisheetmusicmemo;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.midisheetmusicmemo.ClefSymbol;
import com.midisheetmusicmemo.FileUri;
import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiFileException;
import com.midisheetmusicmemo.MidiOptions;
import com.midisheetmusicmemo.MidiPlayer;
import com.midisheetmusicmemo.R;
import com.midisheetmusicmemo.SheetMusic;
import com.midisheetmusicmemo.SheetMusicActivity;
import com.midisheetmusicmemo.TimeSigSymbol;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.IBinder;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.Toast;

public class TommyWidget extends AppWidgetProvider {
	
	static float density = 1.0f;
	
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // To prevent any ANR timeouts, we perform the update in a service
        ComponentName cn = context.startService(new Intent(context, UpdateService.class));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        Log.v("TommyWidget onUpdate", "cn=" + cn);
    }
    
    public static class UpdateService extends Service {
        @Override
        public void onStart(Intent intent, int startId) {
            
            // Build the widget update for today
            RemoteViews updateViews = buildUpdate(this);

            // Push update for this widget to the home screen
            ComponentName thisWidget = new ComponentName(this, TommyWidget.class);
            AppWidgetManager manager = AppWidgetManager.getInstance(this.getApplicationContext());
            manager.updateAppWidget(thisWidget, updateViews);
            
            Log.v("TommyWidget Svc onStart", "" + thisWidget + ", " + manager + ", " + updateViews);
            
            stopSelf();
        }
    	
		@Override
		public IBinder onBind(Intent intent) {
			return null;
		}
		

        public RemoteViews buildUpdate(Context ctx) {
        	Log.v("TommyWidget buildUpdate", "blah");
	        // Pick out month names from resources
	        Resources res = ctx.getResources();
	        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_sheetmusic);
	        Log.v("TommyView Svc buildUpdate", "" + views);
            
            Bitmap bmp;
            {
            	ClefSymbol.LoadImages(ctx);
        		TimeSigSymbol.LoadImages(ctx);
        		MidiPlayer.LoadImages(ctx);
        		
        		
        		// Fallback default.
        		Uri uri = Uri.parse("file:///android_asset/Bach__Invention_No._13.mid");
        		String display_name = uri.getLastPathSegment();
        		FileUri fileuri0 = new FileUri(uri, display_name);
        		

        		byte[] data;
        		
        		// Stole code of the getData function...
        		// Try until successfully read a file.
        		
        		// Stolen from

                ArrayList<FileUri> filelist = new ArrayList<FileUri>();
                SharedPreferences settings = getSharedPreferences("midisheetmusic.recentFiles", 0);
                String recentFilesString = settings.getString("recentFiles", null);
                if (recentFilesString == null) {
                    filelist.add(fileuri0);
                }
                
                
                try {
                    JSONArray jsonArray = new JSONArray(recentFilesString);
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject obj = jsonArray.getJSONObject(i);
                        FileUri file;
                        
                        try {
                            String displayName = obj.optString("displayName", null);
                            String uriString = obj.optString("uri", null);

                            if (displayName == null || uriString == null) {
                                return null;
                            }
                            uri = Uri.parse(uriString);
                            file = new FileUri(uri, displayName);
                        }
                        catch (Exception e) {
                            file = null;
                        }
                        
                        if (file != null) {
                            filelist.add(file);
                        }
                    }
                }
                catch (Exception e) {
                }
        		
                data = null;
                for(FileUri fileuri : filelist) {
        			try {
        	            int totallen, len, offset;
        	            data = new byte[4096];
        	            // First, determine the file length
        	            for(int i=0; i<data.length; i++) data[i] = 0x00;
        	            InputStream file;
        	            String uriString = fileuri.toString();
        	            Log.v("TommyWidget", "Trying to read " + uriString);
        	            if (uriString.startsWith("file:///android_asset/")) {
        	                AssetManager asset = this.getResources().getAssets();
        	                String filepath = uriString.replace("file:///android_asset/", "");
        	                file = asset.open(filepath);
        	            }
        	            else if (uriString.startsWith("content://")) {
        	                ContentResolver resolver = this.getContentResolver(); 
        	                file = resolver.openInputStream(fileuri.getUri());
        	            }
        	            else {
        	                file = new FileInputStream(fileuri.getUri().getPath());
        	            }
        	            totallen = 0;
        	            len = file.read(data, 0, 4096);
        	            while (len > 0) {
        	                totallen += len;
        	                len = file.read(data, 0, 4096);
        	            }
        	            file.close();
        	        
        	            // Now read in the data
        	            offset = 0;
        	            data = new byte[totallen];

        	            if (uriString.startsWith("file:///android_asset/")) {
        	                AssetManager asset = this.getResources().getAssets();
        	                String filepath = uriString.replace("file:///android_asset/", "");
        	                file = asset.open(filepath);
        	            } else if (uriString.startsWith("content://")) {
        	                ContentResolver resolver = this.getContentResolver(); 
        	                file.close();
        	                file = resolver.openInputStream(fileuri.getUri());
        	            } else {
        	                file = new FileInputStream(fileuri.getUri().getPath());
        	            }
        	            while (offset < totallen) {
        	                len = file.read(data, offset, totallen - offset);
        	                if (len <= 0) {
        	                    throw new MidiFileException("Error reading midi file", offset);
        	                }
        	                offset += len;
        	            }
        	            file.close();
        	            fileuri0 = fileuri;
        	            break;
        	        }
        	        catch (Exception e) {
        	        	e.printStackTrace();
        	            continue;
        	        }
        		}
    		
        		Log.v("Test2", String.format("midi_data = %d bytes, file = %s", 
        				data.length, fileuri0.toString()));
        		MidiFile midifile = new MidiFile(data, display_name);
        		
        		
        		SheetMusic sheet = new SheetMusic(ctx);
        		sheet.is_tommy_linebreak = true;
        		sheet.is_first_measure_out_of_boundingbox = false;
        		sheet.getHolder().setFixedSize(20, 20);
        		MidiOptions options = new MidiOptions(midifile);
        		sheet.init(midifile, options);
        		sheet.ComputeMeasureHashesNoRender(800);
        		
        		int num_measures = sheet.getNumMeasures();
        		int num_staffs   = sheet.getNumStaffs();
        		Random random = new Random();
        		int midx = random.nextInt(num_measures), sidx = random.nextInt(num_staffs);
        		bmp = sheet.RenderTile(midx, sidx, 1.0f, 1.0f);
        		
        		Intent intent = new Intent(this, TommyIntroActivity.class);
	            intent.putExtra(SheetMusicActivity.MidiTitleID, fileuri0.toString());
	            intent.putExtra(SheetMusicActivity.MidiDataID,  data);
	            intent.putExtra(TommyConfig.FILE_URI_ID,        fileuri0.toStringFull());
	            PendingIntent pending_intent = PendingIntent.getActivity(ctx,
                        0 /* no requestCode */, intent, 0 /* no flags */);
	            views.setOnClickPendingIntent(R.id.widget_image_view, pending_intent);
            }
            
            views.setImageViewBitmap(R.id.widget_image_view, bmp);
	        return views;
	    }
    }
}
