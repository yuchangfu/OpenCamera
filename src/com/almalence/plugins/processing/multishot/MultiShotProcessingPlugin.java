/*
The contents of this file are subject to the Mozilla Public License
Version 1.1 (the "License"); you may not use this file except in
compliance with the License. You may obtain a copy of the License at
http://www.mozilla.org/MPL/

Software distributed under the License is distributed on an "AS IS"
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
License for the specific language governing rights and limitations
under the License.

The Original Code is collection of files collectively known as Open Camera.

The Initial Developer of the Original Code is Almalence Inc.
Portions created by Initial Developer are Copyright (C) 2013 
by Almalence Inc. All Rights Reserved.
*/

package com.almalence.plugins.processing.multishot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;

import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.location.Location;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.widget.RelativeLayout;

import com.almalence.SwapHeap;
import com.almalence.asynctaskmanager.OnTaskCompleteListener;
import com.almalence.opencam.MainScreen;
import com.almalence.opencam.PluginManager;
import com.almalence.opencam.PluginProcessing;
import com.almalence.opencam.R;
import com.almalence.plugins.export.standard.GPSTagsConverter;
/* <!-- +++
import com.almalence.opencam_plus.MainScreen;
import com.almalence.opencam_plus.PluginManager;
import com.almalence.opencam_plus.PluginProcessing;
import com.almalence.opencam_plus.R;
+++ --> */
// <!-- -+-
//-+- -->
import com.almalence.plugins.processing.groupshot.GroupShotProcessingPlugin;
import com.almalence.plugins.processing.objectremoval.ObjectRemovalProcessingPlugin;
import com.almalence.plugins.processing.sequence.SequenceProcessingPlugin;
import com.almalence.ui.RotateImageView;
import com.almalence.ui.RotateLayout;
import com.almalence.util.MLocation;

/***
Implements multishot processing
***/

public class MultiShotProcessingPlugin extends PluginProcessing implements OnTaskCompleteListener, Handler.Callback, OnClickListener {
	
	private static int GROUP_SHOT = 0;
	private static int SEQUENCE = 1;
	private static int OBJECT_REMOVAL = 2;
	private static int CANCELLED = -2;
	
	private View mButtonsLayout;
	
	private static GroupShotProcessingPlugin groupShotProcessingPlugin =  new GroupShotProcessingPlugin();
	private static SequenceProcessingPlugin sequenceProcessingPlugin = new SequenceProcessingPlugin();
	private static ObjectRemovalProcessingPlugin objectRemovalProcessingPlugin = new ObjectRemovalProcessingPlugin();
	
	private int selectedPlugin;
	private long sessionID;
	
	private boolean isYUV;
	private boolean mSaveInputPreference;
	private static ArrayList<Integer> mYUVBufferList = new ArrayList<Integer>();
	private static ArrayList<byte[]> mJpegBufferList = new ArrayList<byte []>();
	
	public MultiShotProcessingPlugin() {
		super("com.almalence.plugins.multishotprocessing",
			  R.xml.preferences_processing_multishot,
			  0,
			  0,
			  null);
	}
	
	@Override
	public void onGUICreate() {
		LayoutInflater inflator = MainScreen.getInstance().getLayoutInflater();
		mButtonsLayout = inflator.inflate(R.layout.plugin_processing_multishot_options_layout, null, false);
		
		RotateImageView buttonObjectRemoval = (RotateImageView) mButtonsLayout.findViewById(R.id.buttonObjectRemoval);
		RotateImageView buttonGroupShot = (RotateImageView) mButtonsLayout.findViewById(R.id.buttonGroupShot);
		RotateImageView buttonSequence = (RotateImageView) mButtonsLayout.findViewById(R.id.buttonSequence);
	
		MainScreen.getGUIManager().removeViews(mButtonsLayout, R.id.blockingLayout);
				
		buttonObjectRemoval.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				selectedPlugin = OBJECT_REMOVAL;
				mButtonsLayout.setVisibility(View.GONE);
			}
		});
		
		buttonGroupShot.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				selectedPlugin = GROUP_SHOT;
				mButtonsLayout.setVisibility(View.GONE);
			}
		});
		
		buttonSequence.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				selectedPlugin = SEQUENCE;
				mButtonsLayout.setVisibility(View.GONE);
			}
		});
		
		RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
		params.addRule(RelativeLayout.CENTER_IN_PARENT);
		
		((RelativeLayout)MainScreen.getInstance().findViewById(R.id.blockingLayout)).addView(mButtonsLayout, params);
		
		buttonObjectRemoval.setOrientation(MainScreen.getGUIManager().getLayoutOrientation());
		buttonGroupShot.setOrientation(MainScreen.getGUIManager().getLayoutOrientation());
		buttonSequence.setOrientation(MainScreen.getGUIManager().getLayoutOrientation());
	}
	
	@Override
	public View getPostProcessingView() {
		if (selectedPlugin == GROUP_SHOT) {
			return groupShotProcessingPlugin.getPostProcessingView();
		}
		else if (selectedPlugin == SEQUENCE) {
			return sequenceProcessingPlugin.getPostProcessingView();
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			return objectRemovalProcessingPlugin.getPostProcessingView();
		}
		
		return null;
	}
	
	@Override
	public void onStart() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainScreen.getInstance().getBaseContext());
		mSaveInputPreference = prefs.getBoolean("saveInputPrefMultiShot", false);
		
		groupShotProcessingPlugin.onStart();
		sequenceProcessingPlugin.onStart();
		objectRemovalProcessingPlugin.onStart();
	}
	
	@Override
	public void onStartProcessing(long SessionID)  {
		this.sessionID = SessionID;
		
		selectedPlugin = -1;
		
		MainScreen.getInstance().runOnUiThread(new Runnable() {
		    public void run() {  
		    	mButtonsLayout.setVisibility(View.VISIBLE);
		    	MainScreen.getInstance().findViewById(R.id.blockingText).setVisibility(View.GONE);
		    	Message msg = new Message();
				msg.what = PluginManager.MSG_PROCESSING_BLOCK_UI;
				MainScreen.getMessageHandler().sendMessage(msg);
		    }
		});
		
		prepareDataForProcessing();
		
		while(selectedPlugin == -1) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		
		MainScreen.getInstance().runOnUiThread(new Runnable() {
		    public void run() {  
		    	MainScreen.getInstance().findViewById(R.id.blockingText).setVisibility(View.VISIBLE);
		    }
		});
		        
		if (selectedPlugin == GROUP_SHOT) {
			GroupShotProcessingPlugin.setmJpegBufferList(mJpegBufferList);
			GroupShotProcessingPlugin.setmYUVBufferList(mYUVBufferList);
			groupShotProcessingPlugin.onStartProcessing(sessionID);
		}
		else if (selectedPlugin == SEQUENCE) {
			SequenceProcessingPlugin.setmJpegBufferList(mJpegBufferList);
			SequenceProcessingPlugin.setmYUVBufferList(mYUVBufferList);
			sequenceProcessingPlugin.onStartProcessing(sessionID);
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			ObjectRemovalProcessingPlugin.setmJpegBufferList(mJpegBufferList);
			objectRemovalProcessingPlugin.onStartProcessing(sessionID);
		}
	}
	
	private void prepareDataForProcessing() 
	{
        int imagesAmount = Integer.parseInt(PluginManager.getInstance().getFromSharedMem("amountofcapturedframes"+Long.toString(sessionID)));
		
		if (imagesAmount==0)
			imagesAmount=1;
		
		isYUV = Boolean.parseBoolean(PluginManager.getInstance().getFromSharedMem("isyuv"+Long.toString(sessionID)));

		mYUVBufferList.clear();
		mJpegBufferList.clear();
		
		for (int i=1; i<=imagesAmount; i++) {
			if(isYUV) {
				int yuv = Integer.parseInt(PluginManager.getInstance().getFromSharedMem("frame" + i+Long.toString(sessionID)));
				mYUVBufferList.add(i-1, yuv);
			}
			else {
    			byte[] in = SwapHeap.CopyFromHeap(
    	        		Integer.parseInt(PluginManager.getInstance().getFromSharedMem("frame" + i+Long.toString(sessionID))),
    	        		Integer.parseInt(PluginManager.getInstance().getFromSharedMem("framelen" + i+Long.toString(sessionID)))
    	        		);
    			
    			mJpegBufferList.add(i-1, in);
			}
		}
		
		if (mSaveInputPreference) {
			try {
				File saveDir = PluginManager.getInstance().GetSaveDir(false);
				
				String fileFormat = PluginManager.getInstance().getFileFormat();
				
				for (int i = 0; i<imagesAmount; ++i)
				{
					String index = String.format("_%02d", i);
					File file = new File(saveDir, fileFormat+index+".jpg");
					
					FileOutputStream os = null;
					try
					{
						os = new FileOutputStream(file);
					}
					catch (Exception e)
					{
						//save always if not working saving to sdcard
						e.printStackTrace();
						saveDir = PluginManager.getInstance().GetSaveDir(true);
						file = new File(saveDir, fileFormat+index+".jpg");
						os = new FileOutputStream(file);
					}	   
					
					PluginManager.getInstance().writeData(os, isYUV, sessionID, i, mJpegBufferList.get(i), mYUVBufferList.get(i), file);
				}
			}
			catch(IOException e) {
				e.printStackTrace();
				MainScreen.getMessageHandler().sendEmptyMessage(PluginManager.MSG_EXPORT_FINISHED_IOEXCEPTION);
				return;
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}
	
/************************************************
 * 		POST PROCESSING
 ************************************************/
	@Override
	public boolean isPostProcessingNeeded() {
		if (selectedPlugin == CANCELLED) {
			return false;
		}
		return true;
	}
	
	public void onStartPostProcessing() {	
		if (selectedPlugin == GROUP_SHOT) {
			groupShotProcessingPlugin.onStartPostProcessing();
		}
		else if (selectedPlugin == SEQUENCE) {
			sequenceProcessingPlugin.onStartPostProcessing();
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			objectRemovalProcessingPlugin.onStartPostProcessing();
		}
	}
	
    @Override
	public void onClick(View v) {
    	if (selectedPlugin == GROUP_SHOT) {
			groupShotProcessingPlugin.onClick(v);
		}
		else if (selectedPlugin == SEQUENCE) {
			sequenceProcessingPlugin.onClick(v);
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			objectRemovalProcessingPlugin.onClick(v);
		}
	}
    
    @Override
	public boolean handleMessage(Message msg) {
    	if (selectedPlugin == GROUP_SHOT) {
    		return ((Callback) groupShotProcessingPlugin).handleMessage(msg);
		}
		else if (selectedPlugin == SEQUENCE) {
			return ((Callback) sequenceProcessingPlugin).handleMessage(msg);
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			return ((Callback) objectRemovalProcessingPlugin).handleMessage(msg);
		}

    	return true;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		boolean res = false;
		
		if (selectedPlugin == GROUP_SHOT) {
			res = groupShotProcessingPlugin.onKeyDown(keyCode, event);
		}
		else if (selectedPlugin == SEQUENCE) {
			res = sequenceProcessingPlugin.onKeyDown(keyCode, event);
		}
		else if (selectedPlugin == OBJECT_REMOVAL) {
			res = objectRemovalProcessingPlugin.onKeyDown(keyCode, event);
		}
		
		if (keyCode == KeyEvent.KEYCODE_BACK) {
    		MainScreen.getInstance().findViewById(R.id.blockingText).setVisibility(View.VISIBLE);
    		mButtonsLayout.setVisibility(View.GONE);
    		
			MainScreen.getMessageHandler().sendEmptyMessage(PluginManager.MSG_POSTPROCESSING_FINISHED);
    		selectedPlugin = CANCELLED;
    		MainScreen.getGUIManager().lockControls = false;

    		return true;
	    }
		
		if (res) {
			return res;
		}
		return super.onKeyDown(keyCode, event);
	}
/************************************************
 * 		POST PROCESSING END
 ************************************************/
	
	@Override
	public void onPause() {
		if(mButtonsLayout != null) {
			MainScreen.getGUIManager().removeViews(mButtonsLayout, R.id.specialPluginsLayout3);
		}

		selectedPlugin = CANCELLED;
	}
	
	@Override
	public void onOrientationChanged(int orientation) {
		((RotateLayout) MainScreen.getInstance().findViewById(R.id.rotateLayout)).setAngle(orientation - 90);
		MainScreen.getInstance().findViewById(R.id.rotateLayout).requestLayout();
	}
}
