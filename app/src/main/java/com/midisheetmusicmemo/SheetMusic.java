/*
 * Copyright (c) 2007-2012 Madhav Vaidyanathan
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

import java.util.*;
import java.io.*;
import com.quadpixels.midisheetmusicmemo.TommyConfig;
import com.quadpixels.midisheetmusicmemo.TommyIntroActivity;
import com.quadpixels.midisheetmusicmemo.TommyView2Activity;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.Bitmap.Config;
import android.graphics.Paint.Style;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.view.animation.AnimationUtils;

/** @class BoxedInt **/
class BoxedInt {
    public int value;
}

/** @class SheetMusic
 *
 * The SheetMusic Control is the main class for displaying the sheet music.
 * The SheetMusic class has the following public methods:
 *
 * SheetMusic()
 *   Create a new SheetMusic control from the given midi file and options.
 * 
 * onDraw()
 *   Method called to draw the SheetMuisc
 *
 * shadeNotes()
 *   Shade all the notes played at a given pulse time.
 */
public class SheetMusic extends SurfaceView implements SurfaceHolder.Callback, ScrollAnimationListener {

	public static class Vec2 {
		public int x, y;
		public Vec2() { x = 0; y = 0; }
		public Vec2(int i, int j) {
			x=i; y=j;
		}
		public void assign(Vec2 other) {
			x=other.x; y=other.y;
		}
	};
    /* Measurements used when drawing.  All measurements are in pixels. */
    public static final int LineWidth  = 1;   /** The width of a line */
    public static final int LeftMargin = 4;   /** The left margin */
    public static final int LineSpace  = 7;   /** The space between lines in the staff */
    public static final int StaffHeight = LineSpace*4 + LineWidth*5;  /** The height between the 5 horizontal lines of the staff */

    public static final int NoteHeight = LineSpace + LineWidth; /** The height of a whole note */
    public static final int NoteWidth = 3 * LineSpace/2;        /** The width of a whole note */

    public static final int PageWidth = 800;    /** The width of each page */
    public static final int PageHeight = 1050;  /** The height of each page (when printing) */
    public static final int TitleHeight = 14;   /** Height of title on first page */

    public static final int ImmediateScroll = 1;
    public static final int GradualScroll   = 2;
    public static final int DontScroll      = 3;

    private ArrayList<Staff> staffs;  /** The array of staffs to display (from top to bottom) */
    private KeySignature mainkey;     /** The main key signature */

    private String   filename;        /** The midi filename */
    private int      numtracks;       /** The number of tracks */
    private int      numtracks_raw;   // Number of tracks in raw MIDI file.
    private float    zoom;            /** The zoom level to draw at (1.0 == 100%) */
    private boolean  scrollVert;      /** Whether to scroll vertically or horizontally */
    private int      showNoteLetters; /** Display the note letters */
    private int[]    NoteColors;      /** The note colors to use */
    private int      shade1;          /** The color for shading */
    private int      shade2;          /** The color for shading left-hand piano */
    private Paint    paint;           /** The paint for drawing */
    private boolean  surfaceReady;    /** True if we can draw on the surface */
    private Bitmap   bufferBitmap;    /** The bitmap for drawing */
    private Canvas   bufferCanvas;    /** The canvas for drawing */
    private MidiPlayer player;        /** For pausing the music */
    private int      playerHeight;    /** Height of the midi player */
    private int      screenwidth;     /** The screen width */
    private int      screenheight;    /** The screen height */

    /* fields used for scrolling */

    private int      sheetwidth;      /** The sheet music width (excluding zoom) */
    private int      sheetheight;     /** The sheet music height (excluding zoom) */
    private int      viewwidth;       /** The width of this view. */
    private int      viewheight;      /** The height of this view. */
    private int      bufferX;         /** The (left,top) of the bufferCanvas */
    private int      bufferY; 
    private int      scrollX;         /** The (left,top) of the scroll clip */
    private int      scrollY;
    private ScrollAnimation scrollAnimation;

 // Each staff has an ArrayList
    // So we have an arraylist of arraylists
    private ArrayList<ArrayList<Bitmap> > measureBMPs = new ArrayList<ArrayList<Bitmap> >();
    private ArrayList<ArrayList<Integer> > measureHashes = new ArrayList<ArrayList<Integer> >();
    public ArrayList<ArrayList<Integer>> getMeasureHashes() {
    	return measureHashes;
    }
    private ArrayList<Integer> measureHeights = new ArrayList<Integer>();
    private ArrayList<Integer> measureWidths  = new ArrayList<Integer>();
    private ArrayList<Float>   measureOverflowZooms = new ArrayList<Float>();
    private ArrayList<Float>  measurePads = new ArrayList<Float>();
    private int num_notes = 0;
    public int getNumNotes() { return num_notes; }
    public String getFilename() { return filename; }
    public int getNumMeasures() {
    	if(measureHashes.size() > 0)
    		return measureHashes.get(0).size();
    	else return 0;
    }
    public int getNumStaffs()   { return measureHashes.size(); }
    public ArrayList<Integer> getMeasureHeights() { return measureHeights; }
    public ArrayList<Integer> getMeasureWidths() { return measureWidths; }
    private Bitmap scratch;
    private int scratch_width = -999, scratch_height = -999;
    public int render_width = 800;
    public boolean is_first_measure_out_of_boundingbox = true;
    ArrayList<ArrayList<MusicSymbol>> allsymbols, allsymbols_bk;
    ArrayList<ArrayList<LyricSymbol>> lyrics;
    MidiOptions options;
    @SuppressWarnings("unchecked")
	private void resumeBackupAllSymbols() {
    	for(ArrayList<MusicSymbol> x : allsymbols) {
    		for(MusicSymbol s : x) {
    			s.setWidth(s.getMinWidth());
    			if(s instanceof ChordSymbol) {
    				ChordSymbol cs = (ChordSymbol)s;
    				cs.clearBeams();
    			}
    		}
    	}
    	SymbolWidths widths = new SymbolWidths(allsymbols, lyrics);
    	AlignSymbols(allsymbols, widths, options);
    }
    TimeSignature time_signature;
    // Added 20140307
    public boolean is_tommy_linebreak = false;
    
    public SheetMusic(Context context) {
        super(context);
        if(!(context instanceof TommyIntroActivity || context instanceof TommyView2Activity)) {
	        SurfaceHolder holder = getHolder();
	        holder.addCallback(this);
	        screenwidth = 800; screenheight = 480;
        } else {
        	is_tommy_linebreak = true;
        }
        bufferX = bufferY = scrollX = scrollY = 0;
    }
    
    // Added 20140311
    private int curr_playing_measure_idx = -1;
    private float curr_playing_measure_shade_x_begin = 0.0f,
    		      curr_playing_measure_shade_x_end   = 0.0f;
    public int getCurrentPlayingMeasure() {
    	return curr_playing_measure_idx;
    }
    public void resetCurrentPlayingMeasure() {
    	curr_playing_measure_idx = -1;
    	curr_playing_measure_shade_x_begin = 0.0f;
    	curr_playing_measure_shade_x_end   = 0.0f;
    }
    public float getCurrentPlayingMeasureShadeXBegin() {
    	return curr_playing_measure_shade_x_begin;
    }
    public float getCurrentPlayingMeasureShadeXEnd() {
    	return curr_playing_measure_shade_x_end;
    }
    public int getMeasureBeginPulse(int midx) {
    	return staffs.get(0).getMeasureBeginPulse(midx);
    }
    // Added 20140315
    public void getVisibleActualTrackIndices(ArrayList<Integer> actual_tidx) {
    	actual_tidx.clear();
    	if(numtracks_raw == 2) {
    		if(options.tracks[0])
    			actual_tidx.add(0);
    		if(options.tracks[1])
    			actual_tidx.add(1);
    	} else {
	    	if(options.twoStaffs) {
	    		actual_tidx.add(0);
	    		actual_tidx.add(1);
	    	} else {
	    		for(int i=0; i<options.tracks.length; i++) {
	    			if(options.tracks[i]==true) {
	    				actual_tidx.add(2 + i);
	    			}
	    		}
	    	}
    	}
    }
    public int getActualNumberOfTracks() {
    	if(numtracks_raw== 2) return 2;
    	else return 2+numtracks_raw;
    }
    public ArrayList<MusicSymbol> getSymbolsInMeasure(int measure_idx, int staff_idx) {
    	return staffs.get(staff_idx).getSymbolsInMeasure(measure_idx);
    }

    /** Create a new SheetMusic View.
     * MidiFile is the parsed midi file to display.
     * SheetMusic Options are the menu options that were selected.
     *
     * - Apply all the Menu Options to the MidiFile tracks.
     * - Calculate the key signature
     * - For each track, create a list of MusicSymbols (notes, rests, bars, etc)
     * - Vertically align the music symbols in all the tracks
     * - Partition the music notes into horizontal staffs
     */
    public void init(MidiFile file, MidiOptions options) {
    	numtracks_raw = file.getTracks().size();
        if (options == null) {
            options = new MidiOptions(file);
        }
        zoom = 1.0f;

        filename = file.getFileName();
        SetColors(null, options.shade1Color, options.shade2Color);
        paint = new Paint();
        paint.setTextSize(12.0f);
        Typeface typeface = Typeface.create(paint.getTypeface(), Typeface.NORMAL);
        paint.setTypeface(typeface);
        paint.setColor(Color.BLACK);
        
        ArrayList<MidiTrack> tracks = file.ChangeMidiNotes(options);
        // SetNoteSize(options.largeNoteSize);
        scrollVert = options.scrollVert;
        showNoteLetters = options.showNoteLetters;
        TimeSignature time = file.getTime(); 
        if (options.time != null) {
            time = options.time;
        }
        if (options.key == -1) {
            mainkey = GetKeySignature(tracks);
        }
        else {
            mainkey = new KeySignature(options.key);
        }
        numtracks = tracks.size();

        int lastStart = file.EndTime() + options.shifttime;

        /* Create all the music symbols (notes, rests, vertical bars, and
         * clef changes).  The symbols variable contains a list of music 
         * symbols for each track.  The list does not include the left-side 
         * Clef and key signature symbols.  Those can only be calculated 
         * when we create the staffs.
         */
        ArrayList<ArrayList<MusicSymbol>> allsymbols = 
          new ArrayList<ArrayList<MusicSymbol> >(numtracks);

        for (int tracknum = 0; tracknum < numtracks; tracknum++) {
            MidiTrack track = tracks.get(tracknum);
            ClefMeasures clefs = new ClefMeasures(track.getNotes(), time.getMeasure());
            ArrayList<ChordSymbol> chords = CreateChords(track.getNotes(), mainkey, time, clefs);
            allsymbols.add(CreateSymbols(chords, clefs, time, lastStart));
        }

        lyrics = null;
        if (options.showLyrics) {
            lyrics = GetLyrics(tracks);
        }

        /* Vertically align the music symbols */
        SymbolWidths widths = new SymbolWidths(allsymbols, lyrics);
        AlignSymbols(allsymbols, widths, options);

        staffs = CreateStaffs(allsymbols, mainkey, options, time.getMeasure());
        Log.v("SheetMusic", staffs.size() + " staffs");
        if(!is_tommy_linebreak) {
        	CreateAllBeamedChords(allsymbols, time);
        } else {
        	this.allsymbols = allsymbols;
        	this.time_signature = time;
        }
        if (lyrics != null) {
            AddLyricsToStaffs(staffs, lyrics);
        }

        /* After making chord pairs, the stem directions can change,
         * which affects the staff height.  Re-calculate the staff height.
         */
        for (Staff staff : staffs) {
            staff.CalculateHeight();
        }
        zoom = 1.0f;

        scrollAnimation = new ScrollAnimation(this, scrollVert);
    	this.options = options;
    }

    /** Calculate the size of the sheet music width and height
     *  (without zoom scaling to fit the screen).  Store the result in
     *  sheetwidth and sheetheight.
     */
    private void calculateSize() {
        sheetwidth = 0;
        sheetheight = 0;
        for (Staff staff : staffs) {
            sheetwidth = Math.max(sheetwidth, staff.getWidth());
            sheetheight += (staff.getHeight());
        }
        sheetwidth += 2;
        sheetheight += LeftMargin;
    }

    /* Adjust the zoom level so that the sheet music page (PageWidth)
     * fits within the width. If the heightspec is 0, return the screenheight.
     * Else, use the given view width/height. 
     */
    @Override
    protected void onMeasure(int widthspec, int heightspec) {
        // First, calculate the zoom level
        int specwidth = MeasureSpec.getSize(widthspec);
        int specheight = MeasureSpec.getSize(heightspec);

        if (specwidth == 0 && specheight == 0) {
            setMeasuredDimension(screenwidth, screenheight);
        }
        else if (specwidth == 0) {
            setMeasuredDimension(screenwidth, specheight);
        }
        else if (specheight == 0) {
            setMeasuredDimension(specwidth, screenheight);
        }
        else {
            setMeasuredDimension(specwidth, specheight);            
        }
    }


    /** If this is the first size change, calculate the zoom level,
     *  and create the bufferCanvas.  Otherwise, do nothing.
     */
    @Override
    protected void 
    onSizeChanged(int newwidth, int newheight, int oldwidth, int oldheight) {
        viewwidth = newwidth;
        viewheight = newheight;

        if (bufferCanvas != null) {
            callOnDraw();
            return;
        }

        calculateSize();
        if (scrollVert) {
            zoom = (float)((newwidth - 2) * 1.0 / PageWidth);
        }
        else {
            zoom = (float)( (newheight + playerHeight) * 1.0 / sheetheight);
            if (zoom < 0.9)
                zoom = 0.9f;
            if (zoom > 1.1)
                zoom = 1.1f;
        }
        if (bufferCanvas == null) {
            createBufferCanvas();
        }
        callOnDraw();
    }
    

    /** Get the best key signature given the midi notes in all the tracks. */
    private KeySignature GetKeySignature(ArrayList<MidiTrack> tracks) {
        ListInt notenums = new ListInt();
        for (MidiTrack track : tracks) {
            for (MidiNote note : track.getNotes()) {
                notenums.add(note.getNumber());
            }
        }
        return KeySignature.Guess(notenums);
    }


    /** Create the chord symbols for a single track.
     * @param midinotes  The Midinotes in the track.
     * @param key        The Key Signature, for determining sharps/flats.
     * @param time       The Time Signature, for determining the measures.
     * @param clefs      The clefs to use for each measure.
     * @ret An array of ChordSymbols
     */
    private
    ArrayList<ChordSymbol> CreateChords(ArrayList<MidiNote> midinotes, 
                                   KeySignature key,
                                   TimeSignature time,
                                   ClefMeasures clefs) {

        int i = 0;
        ArrayList<ChordSymbol> chords = new ArrayList<ChordSymbol>();
        ArrayList<MidiNote> notegroup = new ArrayList<MidiNote>(12);
        int len = midinotes.size(); 

        while (i < len) {

            int starttime = midinotes.get(i).getStartTime();
            Clef clef = clefs.GetClef(starttime);

            /* Group all the midi notes with the same start time
             * into the notes list.
             */
            notegroup.clear();
            notegroup.add(midinotes.get(i));
            i++;
            while (i < len && midinotes.get(i).getStartTime() == starttime) {
                notegroup.add(midinotes.get(i));
                i++;
            }

            /* Create a single chord from the group of midi notes with
             * the same start time.
             */
            ChordSymbol chord = new ChordSymbol(notegroup, key, time, clef, this);
            chords.add(chord);
        }

        return chords;
    }

    /** Given the chord symbols for a track, create a new symbol list
     * that contains the chord symbols, vertical bars, rests, and
     * clef changes.
     * Return a list of symbols (ChordSymbol, BarSymbol, RestSymbol, ClefSymbol)
     */
    private ArrayList<MusicSymbol> 
    CreateSymbols(ArrayList<ChordSymbol> chords, ClefMeasures clefs,
                  TimeSignature time, int lastStart) {

        ArrayList<MusicSymbol> symbols = new ArrayList<MusicSymbol>();
        symbols = AddBars(chords, time, lastStart);
        symbols = AddRests(symbols, time);
        symbols = AddClefChanges(symbols, clefs, time);

        return symbols;
    }

    /** Add in the vertical bars delimiting measures. 
     *  Also, add the time signature symbols.
     */
    private ArrayList<MusicSymbol> 
    AddBars(ArrayList<ChordSymbol> chords, TimeSignature time, int lastStart) {
        ArrayList<MusicSymbol> symbols = new ArrayList<MusicSymbol>();

        TimeSigSymbol timesig = new TimeSigSymbol(time.getNumerator(), time.getDenominator());
        symbols.add(timesig);

        /* The starttime of the beginning of the measure */
        int measuretime = 0;

        int i = 0;
        while (i < chords.size()) {
            if (measuretime <= chords.get(i).getStartTime()) {
                symbols.add(new BarSymbol(measuretime) );
                measuretime += time.getMeasure();
            }
            else {
                symbols.add(chords.get(i));
                i++;
            }
        }

        /* Keep adding bars until the last StartTime (the end of the song) */
        while (measuretime < lastStart) {
            symbols.add(new BarSymbol(measuretime) );
            measuretime += time.getMeasure();
        }

        /* Add the final vertical bar to the last measure */
        symbols.add(new BarSymbol(measuretime) );
        return symbols;
    }

    /** Add rest symbols between notes.  All times below are 
     * measured in pulses.
     */
    private
    ArrayList<MusicSymbol> AddRests(ArrayList<MusicSymbol> symbols, TimeSignature time) {
        int prevtime = 0;

        ArrayList<MusicSymbol> result = new ArrayList<MusicSymbol>( symbols.size() );

        for (MusicSymbol symbol : symbols) {
            int starttime = symbol.getStartTime();
            RestSymbol[] rests = GetRests(time, prevtime, starttime);
            if (rests != null) {
                for (RestSymbol r : rests) {
                    result.add(r);
                }
            }

            result.add(symbol);

            /* Set prevtime to the end time of the last note/symbol. */
            if (symbol instanceof ChordSymbol) {
                ChordSymbol chord = (ChordSymbol)symbol;
                prevtime = Math.max( chord.getEndTime(), prevtime );
            }
            else {
                prevtime = Math.max(starttime, prevtime);
            }
        }
        return result;
    }

    /** Return the rest symbols needed to fill the time interval between
     * start and end.  If no rests are needed, return nil.
     */
    private
    RestSymbol[] GetRests(TimeSignature time, int start, int end) {
        RestSymbol[] result;
        RestSymbol r1, r2;

        if (end - start < 0)
            return null;

        NoteDuration dur = time.GetNoteDuration(end - start);
        switch (dur) {
            case Whole:
            case Half:
            case Quarter:
            case Eighth:
                r1 = new RestSymbol(start, dur);
                result = new RestSymbol[]{ r1 };
                return result;

            case DottedHalf:
                r1 = new RestSymbol(start, NoteDuration.Half);
                r2 = new RestSymbol(start + time.getQuarter()*2, 
                                    NoteDuration.Quarter);
                result = new RestSymbol[]{ r1, r2 };
                return result;

            case DottedQuarter:
                r1 = new RestSymbol(start, NoteDuration.Quarter);
                r2 = new RestSymbol(start + time.getQuarter(), 
                                    NoteDuration.Eighth);
                result = new RestSymbol[]{ r1, r2 };
                return result; 

            case DottedEighth:
                r1 = new RestSymbol(start, NoteDuration.Eighth);
                r2 = new RestSymbol(start + time.getQuarter()/2, 
                                    NoteDuration.Sixteenth);
                result = new RestSymbol[]{ r1, r2 };
                return result;

            default:
                return null;
        }
    }

    /** The current clef is always shown at the beginning of the staff, on
     * the left side.  However, the clef can also change from measure to 
     * measure. When it does, a Clef symbol must be shown to indicate the 
     * change in clef.  This function adds these Clef change symbols.
     * This function does not add the main Clef Symbol that begins each
     * staff.  That is done in the Staff() contructor.
     */
    private
    ArrayList<MusicSymbol> AddClefChanges(ArrayList<MusicSymbol> symbols,
                                     ClefMeasures clefs,
                                     TimeSignature time) {

        ArrayList<MusicSymbol> result = new ArrayList<MusicSymbol>( symbols.size() );
        Clef prevclef = clefs.GetClef(0);
        for (MusicSymbol symbol : symbols) {
            /* A BarSymbol indicates a new measure */
            if (symbol instanceof BarSymbol) {
                Clef clef = clefs.GetClef(symbol.getStartTime());
                if (clef != prevclef) {
                    result.add(new ClefSymbol(clef, symbol.getStartTime()-1, true));
                }
                prevclef = clef;
            }
            result.add(symbol);
        }
        return result;
    }
           

    /** Notes with the same start times in different staffs should be
     * vertically aligned.  The SymbolWidths class is used to help 
     * vertically align symbols.
     *
     * First, each track should have a symbol for every starttime that
     * appears in the Midi File.  If a track doesn't have a symbol for a
     * particular starttime, then add a "blank" symbol for that time.
     *
     * Next, make sure the symbols for each start time all have the same
     * width, across all tracks.  The SymbolWidths class stores
     * - The symbol width for each starttime, for each track
     * - The maximum symbol width for a given starttime, across all tracks.
     *
     * The method SymbolWidths.GetExtraWidth() returns the extra width
     * needed for a track to match the maximum symbol width for a given
     * starttime.
     */
    private
    void AlignSymbols(ArrayList<ArrayList<MusicSymbol>> allsymbols, SymbolWidths widths, MidiOptions options) {

        // If we show measure numbers, increase bar symbol width
        if (options.showMeasures) {
            for (int track = 0; track < allsymbols.size(); track++) {
                ArrayList<MusicSymbol> symbols = allsymbols.get(track);
                for (MusicSymbol sym : symbols) {
                    if (sym instanceof BarSymbol) {
                        sym.setWidth( sym.getWidth() + NoteWidth);
                    }
                }
            }
        }

        for (int track = 0; track < allsymbols.size(); track++) {
            ArrayList<MusicSymbol> symbols = allsymbols.get(track);
            ArrayList<MusicSymbol> result = new ArrayList<MusicSymbol>();

            int i = 0;

            /* If a track doesn't have a symbol for a starttime,
             * add a blank symbol.
             */
            for (int start : widths.getStartTimes()) {

                /* BarSymbols are not included in the SymbolWidths calculations */
                while (i < symbols.size() && (symbols.get(i) instanceof BarSymbol) &&
                    symbols.get(i).getStartTime() <= start) {
                    result.add(symbols.get(i));
                    i++;
                }

                if (i < symbols.size() && symbols.get(i).getStartTime() == start) {

                    while (i < symbols.size() && 
                           symbols.get(i).getStartTime() == start) {

                        result.add(symbols.get(i));
                        i++;
                    }
                }
                else {
                    result.add(new BlankSymbol(start, 0));
                }
            }

            /* For each starttime, increase the symbol width by
             * SymbolWidths.GetExtraWidth().
             */
            i = 0;
            while (i < result.size()) {
                if (result.get(i) instanceof BarSymbol) {
                    i++;
                    continue;
                }
                int start = result.get(i).getStartTime();
                int extra = widths.GetExtraWidth(track, start);
                int newwidth = result.get(i).getWidth() + extra;
                result.get(i).setWidth(newwidth);

                /* Skip all remaining symbols with the same starttime. */
                while (i < result.size() && result.get(i).getStartTime() == start) {
                    i++;
                }
            } 
            allsymbols.set(track, result);
        }
    }


    /** Find 2, 3, 4, or 6 chord symbols that occur consecutively (without any
     *  rests or bars in between).  There can be BlankSymbols in between.
     *
     *  The startIndex is the index in the symbols to start looking from.
     *
     *  Store the indexes of the consecutive chords in chordIndexes.
     *  Store the horizontal distance (pixels) between the first and last chord.
     *  If we failed to find consecutive chords, return false.
     */
    private static boolean
    FindConsecutiveChords(ArrayList<MusicSymbol> symbols, TimeSignature time,
                          int startIndex, int[] chordIndexes,
                          BoxedInt horizDistance) {

        int i = startIndex;
        int numChords = chordIndexes.length;

        while (true) {
            horizDistance.value = 0;

            /* Find the starting chord */
            while (i < symbols.size() - numChords) {
                if (symbols.get(i) instanceof ChordSymbol) {
                    ChordSymbol c = (ChordSymbol) symbols.get(i);
                    if (c.getStem() != null) {
                        break;
                    }
                }
                i++;
            }
            if (i >= symbols.size() - numChords) {
                chordIndexes[0] = -1;
                return false;
            }
            chordIndexes[0] = i;
            boolean foundChords = true;
            for (int chordIndex = 1; chordIndex < numChords; chordIndex++) {
                i++;
                int remaining = numChords - 1 - chordIndex;
                while ((i < symbols.size() - remaining) && 
                       (symbols.get(i) instanceof BlankSymbol)) {

                    horizDistance.value += symbols.get(i).getWidth();
                    i++;
                }
                if (i >= symbols.size() - remaining) {
                    return false;
                }
                if (!(symbols.get(i) instanceof ChordSymbol)) {
                    foundChords = false;
                    break;
                }
                chordIndexes[chordIndex] = i;
                horizDistance.value += symbols.get(i).getWidth();
            }
            if (foundChords) {
                return true;
            }

            /* Else, start searching again from index i */
        }
    }


    /** Connect chords of the same duration with a horizontal beam.
     *  numChords is the number of chords per beam (2, 3, 4, or 6).
     *  if startBeat is true, the first chord must start on a quarter note beat.
     */
    private static void
    CreateBeamedChords(ArrayList<ArrayList<MusicSymbol>> allsymbols, TimeSignature time,
                       int numChords, boolean startBeat) {
        int[] chordIndexes = new int[numChords];
        ChordSymbol[] chords = new ChordSymbol[numChords];

        for (ArrayList<MusicSymbol> symbols : allsymbols) {
            int startIndex = 0;
            while (true) {
                BoxedInt horizDistance = new BoxedInt();
                horizDistance.value = 0;
                boolean found = FindConsecutiveChords(symbols, time,
                                                   startIndex,
                                                   chordIndexes,
                                                   horizDistance);
                if (!found) {
                    break;
                }
                for (int i = 0; i < numChords; i++) {
                    chords[i] = (ChordSymbol)symbols.get( chordIndexes[i] );
                }

                if (ChordSymbol.CanCreateBeam(chords, time, startBeat)) {
                    ChordSymbol.CreateBeam(chords, horizDistance.value);
                    startIndex = chordIndexes[numChords-1] + 1;
                }
                else {
                    startIndex = chordIndexes[0] + 1;
                }

                /* What is the value of startIndex here?
                 * If we created a beam, we start after the last chord.
                 * If we failed to create a beam, we start after the first chord.
                 */
            }
        }
    }


    /** Connect chords of the same duration with a horizontal beam.
     *
     *  We create beams in the following order:
     *  - 6 connected 8th note chords, in 3/4, 6/8, or 6/4 time
     *  - Triplets that start on quarter note beats
     *  - 3 connected chords that start on quarter note beats (12/8 time only)
     *  - 4 connected chords that start on quarter note beats (4/4 or 2/4 time only)
     *  - 2 connected chords that start on quarter note beats
     *  - 2 connected chords that start on any beat
     */
    private static void
    CreateAllBeamedChords(ArrayList<ArrayList<MusicSymbol>> allsymbols, TimeSignature time) {
        if ((time.getNumerator() == 3 && time.getDenominator() == 4) ||
            (time.getNumerator() == 6 && time.getDenominator() == 8) ||
            (time.getNumerator() == 6 && time.getDenominator() == 4) ) {

            CreateBeamedChords(allsymbols, time, 6, true);
        }
        CreateBeamedChords(allsymbols, time, 3, true);
        CreateBeamedChords(allsymbols, time, 4, true);
        CreateBeamedChords(allsymbols, time, 2, true);
        CreateBeamedChords(allsymbols, time, 2, false);
    }


    /** Get the width (in pixels) needed to display the key signature */
    public static int
    KeySignatureWidth(KeySignature key) {
        ClefSymbol clefsym = new ClefSymbol(Clef.Treble, 0, false);
        int result = clefsym.getMinWidth();
        AccidSymbol[] keys = key.GetSymbols(Clef.Treble);
        for (AccidSymbol symbol : keys) {
            result += symbol.getMinWidth();
        }
        return result + SheetMusic.LeftMargin + 5;
    }


    /** Given MusicSymbols for a track, create the staffs for that track.
     *  Each Staff has a maxmimum width of PageWidth (800 pixels).
     *  Also, measures should not span multiple Staffs.
     */
    private ArrayList<Staff> 
    CreateStaffsForTrack(ArrayList<MusicSymbol> symbols, int measurelen, 
                         KeySignature key, MidiOptions options,
                         int track, int totaltracks) {
        int keysigWidth = KeySignatureWidth(key);
        int startindex = 0;
        ArrayList<Staff> thestaffs = new ArrayList<Staff>(symbols.size() / 50);

        while (startindex < symbols.size()) {
            /* startindex is the index of the first symbol in the staff.
             * endindex is the index of the last symbol in the staff.
             */
            int endindex = startindex;
            int width = keysigWidth;
            int maxwidth;

            /* If we're scrolling vertically, the maximum width is PageWidth. */
            if (scrollVert) {
                maxwidth = SheetMusic.PageWidth;
            }
            else {
                maxwidth = 2000000;
            }

            while (endindex < symbols.size() &&
                   width + symbols.get(endindex).getWidth() < maxwidth) {

                width += symbols.get(endindex).getWidth();
                endindex++;
            }
            endindex--;

            /* There's 3 possibilities at this point:
             * 1. We have all the symbols in the track.
             *    The endindex stays the same.
             *
             * 2. We have symbols for less than one measure.
             *    The endindex stays the same.
             *
             * 3. We have symbols for 1 or more measures.
             *    Since measures cannot span multiple staffs, we must
             *    make sure endindex does not occur in the middle of a
             *    measure.  We count backwards until we come to the end
             *    of a measure.
             */

            if (endindex == symbols.size() - 1) {
                /* endindex stays the same */
            }
            else if (symbols.get(startindex).getStartTime() / measurelen ==
                     symbols.get(endindex).getStartTime() / measurelen) {
                /* endindex stays the same */
            }
            else {
                int endmeasure = symbols.get(endindex+1).getStartTime()/measurelen;
                while (symbols.get(endindex).getStartTime() / measurelen == 
                       endmeasure) {
                    endindex--;
                }
            }

            if (scrollVert) {
                width = SheetMusic.PageWidth;
            }
            // int range = endindex + 1 - startindex;
            ArrayList<MusicSymbol> staffSymbols = new ArrayList<MusicSymbol>();
            for (int i = startindex; i <= endindex; i++) {
                staffSymbols.add(symbols.get(i));
            }
            Staff staff = new Staff(staffSymbols, key, options, track, totaltracks);
            thestaffs.add(staff);
            startindex = endindex + 1;
        }
        return thestaffs;
    }


    /** Given all the MusicSymbols for every track, create the staffs
     * for the sheet music.  There are two parts to this:
     *
     * - Get the list of staffs for each track.
     *   The staffs will be stored in trackstaffs as:
     *
     *   trackstaffs[0] = { Staff0, Staff1, Staff2, ... } for track 0
     *   trackstaffs[1] = { Staff0, Staff1, Staff2, ... } for track 1
     *   trackstaffs[2] = { Staff0, Staff1, Staff2, ... } for track 2
     *
     * - Store the Staffs in the staffs list, but interleave the
     *   tracks as follows:
     *
     *   staffs = { Staff0 for track 0, Staff0 for track1, Staff0 for track2,
     *              Staff1 for track 0, Staff1 for track1, Staff1 for track2,
     *              Staff2 for track 0, Staff2 for track1, Staff2 for track2,
     *              ... } 
     */
    private ArrayList<Staff> 
    CreateStaffs(ArrayList<ArrayList<MusicSymbol>> allsymbols, KeySignature key, 
                 MidiOptions options, int measurelen) {

        ArrayList<ArrayList<Staff>> trackstaffs = 
          new ArrayList<ArrayList<Staff>>( allsymbols.size() );
        int totaltracks = allsymbols.size();

        for (int track = 0; track < totaltracks; track++) {
            ArrayList<MusicSymbol> symbols = allsymbols.get( track );
            trackstaffs.add(CreateStaffsForTrack(symbols, measurelen, key, 
                                                 options, track, totaltracks));
        }

        /* Update the EndTime of each Staff. EndTime is used for playback */
        for (ArrayList<Staff> list : trackstaffs) {
            for (int i = 0; i < list.size()-1; i++) {
                list.get(i).setEndTime( list.get(i+1).getStartTime() );
            }
        }

        /* Interleave the staffs of each track into the result array. */
        int maxstaffs = 0;
        for (int i = 0; i < trackstaffs.size(); i++) {
            if (maxstaffs < trackstaffs.get(i).size()) {
                maxstaffs = trackstaffs.get(i).size();
            }
        }
        ArrayList<Staff> result = new ArrayList<Staff>(maxstaffs * trackstaffs.size());
        for (int i = 0; i < maxstaffs; i++) {
            for (ArrayList<Staff> list : trackstaffs) {
                if (i < list.size()) {
                    result.add(list.get(i));
                }
            }
        }
        return result;
    }


    /** Change the note colors for the sheet music, and redraw. 
     *  This is not currently used.
     */
    public void SetColors(int[] newcolors, int newshade1, int newshade2) {
        if (NoteColors == null) {
            NoteColors = new int[12];
            for (int i = 0; i < 12; i++) {
                NoteColors[i] = Color.BLACK;
            }
        }
        if (newcolors != null) {
            for (int i = 0; i < 12; i++) {
                NoteColors[i] = newcolors[i];
            }
        }
        shade1 = newshade1;
        shade2 = newshade2;
    }

    /** Get the color for a given note number. Not currently used. */
    public int NoteColor(int number) {
        return NoteColors[ NoteScale.FromNumber(number) ];
    }

    /** Get the shade color */
    public int getShade1() { return shade1; }

    /** Get the shade2 color */
    public int getShade2() { return shade2; }

    /** Get whether to show note letters or not */
    public int getShowNoteLetters() { return showNoteLetters; }

    /** Get the main key signature */
    public KeySignature getMainKey() { return mainkey; }

    /** Get the lyrics for each track */
    private static ArrayList<ArrayList<LyricSymbol>> 
    GetLyrics(ArrayList<MidiTrack> tracks) {
       boolean hasLyrics = false;
        ArrayList<ArrayList<LyricSymbol>> result = new ArrayList<ArrayList<LyricSymbol>>();
        for (int tracknum = 0; tracknum < tracks.size(); tracknum++) {
            ArrayList<LyricSymbol> lyrics = new ArrayList<LyricSymbol>();
            result.add(lyrics);
            MidiTrack track = tracks.get(tracknum);
            if (track.getLyrics() == null) {
                continue;
            }
            hasLyrics = true;
            for (MidiEvent ev : track.getLyrics()) {
                try {
                    String text = new String(ev.Value, 0, ev.Value.length, "UTF-8");
                    LyricSymbol sym = new LyricSymbol(ev.StartTime, text);
                    lyrics.add(sym);
                }
                catch (UnsupportedEncodingException e) {}
            }
        }
        if (!hasLyrics) {
            return null;
        }
        else {
            return result;
        }
    }

    /** Add the lyric symbols to the corresponding staffs */
    static void
    AddLyricsToStaffs(ArrayList<Staff> staffs, ArrayList<ArrayList<LyricSymbol>> tracklyrics) {
        for (Staff staff : staffs) {
            ArrayList<LyricSymbol> lyrics = tracklyrics.get(staff.getTrack());
            staff.AddLyrics(lyrics);
        }
    }



    /** Create a bitmap/canvas to use for double-buffered drawing.
     *  This is needed for shading the notes quickly.
     *  Instead of redrawing the entire sheet music on every shade call,
     *  we draw the sheet music to this bitmap canvas.  On subsequent
     *  calls to ShadeNotes(), we only need to draw the delta (the
     *  new notes to shade/unshade) onto the bitmap, and then draw the bitmap.
     *
     *  We include the MidiPlayer height (since we hide the MidiPlayer
     *  once the music starts playing). Also, we make the bitmap twice as 
     *  large as the scroll viewable area, so that we don't need to
     *  refresh the bufferCanvas on every scroll change.
     */
    void createBufferCanvas() {
        if (bufferBitmap != null) {
            bufferCanvas = null;
            bufferBitmap.recycle();
            bufferBitmap = null;
        }
        if (scrollVert) {
            bufferBitmap = Bitmap.createBitmap(viewwidth, 
                                               (viewheight + playerHeight) * 2, 
                                               Config.ARGB_8888);
        }
        else {
            bufferBitmap = Bitmap.createBitmap(viewwidth * 2, 
                                               (viewheight + playerHeight) * 2, 
                                               Config.ARGB_8888);
        }

        bufferCanvas = new Canvas(bufferBitmap);
        drawToBuffer(scrollX, scrollY);
    }


    /** Obtain the drawing canvas and call onDraw() */
    @SuppressLint("WrongCall")
	public void callOnDraw() {
        if (!surfaceReady) {
            return;
        }
        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        onDraw(canvas);
        holder.unlockCanvasAndPost(canvas);
    }

    /** Draw the SheetMusic. */
    @Override
    protected void onDraw(Canvas canvas) {
        if (bufferBitmap == null) {
            createBufferCanvas();
        }
        if (!isScrollPositionInBuffer()) {
            drawToBuffer(scrollX, scrollY);
        }

        // We want (scrollX - bufferX, scrollY - bufferY) 
        // to be (0,0) on the canvas 
        canvas.translate(-(scrollX - bufferX), -(scrollY - bufferY));
        canvas.drawBitmap(bufferBitmap, 0, 0, paint);
        canvas.translate(scrollX - bufferX, scrollY - bufferY);
    }
    
    /** Return true if the scrollX/scrollY is in the bufferBitmap */
    private boolean isScrollPositionInBuffer() {
        if ((scrollY < bufferY) ||
            (scrollX < bufferX) ||
            (scrollY > bufferY + bufferBitmap.getHeight()/3) ||
            (scrollX > bufferX + bufferBitmap.getWidth()/3) ) {

            return false;
        }
        else {
            return true;
        }
    }
    
    /** Draw the SheetMusic to the bufferCanvas, with the
     * given (left,top) corner.
     *  
     * Scale the graphics by the current zoom factor.
     * Only draw Staffs which lie inside the buffer area.
     */
    private void drawToBuffer(int left, int top) {
        if (staffs == null) {
            return;
        }

        bufferX =left;
        bufferY = top;
        
        bufferCanvas.translate(-bufferX, -bufferY);
        Rect clip = new Rect(bufferX, bufferY,
                             bufferX + bufferBitmap.getWidth(), 
                             bufferY + bufferBitmap.getHeight());

        // Scale both the canvas and the clip by the zoom factor
        clip.left   = (int)(clip.left   / zoom);
        clip.top    = (int)(clip.top    / zoom);
        clip.right  = (int)(clip.right  / zoom);
        clip.bottom = (int)(clip.bottom / zoom);
        bufferCanvas.scale(zoom, zoom);

        // Draw a white background
        paint.setAntiAlias(true);
        paint.setStyle(Style.FILL);
        paint.setColor(Color.WHITE);
        bufferCanvas.drawRect(clip.left, clip.top, clip.right, clip.bottom, paint);
        paint.setStyle(Style.STROKE);
        paint.setColor(Color.BLACK);

        // Draw the staffs in the clip area
        int ypos = 0;
        for (Staff staff : staffs) {
            if ((ypos + staff.getHeight() < clip.top) || (ypos > clip.bottom))  {
                /* Staff is not in the clip, don't need to draw it */
            }
            else {
                bufferCanvas.translate(0, ypos);
                staff.Draw(bufferCanvas, clip, paint);
                bufferCanvas.translate(0, -ypos);
            }

            ypos += staff.getHeight();
        }
        bufferCanvas.scale(1.0f/zoom, 1.0f/zoom);
        bufferCanvas.translate(bufferX, bufferY);
    }


    /** Write the MIDI filename at the top of the page */
    private void DrawTitle(Canvas canvas) {
        int leftmargin = 20;
        int topmargin = 20;
        String title = filename;
        title = title.replace(".mid", "").replace("_", " ");
        canvas.translate(leftmargin, topmargin);
        canvas.drawText(title, 0, 0, paint);
        canvas.translate(-leftmargin, -topmargin);
    }

    /**
     * Return the number of pages needed to print this sheet music.
     * A staff should fit within a single page, not be split across two pages.
     * If the sheet music has exactly 2 tracks, then two staffs should
     * fit within a single page, and not be split across two pages.
     */
    public int GetTotalPages() {
        int num = 1;
        int currheight = TitleHeight;

        if (numtracks == 2 && (staffs.size() % 2) == 0) {
            for (int i = 0; i < staffs.size(); i += 2) {
                int heights = staffs.get(i).getHeight() + staffs.get(i+1).getHeight();
                if (currheight + heights > PageHeight) {
                    num++;
                    currheight = heights;
                }
                else {
                    currheight += heights;
                }
            }
        }
        else {
            for (Staff staff : staffs) {
                if (currheight + staff.getHeight() > PageHeight) {
                    num++;
                    currheight = staff.getHeight();
                }
                else {
                    currheight += staff.getHeight();
                }
            }
        }
        return num;
    }

    /** Draw the given page of the sheet music.
     * Page numbers start from 1.
     * A staff should fit within a single page, not be split across two pages.
     * If the sheet music has exactly 2 tracks, then two staffs should
     * fit within a single page, and not be split across two pages.
     */
    public void DrawPage(Canvas canvas, int pagenumber)
    {
        int leftmargin = 20;
        int topmargin = 20;
        //int rightmargin = 20;
        //int bottommargin = 20;

        //float scale = 1.0f;
        Rect clip = new Rect(0, 0, PageWidth + 40, PageHeight + 40);

        paint.setAntiAlias(true);
        paint.setStyle(Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawRect(clip.left, clip.top, clip.right, clip.bottom, paint);
        paint.setStyle(Style.STROKE);
        paint.setColor(Color.BLACK);

        int ypos = TitleHeight;
        int pagenum = 1;
        int staffnum = 0;

        if (numtracks == 2 && (staffs.size() % 2) == 0) {
            /* Skip the staffs until we reach the given page number */
            while (staffnum + 1 < staffs.size() && pagenum < pagenumber) {
                int heights = staffs.get(staffnum).getHeight() +
                              staffs.get(staffnum+1).getHeight();
                if (ypos + heights >= PageHeight) {
                    pagenum++;
                    ypos = 0;
                }
                else {
                    ypos += heights;
                    staffnum += 2;
                }
            }
            /* Print the staffs until the height reaches PageHeight */
            if (pagenum == 1) {
                DrawTitle(canvas);
                ypos = TitleHeight;
            }
            else {
                ypos = 0;
            }
            for (; staffnum + 1 < staffs.size(); staffnum += 2) {
                int heights = staffs.get(staffnum).getHeight() +
                              staffs.get(staffnum+1).getHeight();

                if (ypos + heights >= PageHeight)
                    break;

                canvas.translate(leftmargin, topmargin + ypos);
                staffs.get(staffnum).Draw(canvas, clip, paint);
                canvas.translate(-leftmargin, -(topmargin + ypos));
                ypos += staffs.get(staffnum).getHeight();
                canvas.translate(leftmargin, topmargin + ypos);
                staffs.get(staffnum + 1).Draw(canvas, clip, paint);
                canvas.translate(-leftmargin, -(topmargin + ypos));
                ypos += staffs.get(staffnum + 1).getHeight();
            }
        }

        else {
            /* Skip the staffs until we reach the given page number */
            while (staffnum < staffs.size() && pagenum < pagenumber) {
                if (ypos + staffs.get(staffnum).getHeight() >= PageHeight) {
                    pagenum++;
                    ypos = 0;
                }
                else {
                    ypos += staffs.get(staffnum).getHeight();
                    staffnum++;
                }
            }

            /* Print the staffs until the height reaches viewPageHeight */
            if (pagenum == 1) {
                DrawTitle(canvas);
                ypos = TitleHeight;
            }
            else {
                ypos = 0;
            }
            for (; staffnum < staffs.size(); staffnum++) {
                if (ypos + staffs.get(staffnum).getHeight() >= PageHeight)
                    break;

                canvas.translate(leftmargin, topmargin + ypos);
                staffs.get(staffnum).Draw(canvas, clip, paint);
                canvas.translate(-leftmargin, -(topmargin + ypos));
                ypos += staffs.get(staffnum).getHeight();
            }
        }

        /* Draw the page number */
        canvas.drawText("" + pagenumber,
                        PageWidth-leftmargin,
                        topmargin + PageHeight - 12,
                        paint);

    }


    public void DrawMeasure(Canvas canvas, int measureIdx, int width_px) {
    	Vec2 xlim = new Vec2();
        int leftmargin = 20;
        int topmargin = 20;
    	int ypos = TitleHeight;
    	int ymin = TitleHeight;
    	int ymax = 0;

    	int curr_line_x = 0;
    	boolean is_clef = true;
    	for(Staff f : staffs) {
    		canvas.translate(leftmargin, topmargin+ypos);
    		xlim = f.DrawMeasure(canvas, measureIdx, paint, is_clef, 0.0f);
    		curr_line_x = curr_line_x + xlim.y - xlim.x;
    		if(curr_line_x > width_px) {
    			curr_line_x = 0;
    			is_clef = true;
    		} else {
    			is_clef = false;
    		}
    		canvas.translate(-leftmargin, -(topmargin+ypos));
    		ypos = ypos + f.getHeight();
    	}
    	ymax = ypos;
    	paint.setColor(Color.RED);
    	paint.setStyle(Style.STROKE);
    	canvas.drawRect(xlim.x, ymin, xlim.y, ymax, paint);
    }
    
    // The bitmap being drawn in the background is cached
    //   scaling to zoom_x and zoom_y
    public Bitmap RenderTile(int measure_idx, int staff_idx, float zoom_x, float zoom_y) {
    	synchronized(scratch) {
    		try{
    			if(scratch.isRecycled()) {
    				Thread.sleep(100);
    			}
    		} catch (Exception x) {}
	    	Staff staff = staffs.get(staff_idx);
	    	int H = staff.getHeight();
	    	Canvas c = new Canvas(scratch);
	    	c.drawColor(TommyConfig.BACKGROUND_COLOR);
	    	boolean is_clef = (measure_idx == 0) ? true : false;
	    	
	    	float pad = 0.0f;
	    	if(measurePads.size() > 0) pad = measurePads.get(measure_idx);
	    	float overflow_zoomx = 1.0f;
	    	if(measureOverflowZooms.size() > 0) overflow_zoomx = measureOverflowZooms.get(measure_idx);
	    	
	    	Vec2 xlim = staff.DrawMeasure(c, measure_idx, paint, is_clef, pad);
	    	int W = xlim.y - xlim.x;
	    	
	    	if(zoom_x > 1) zoom_x = 1;
	    	if(zoom_y > 1) zoom_y = 1;
	    	int W_alloc = (int)(W*zoom_x*overflow_zoomx);
	    	int H_alloc = (int)(H*zoom_y);
	    	
	    	Bitmap ret = Bitmap.createBitmap((int)W_alloc, (int)H_alloc, Config.RGB_565);
	    	Canvas c1 = new Canvas(ret);
	    	Rect src = new Rect(0, 0, W, H), dst = new Rect(0, 0, W_alloc, H_alloc);
	    	paint.setFilterBitmap(true);
	    	c1.drawBitmap(scratch, src, dst, paint);
	    	c1 = null;
	    	c = null;
	    	return ret;
    	}
    }
    
    

    public void ComputeMeasureHashesNoLineBreakNoRender() {
    	resumeBackupAllSymbols();
    	int num_measures = staffs.get(0).getNumMeasures();
    	if(num_measures == 0) {
    		Log.v("SheetMusic", "Rendering all measures, but we have 0 measures.");
    	}
    	num_notes = 0;
    	measureHashes.clear();
    	measurePads.clear();
    	measureOverflowZooms.clear();
    	measureWidths.clear();
    	measureHeights.clear();
    	scratch_height = scratch_width = 0;
    	for(Staff f : staffs) {
    		ArrayList<Integer> staffHashes = new ArrayList<Integer>();
    		for(int i=0; i<num_measures; i++) {
    			int ypos = 5;
	    		paint.setColor(TommyConfig.BACKGROUND_COLOR);
	    		paint.setStyle(Style.FILL);
    			// Compute Hash
				{
		    		int syms_hash = 0x00000000;
	    			ArrayList<Integer> syms_f_idxs = f.getNotesInMeasure(i);
	    			int idx = 1;
	    			String longhash = "";
	    			for(Integer idx1 : syms_f_idxs) {
	    				MusicSymbol s = f.getSymbols().get(idx1);
	    				syms_hash ^= s.getMyHash();
	    				longhash = longhash + "," + s.getMyHash();
	    				syms_hash = syms_hash * idx;
	    				idx ++;
	    				num_notes ++;
	    			}
	    			ypos = ypos + f.getHeight();
		    		staffHashes.add(syms_hash);
				}
				// Create bitmap for measure #i
				Vec2 xlim;
				if(i == 0) xlim = f.DrawMeasure(null, i, paint, true, 0.0f);
				else xlim = f.DrawMeasure(null, i, paint, false, 0.0f);
				
				if(f==staffs.get(0)) {
					int w = xlim.y - xlim.x;
					if(scratch_width < w) scratch_width = w;
					measureWidths.add(w);
				}
    		}
    		measureHashes.add(staffHashes);
    		if(scratch_height < f.getHeight()) scratch_height = f.getHeight();
    		measureHeights.add(f.getHeight());
    	}
    	CreateAllBeamedChords(this.allsymbols, this.time_signature);
    	if(scratch == null) 
    		scratch = Bitmap.createBitmap(scratch_width, scratch_height, Config.RGB_565);
    }
    
    public void ComputeMeasureHashesNoRender(int line_width) {
    	resumeBackupAllSymbols();
    	int num_measures = 0;
    	if(staffs.size() > 0) num_measures = staffs.get(0).getNumMeasures();
    	if(num_measures == 0) {
//    		Log.v("SheetMusic", "Rendering all measures, but we have 0 measures.");
    	}
    	
    	num_notes = 0;
    	Vec2 xlim = new Vec2();
    	measureHashes.clear();
    	measurePads.clear();
    	measureOverflowZooms.clear();
    	measureWidths.clear();
    	measureHeights.clear();
    	scratch_height = scratch_width = 0;
    	for(Staff f : staffs) {
    		ArrayList<Integer> staffHashes = new ArrayList<Integer>();
    		int x = 0;
    		int line_begin_idx = 0,    
    				line_end_idx = -1; // Measure index of each line is [line_begin_idx, line_end_idx).
    		boolean line_need_shrinkzoom = false; // When there is only 1 measure and it's longer than LineWidth, shrink-zoom it
    		float line_shrink_zoom = 1.0f;
	    	for(int i=0; i<num_measures; i++) {
	    		if(i==num_measures - 1) { line_end_idx = num_measures; }
	    		int pad_right = 0;
	    		
	    		if(i==0) { xlim = f.DrawMeasure(null, i, paint, true, 0.0f); }
	    		else { xlim = f.DrawMeasure(null, i, paint, false, 0.0f); }
    			
//    			Log.v("SheetMusic", String.format("Measure %d, width=%d/%d", i, xlim.y - xlim.x, line_width));
    			
    			// 把右边补齐
    			pad_right = 0;
    			if(i>0) {
	    			if(x+xlim.y - xlim.x > line_width) {
	    				if(x!=0) {
		    				pad_right = line_width - x;
//		    				Log.v("SheetMusic", String.format("pad_right=%d, i=%d", pad_right, i));
		    				x = 0;
	    					line_end_idx = i;
	    					i--; // Re-layout this measure
	    				} else {
 	    					line_end_idx = i+1;
 	    					line_need_shrinkzoom = true;
 	    					line_shrink_zoom = 1.0f * line_width / (xlim.y-xlim.x); 
	    				}
	    			} else { 
	    				x = x + (xlim.y - xlim.x); 
	    			}
    			} else {
    				if(is_first_measure_out_of_boundingbox == false) {
    					x = x + (xlim.y - xlim.x);
    				}
    			}
    			
    			if(line_end_idx != -1) {
    				int currx = 0;
    				int measures_in_line = line_end_idx - line_begin_idx;
    				if(is_first_measure_out_of_boundingbox == true) {
    					if(line_begin_idx == 0) { measures_in_line = measures_in_line - 1; }
    				}
    				float each_pad = (pad_right * 1.0f / (1.0f * measures_in_line));
	    			for(int j=line_begin_idx; j<line_end_idx; j++) {
	    				int ypos = 5, ymax = 0;
	    				
		    			// Compute Hash
	    				{
	    		    		int syms_hash = 0x00000000;
			    			ArrayList<Integer> syms_f_idxs = f.getNotesInMeasure(j);
			    			int idx = 1;
			    			String longhash = "";
			    			for(Integer idx1 : syms_f_idxs) {
			    				MusicSymbol s = f.getSymbols().get(idx1);
			    				syms_hash ^= s.getMyHash();
			    				longhash = longhash + "," + s.getMyHash();
			    				syms_hash = syms_hash * idx;
			    				idx ++;
			    				num_notes ++;
			    			}
			    			ypos = ypos + f.getHeight();
				    		ymax = ypos;
				    		staffHashes.add(syms_hash);
	    				}

	    				float this_pad = Float.NaN;
	    				{
				    		if(j == line_end_idx - 1 && j>0) {
				    			xlim = f.DrawMeasure(null, j, paint, false, 0.0f);
				    			this_pad = line_width - (xlim.y - xlim.x) - currx; 
				    		} else if(j==0) {
				    			if(is_first_measure_out_of_boundingbox) {
				    				this_pad = 0;
				    			} else this_pad = each_pad;
				    		} else {
				    			this_pad = each_pad;
				    		}
				    		
				    		if(f==staffs.get(0)) {
				    			measurePads.add(0.f);
				    			measureOverflowZooms.add(line_shrink_zoom);
				    		}
				    		
				    		f.PadMeasure(j, this_pad);
				    		
				    		if(j==0) { xlim = f.DrawMeasure(null, j, paint, true, 0); }
				    		else { xlim = f.DrawMeasure(null, j, paint, false, 0); }
			    			if(j > 0 || is_first_measure_out_of_boundingbox==false)
			    				currx = currx + xlim.y - xlim.x;
	    				}
			    		
			    		// Create bitmap for measure #i
			    		int W = xlim.y - xlim.x;
			    		int H = ymax;
			    		
			    		if(scratch_width < W) scratch_width = W;
			    		if(f == staffs.get(0)) {
			    			measureWidths.add(W);
			    		}
			    		
			    		
	    			}
	    			line_begin_idx = line_end_idx;
    			}
	    	}
	    	measureHashes.add(staffHashes);
	    	measureHeights.add(f.getHeight());
	    	if(scratch_height < f.getHeight()) scratch_height = f.getHeight();
    	}

    	Log.v("SheetMusic new", "Creating all beamed chords" + time_signature);
    	CreateAllBeamedChords(this.allsymbols, this.time_signature);
    	
    	// When the user wants to resize
    	if(scratch != null && scratch.isRecycled()==false) {
    		scratch.recycle(); scratch = null;
    		scratch = Bitmap.createBitmap(line_width, scratch_height, Config.RGB_565);
    	}
    	if(scratch == null && scratch_width > 0 && scratch_height > 0) 
    		scratch = Bitmap.createBitmap(line_width, scratch_height, Config.RGB_565);
    }
    
    
    public ArrayList<ArrayList<Bitmap>> RenderAllMeasuresNoLineBreak() {
    	Canvas c = new Canvas(scratch);
    	int num_measures = staffs.get(0).getNumMeasures();
    	if(num_measures == 0) {
    		Log.v("SheetMusic", "Rendering all measures, but we have 0 measures.");
    		return null;
    	}
    	num_notes = 0;
    	int num_bytes = 0;
    	
    	Vec2 xlim = new Vec2();
    	for(Staff f : staffs) {
    		ArrayList<Bitmap> staffBmps = new ArrayList<Bitmap>();
    		ArrayList<Integer> staffHashes = new ArrayList<Integer>();
    		int x = 0;
    		for(int i=0; i<num_measures; i++) {
    			int ypos = 5, ymax = 0;
	    		paint.setColor(TommyConfig.BACKGROUND_COLOR);
	    		paint.setStyle(Style.FILL);
	    		c.drawRect(0, 0, c.getWidth(), c.getHeight(), paint);
    			if(i==0) { xlim = f.DrawMeasure(c, i, paint, true, 0.0f); }
    			else { xlim = f.DrawMeasure(c, i, paint, false, 0.0f); }
    			// Compute Hash
				{
		    		int syms_hash = 0x00000000;
	    			ArrayList<Integer> syms_f_idxs = f.getNotesInMeasure(i);
	    			int idx = 1;
	    			String longhash = "";
	    			for(Integer idx1 : syms_f_idxs) {
	    				MusicSymbol s = f.getSymbols().get(idx1);
//	    			for(MusicSymbol s : syms_f) {
	    				syms_hash ^= s.getMyHash();
	    				longhash = longhash + "," + s.getMyHash();
	    				syms_hash = syms_hash * idx;
	    				idx ++;
	    				num_notes ++;
	    			}
	    			ypos = ypos + f.getHeight();
		    		ymax = ypos;
		    		staffHashes.add(syms_hash);
				}
				
				// Create bitmap for measure #i
	    		int W = xlim.y - xlim.x;
	    		int H = ymax;
	    		Bitmap measurebmp = Bitmap.createBitmap(W, H, Config.RGB_565);
	    		Canvas c1 = new Canvas(measurebmp);
	    		paint.setAntiAlias(true);
	    		c1.drawBitmap(scratch, 0, 0, paint);
	    		num_bytes += W*H*2;
	    		
	    		staffBmps.add(measurebmp);
    		}
    		measureBMPs.add(staffBmps);
    		measureHashes.add(staffHashes);
    	}
    	scratch.recycle();
    	scratch = null;
    	Log.v("SheetMusic", String.format("Memory consumed: %d KB", num_bytes/1024));
    	return measureBMPs;
    }
    
    public void free() {
    	for(ArrayList<Bitmap> ab : measureBMPs) {
    		for(Bitmap b : ab) {
    			b.recycle();
    			b = null;
    		}
    		ab.clear();
    	}
    	measureBMPs.clear();
    	for(ArrayList<Integer> ai : measureHashes) {
    		ai.clear();
    	}
    	for(Staff s : staffs) {
    		s.free();
    	}
    	staffs.clear();
    	measureHashes.clear();
    	measureHeights.clear();
    	measureWidths.clear();
    	if(scratch != null)
    		scratch.recycle();
    	scratch = null;
    	System.gc();
    	System.gc();
    }
    
    public void tommyFree() {
    	if(bufferBitmap != null) bufferBitmap.recycle();
    	if(player != null) {
    		player.destroyDrawingCache();
    		player = null;
    	}
    }
    
    public ArrayList<ArrayList<Bitmap>> RenderAllMeasures(int line_width) {
    	Bitmap scratch = Bitmap.createBitmap(1280, 320, Config.ARGB_8888);
    	Canvas c = new Canvas(scratch);
    	int num_measures = staffs.get(0).getNumMeasures();
    	if(num_measures == 0) {
    		Log.v("SheetMusic", "Rendering all measures, but we have 0 measures.");
    		return null;
    	}
    	
    	num_notes = 0;
    	Vec2 xlim = new Vec2();
    	for(Staff f : staffs) {
    		ArrayList<Bitmap> staffBmps = new ArrayList<Bitmap>();
    		ArrayList<Integer> staffHashes = new ArrayList<Integer>();
    		int x = 0;
    		int line_begin_idx = 0,    
    				line_end_idx = -1; // Measure index of each line is [line_begin_idx, line_end_idx). 
	    	for(int i=0; i<num_measures; i++) {
	    		if(i==num_measures - 1) { line_end_idx = num_measures; }
	    		int pad_right = 0;
	    		
	    		if(i==0) { xlim = f.DrawMeasure(null, i, paint, true, 0.0f); }
	    		else { xlim = f.DrawMeasure(null, i, paint, false, 0.0f); }
    			
//    			Log.v("SheetMusic", String.format("Measure %d, width=%d", i, xlim.y - xlim.x));
    			
    			// 把右边补齐
    			pad_right = 0;
    			if(i>0) {
	    			if(x+xlim.y - xlim.x > line_width) {
	    				pad_right = line_width - x;
//	    				Log.v("SheetMusic", String.format("pad_right=%d, i=%d", pad_right, i));
	    				x = 0;
	    				line_end_idx = i;
	    				i--; // Re-layout this measure
	    			} else { 
	    				x = x + (xlim.y - xlim.x); 
	    			}
    			}
    			
    			if(line_end_idx != -1) {
    				int currx = 0;
    				int measures_in_line = line_end_idx - line_begin_idx;
    				if(line_begin_idx == 0) { measures_in_line = measures_in_line - 1; }
    				float each_pad = (pad_right * 1.0f / (1.0f * measures_in_line));
	    			for(int j=line_begin_idx; j<line_end_idx; j++) {
	    				int ypos = 5, ymax = 0;
	    				
		    			// Compute Hash
	    				{
	    		    		int syms_hash = 0x00000000;
			    			ArrayList<Integer> syms_f_idxs = f.getNotesInMeasure(j);
			    			int idx = 1;
			    			String longhash = "";
			    			for(Integer idx1 : syms_f_idxs) {
			    				MusicSymbol s = f.getSymbols().get(idx1);
//			    			for(MusicSymbol s : syms_f) {
			    				syms_hash ^= s.getMyHash();
			    				longhash = longhash + "," + s.getMyHash();
			    				syms_hash = syms_hash * idx;
			    				idx ++;
			    				num_notes ++;
			    			}
			    			ypos = ypos + f.getHeight();
				    		ymax = ypos;
				    		staffHashes.add(syms_hash);
	    				}

	    				float this_pad = Float.NaN;
	    				{
				    		// Render this measure, set is_clef to zero
				    		paint.setColor(TommyConfig.BACKGROUND_COLOR);
				    		paint.setStyle(Style.FILL);
				    		c.drawRect(0, 0, c.getWidth(), c.getHeight(), paint);
				    		if(j == line_end_idx - 1 && j>0) {
				    			xlim = f.DrawMeasure(null, j, paint, false, 0.0f);
				    			this_pad = line_width - (xlim.y - xlim.x) - currx; 
				    		} else if(j==0) {
				    			this_pad = 0;
				    		} else {
				    			this_pad = each_pad;
				    		}
				    		if(j==0) { xlim = f.DrawMeasure(c, j, paint, true, this_pad); }
				    		else { xlim = f.DrawMeasure(c, j, paint, false, this_pad); }
			    			if(j > 0)
			    				currx = currx + xlim.y - xlim.x;
	    				}
			    		
			    		// Create bitmap for measure #i
			    		int W = xlim.y - xlim.x;
			    		int H = ymax;
			    		Bitmap measurebmp = Bitmap.createBitmap(W, H, Config.RGB_565);
			    		Canvas c1 = new Canvas(measurebmp);
			    		paint.setAntiAlias(true);
			    		c1.drawBitmap(scratch, 0, 0, paint);

			    		if(MidiSheetMusicActivity.DEBUG)
			    		{ // Draws debug information on tile
				    		c1.drawText(String.format("%dx%d", W, H), 0, 14, paint);
				    		c1.drawText(String.format("pad %f", this_pad), 0, 28, paint);
				    		if(j == line_end_idx - 1) {
				    			c1.drawText(String.format("End of line"), 0, 42, paint);
				    		}
			    		}
			    		
			    		staffBmps.add(measurebmp);
			    		Log.v("SheetMusic", String.format("BMP for measure %d/%d/%d", j, line_end_idx, num_measures));
	    			}
	    			line_begin_idx = line_end_idx;
    			}
	    	}
	    	measureBMPs.add(staffBmps);
	    	measureHashes.add(staffHashes);
    	}
    	scratch.recycle();
    	return measureBMPs;
    }

    /** Shade all the chords played at the given pulse time.
     *  First, make sure the current scroll position is in the bufferBitmap.
     *  Loop through all the staffs and call staff.Shade().
     *  If scrollGradually is true, scroll gradually (smooth scrolling)
     *  to the shaded notes.
     */
    public void ShadeNotes(int currentPulseTime, int prevPulseTime, int scrollType) {
    	if(staffs!=null) {
    		Staff f = staffs.get(0);
	        { 
	        	curr_playing_measure_idx = f.getMeasureIdxFromPulse(currentPulseTime);
	        }

	        
	        float x_shade1 = 1e20f, x_shade1_end = 1e20f; // Shade X in the current measure.
	        int shade_measure_idx_min = 2147483647;

	        for(Staff staff : staffs) {
	            staff.updateShadedNoteX(curr_playing_measure_idx, currentPulseTime, prevPulseTime); // TOMMY
	        	if(staff.shade_measure_idx < shade_measure_idx_min) {
	        		shade_measure_idx_min = staff.shade_measure_idx;
        			x_shade1 = staff.shade_measure_x_begin;
        			x_shade1_end = staff.shade_measure_x_end;
	        	} else if(staff.shade_measure_idx == shade_measure_idx_min) {
	        		if(staff.shade_measure_x_begin < x_shade1) {
	        			x_shade1 = staff.shade_measure_x_begin;
	        			x_shade1_end = staff.shade_measure_x_end;
	        		}
	        	}
	        }

	        curr_playing_measure_idx = shade_measure_idx_min;
	        curr_playing_measure_shade_x_begin = x_shade1;
	        curr_playing_measure_shade_x_end = x_shade1_end;
    	}
        
        if (!surfaceReady || staffs == null) {
            return;
        }
        if (bufferCanvas == null) {
            createBufferCanvas();
        }

        /* If the scroll position is not in the bufferCanvas,
         * we need to redraw the sheet music into the bufferCanvas
         */
        if (!isScrollPositionInBuffer()) {
            drawToBuffer(scrollX, scrollY);
        }

        /* We're going to draw the shaded notes into the bufferCanvas.
         * Translate, so that (bufferX, bufferY) maps to (0,0) on the canvas
         */
        bufferCanvas.translate(-bufferX, -bufferY);

        /* Loop through each staff.  Each staff will shade any notes that 
         * start at currentPulseTime, and unshade notes at prevPulseTime.
         */
        int x_shade = 0;
        int y_shade = 0;
        paint.setAntiAlias(true);
        bufferCanvas.scale(zoom, zoom);
        int ypos = 0;
        
        
        for (Staff staff : staffs) {
            bufferCanvas.translate(0, ypos);
            x_shade = staff.ShadeNotes(bufferCanvas, paint, shade1, 
                            currentPulseTime, prevPulseTime, x_shade);
            bufferCanvas.translate(0, -ypos);
            ypos += staff.getHeight();
            if (currentPulseTime >= staff.getEndTime()) {
                y_shade += staff.getHeight();
            }

        }
        bufferCanvas.scale(1.0f/zoom, 1.0f/zoom);
        bufferCanvas.translate(bufferX, bufferY);
        

        /* We have the (x,y) position of the shaded notes.
         * Calculate the new scroll position.
         */
        if (currentPulseTime >= 0) {
            x_shade = (int)(x_shade * zoom);
            y_shade -= NoteHeight;
            y_shade = (int)(y_shade * zoom);
            if (scrollType == ImmediateScroll) {
                ScrollToShadedNotes(x_shade, y_shade, false);
            }
            else if (scrollType == GradualScroll) {
                ScrollToShadedNotes(x_shade, y_shade, true);
            }
            else if (scrollType == DontScroll) {
            }
        }

        /* If the new scrollX, scrollY is not in the buffer,
         * we have to call this method again.
         */
        if (scrollX < bufferX || scrollY < bufferY) {
            ShadeNotes(currentPulseTime, prevPulseTime, scrollType);
            return;
        }

        /* Draw the buffer canvas to the real canvas.        
         * Translate canvas such that (scrollX,scrollY) within the 
         * bufferCanvas maps to (0,0) on the real canvas.
         */
        SurfaceHolder holder = getHolder();
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        canvas.translate(-(scrollX - bufferX), -(scrollY - bufferY));
        canvas.drawBitmap(bufferBitmap, 0, 0, paint);
        canvas.translate(scrollX - bufferX, scrollY - bufferY);
        holder.unlockCanvasAndPost(canvas);
    }

    /** Scroll the sheet music so that the shaded notes are visible.
      * If scrollGradually is true, scroll gradually (smooth scrolling)
      * to the shaded notes. Update the scrollX/scrollY fields.
      */
    void ScrollToShadedNotes(int x_shade, int y_shade, boolean scrollGradually) {
        if (scrollVert) {
            int scrollDist = (int)(y_shade - scrollY);

            if (scrollGradually) {
                if (scrollDist > (zoom * StaffHeight * 8))
                    scrollDist = scrollDist/2;
                else if (scrollDist > (NoteHeight * 4 * zoom))
                    scrollDist = (int)(NoteHeight * 4 * zoom);
            }
            scrollY += scrollDist;
        }
        else {

            int x_view = scrollX + viewwidth * 40/100;
            int xmax   = scrollX + viewwidth * 65/100;
            int scrollDist = x_shade - x_view;

            if (scrollGradually) {
                if (x_shade > xmax) 
                    scrollDist = (x_shade - x_view)/3;
                else if (x_shade > x_view)
                    scrollDist = (x_shade - x_view)/6;
            }

            scrollX += scrollDist;
        }
        checkScrollBounds();
    }

    /** Return the pulseTime corresponding to the given point on the SheetMusic.
     *  First, find the staff corresponding to the point.
     *  Then, within the staff, find the notes/symbols corresponding to the point,
     *  and return the StartTime (pulseTime) of the symbols.
     */
    public int PulseTimeForPoint(Point point) {
        Point scaledPoint = new Point((int)(point.x / zoom), (int)(point.y / zoom));
        int y = 0;
        for (Staff staff : staffs) {
            if (scaledPoint.y >= y && scaledPoint.y <= y + staff.getHeight()) {
                return staff.PulseTimeForPoint(scaledPoint);
            }
            y += staff.getHeight();
        }
        return -1;
    }


    /** Check that the scrollX/scrollY position does not exceed
     *  the bounds of the sheet music.
     */
    private void
    checkScrollBounds() {
        // Get the width/height of the scrollable area
        int scrollwidth = (int)(sheetwidth * zoom);
        int scrollheight = (int)(sheetheight * zoom);
        
        if (scrollX < 0) {
            scrollX = 0;
        }
        if (scrollX > scrollwidth - viewwidth/2) {
            scrollX = scrollwidth - viewwidth/2;
        }

        if (scrollY < 0) {
            scrollY = 0;
        }
        if (scrollY > scrollheight - viewheight/2) {
            scrollY = scrollheight - viewheight/2;
        }
    }


    /** Handle touch/motion events to implement scrolling the sheet music. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        boolean result = scrollAnimation.onTouchEvent(event);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                // If we touch while music is playing, stop the midi player 
                if (player != null && player.getVisibility() == View.GONE) {
                    player.Pause();
                    scrollAnimation.stopMotion();
                }
                return result;

            case MotionEvent.ACTION_MOVE:
                return result;

            case MotionEvent.ACTION_UP:
                return result;

            default:
                return false;
        }
    }


    /** Update the scroll position. Callback by ScrollAnimation */
    public void scrollUpdate(int deltaX, int deltaY) {
        scrollX += deltaX;
        scrollY += deltaY;
        checkScrollBounds();
        callOnDraw();
    }

    /** When the scroll is tapped, highlight the position tapped */
    public void scrollTapped(int x, int y) {
        if (player != null) {
            player.MoveToClicked(scrollX + x, scrollY + y);
        }
    }

    public void setPlayer(MidiPlayer p) {
        player = p;
    }
    
    public void
    surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        callOnDraw();
    }

    /** Surface is ready for shading the notes */
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    /** Surface has been destroyed */
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    @Override
    public String toString() {
        String result = "SheetMusic staffs=" + staffs.size() + "\n";
        for (Staff staff : staffs) {
            result += staff.toString();
        }
        result += "End SheetMusic\n";
        return result;
    }

}

