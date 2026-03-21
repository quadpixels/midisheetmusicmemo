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

import java.util.ArrayList;
import java.util.Locale;

import androidx.documentfile.provider.DocumentFile;

import com.quadpixels.midisheetmusicmemo1.R;

import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;


public class FileBrowserActivity extends ListActivity {
    private static final int REQUEST_OPEN_DOCUMENT_TREE = 1001;
    private static final String PREF_TREE_URI = "safTreeUri";

    private ArrayList<DocumentFile> browseTargets; /* Backing DocumentFile list */
    private TextView directoryView;      /* TextView showing directory name */

    private Uri rootTreeUri;


    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.file_browser);
        setTitle("MidiSheetMusic: Browse Files");

        directoryView = findViewById(R.id.directory);
        directoryView.setOnClickListener(v -> requestRootFolder());
    }

    @Override
    public void onResume() {
        super.onResume();
        initializeTreeAccess();
    }

    private void initializeTreeAccess() {
        SharedPreferences settings = getPreferences(0);
        String treeUriString = settings.getString(PREF_TREE_URI, null);
        if (treeUriString == null) {
            requestRootFolder();
            return;
        }

        try {
            rootTreeUri = Uri.parse(treeUriString);
            DocumentFile treeRoot = DocumentFile.fromTreeUri(this, rootTreeUri);
            if (treeRoot == null || !treeRoot.canRead()) {
                clearTreeSettingsAndRequest();
                return;
            }
            loadDirectory(treeRoot);
        }
        catch (Exception e) {
            clearTreeSettingsAndRequest();
        }
    }

    private void clearTreeSettingsAndRequest() {
        SharedPreferences.Editor editor = getPreferences(0).edit();
        editor.remove(PREF_TREE_URI);
        editor.apply();
        requestRootFolder();
    }

    private void requestRootFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OPEN_DOCUMENT_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_DOCUMENT_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "Folder access is required to browse local MIDI files", Toast.LENGTH_LONG).show();
            return;
        }

        Uri treeUri = data.getData();
        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            takeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
        }
        catch (SecurityException ignored) {
        }

        SharedPreferences.Editor editor = getPreferences(0).edit();
        editor.putString(PREF_TREE_URI, treeUri.toString());
        editor.apply();

        rootTreeUri = treeUri;
        DocumentFile treeRoot = DocumentFile.fromTreeUri(this, rootTreeUri);
        if (treeRoot != null) {
            loadDirectory(treeRoot);
        }
    }

    /* Scan the files in the new directory, and store them in the fileList.
     * Update the UI by refreshing the list adapter.
     */
    private void loadDirectory(DocumentFile directory) {
        if (directory == null || !directory.canRead() || !directory.isDirectory()) {
            Toast.makeText(this, "Unable to open selected directory", Toast.LENGTH_SHORT).show();
            return;
        }

        directoryView.setText(getDirectoryTitle(directory));

        /* List of files in the directory */
        ArrayList<FileUri> fileList = new ArrayList<>();
        browseTargets = new ArrayList<>();
        ArrayList<FileUri> sortedDirs = new ArrayList<>();
        ArrayList<FileUri> sortedFiles = new ArrayList<>();
        ArrayList<DocumentFile> sortedDirTargets = new ArrayList<>();
        ArrayList<DocumentFile> sortedFileTargets = new ArrayList<>();

        DocumentFile parent = directory.getParentFile();
        if (parent != null && !isRootDirectory(directory)) {
            sortedDirs.add(new FileUri(parent.getUri(), "../", true));
            sortedDirTargets.add(parent);
        }

        DocumentFile[] entries;
        try {
            entries = directory.listFiles();
        }
        catch (Exception e) {
            entries = new DocumentFile[0];
        }

        for (DocumentFile entry : entries) {
            if (entry == null || !entry.canRead()) {
                continue;
            }
            String name = entry.getName();
            if (name == null) {
                name = entry.getUri().getLastPathSegment();
            }
            if (entry.isDirectory()) {
                sortedDirs.add(new FileUri(entry.getUri(), name, true));
                sortedDirTargets.add(entry);
            }
            else {
                assert name != null;
                if (isMidiFilename(name)) {
                    sortedFiles.add(new FileUri(entry.getUri(), name, false));
                    sortedFileTargets.add(entry);
                }
            }
        }
        sortEntries(sortedDirs, sortedDirTargets);
        sortEntries(sortedFiles, sortedFileTargets);
        fileList.addAll(sortedDirs);
        fileList.addAll(sortedFiles);
        browseTargets.addAll(sortedDirTargets);
        browseTargets.addAll(sortedFileTargets);
        IconArrayAdapter<FileUri> adapter = new IconArrayAdapter<>(this, android.R.layout.simple_list_item_1, fileList);
        this.setListAdapter(adapter);
    }

    private void sortEntries(ArrayList<FileUri> entries, ArrayList<DocumentFile> targets) {
        for (int i = 0; i < entries.size() - 1; i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                if (entries.get(i).compare(entries.get(i), entries.get(j)) > 0) {
                    FileUri tempEntry = entries.get(i);
                    entries.set(i, entries.get(j));
                    entries.set(j, tempEntry);

                    DocumentFile tempTarget = targets.get(i);
                    targets.set(i, targets.get(j));
                    targets.set(j, tempTarget);
                }
            }
        }
    }

    private boolean isRootDirectory(DocumentFile directory) {
        return rootTreeUri != null && rootTreeUri.toString().equals(directory.getUri().toString());
    }

    private boolean isMidiFilename(String filename) {
        String lower = filename.toLowerCase(Locale.US);
        return lower.endsWith(".mid") || lower.endsWith(".midi");
    }

    private String getDirectoryTitle(DocumentFile directory) {
        String name = directory.getName();
        if (name == null || name.isEmpty()) {
            name = directory.getUri().toString();
        }
        return name + " (tap here to change folder)";
    }


    /** When a user selects an item:
     * - If it's a directory, load that directory.
     * - If it's a file, open the SheetMusicActivity.
     */
    @Override
    protected void onListItemClick(ListView parent, View view, int position, long id) {
        super.onListItemClick(parent, view, position, id);
        FileUri file = (FileUri) this.getListAdapter().getItem(position);
        DocumentFile target = browseTargets.get(position);
        if (file.isDirectory()) {
            if (target != null && target.canRead() && target.isDirectory()) {
                this.loadDirectory(target);
            }
        }
        else {
            ChooseSongActivity.openFile(file);
        }
    }
}
