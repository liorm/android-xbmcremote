package org.xbmc.android.remote.business;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.xbmc.android.util.ClientFactory;
import org.xbmc.api.business.DataResponse;
import org.xbmc.api.business.IEventClientManager;
import org.xbmc.api.business.INotifiableManager;
import org.xbmc.api.data.IControlClient;
import org.xbmc.api.presentation.INotifiableController;
import org.xbmc.eventclient.ButtonCodes;
import org.xbmc.httpapi.WifiStateException;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class ServerVolumeManager  
{
	private static final String TAG = ServerVolumeManager.class.getName();
	
	public static final String BUNDLE_LAST_VOLUME = "LastVolume";
	
	public static final int MESSAGE_VOLUME_CHANGED = 700;

	
	/**
	 * Since this one is kinda of its own, we use a stub as manager.
	 * @TODO create some toasts or at least logs instead of empty on* methods.
	 */
	private static final INotifiableManager mManagerStub = 
		new INotifiableManager() {
			public void onMessage(int code, String message) { }
			public void onMessage(String message) { }
			public void onError(Exception e) {
				// XXX link to context will eventually change if activity which created the thread changes (java.lang.RuntimeException: Can't create handler inside thread that has not called Looper.prepare())
				//Toast toast = Toast.makeText(context, "Poller Error: " + e.getMessage(), Toast.LENGTH_LONG);
				//toast.show();
				if (e.getMessage() != null) {
					Log.e(TAG, e.getMessage());
				}
				e.printStackTrace();
			}
			public void onFinish(DataResponse<?> response) {
			}
			public void onWrongConnectionState(int state, Command<?> cmd) {
			}
			public void retryAll() {
			}
		};
	
	static ServerVolumeManager INSTANCE;
	public static ServerVolumeManager getInstance()
	{
		if ( INSTANCE == null )
			INSTANCE = new ServerVolumeManager();
		
		return INSTANCE;
	}
	
	private final HashSet<Handler> mSubscribers = new HashSet<Handler>();
	private int mLastVolume = 0;
	
	private PollerThread mPollerThread;
	
	public synchronized void subscribe(Context context, Handler handler) 
	{
		// update handler on the state of affairs
		notifySingleSubscriber(handler, mLastVolume);
		
		if ( mSubscribers.size() == 0 )
		{
			mPollerThread = new PollerThread(context);
			mPollerThread.start();
		}
		mSubscribers.add(handler);
	}
	
	public synchronized void unSubscribe(Handler handler)
	{
		mSubscribers.remove(handler);
		
		if ( mSubscribers.size() == 0 )
		{
			mPollerThread.interrupt();
			try
			{
				mPollerThread.join();
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
			mPollerThread = null;
		}
	}
	
	public void incVolume()
	{
		IEventClientManager client = ManagerFactory.getEventClientManager(null);
		client.sendButton("R1", ButtonCodes.REMOTE_VOLUME_PLUS, false, true, true, (short) 0, (byte) 0);
		
		forceRefresh(); 
	}
	
	public void decVolume()
	{
		IEventClientManager client = ManagerFactory.getEventClientManager(null);
		client.sendButton("R1", ButtonCodes.REMOTE_VOLUME_MINUS, false, true, true, (short) 0, (byte) 0);
		
		forceRefresh();
	}
	
	private synchronized void forceRefresh()
	{
		if ( mPollerThread != null )
		{
			mPollerThread.forceRefresh();
		}
	}

	private synchronized void notifySubscribers(int newVolume)
	{
		mLastVolume = newVolume;
		
		for ( Handler handler : mSubscribers )
			notifySingleSubscriber(handler, mLastVolume);
	}
	
	private static void notifySingleSubscriber(Handler handler, int volume)
	{
		Message msg = Message.obtain(handler, MESSAGE_VOLUME_CHANGED);
		Bundle bundle = msg.getData();
		bundle.putInt(BUNDLE_LAST_VOLUME, volume);
		
		msg.sendToTarget();
	}
	
	private class PollerThread extends Thread
	{
		public PollerThread(Context context)
		{
			mContext = context;
		}

		private final Context mContext;
		
		public synchronized void forceRefresh()
		{
			mForceRefresh = true;
			notify();
		}
		
		private boolean mForceRefresh;
		
		@Override
		public void run()
		{
			IControlClient control = null;
			int currentVolume = mLastVolume;
			while ( !isInterrupted() ) 
			{
				synchronized (this)
				{
					try
					{
						if ( !mForceRefresh )
							wait(1000);
						mForceRefresh = false;
					}
					catch (InterruptedException e)
					{
						return;
					}
				}
				
				// Try to obtain a client control instance.
				if ( control == null )
				{
					try 
					{
						control = ClientFactory.getControlClient(mManagerStub, mContext);
					} 
					catch (WifiStateException e2) 
					{
						control = null;
					}
				}
				
				if ( control != null )
				{
					currentVolume = control.getVolume(mManagerStub);
					if ( currentVolume != mLastVolume )
						notifySubscribers(currentVolume);
				}
			}
		}
	}
}
