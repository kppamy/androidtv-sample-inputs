/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sampletvinput;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Programs;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.view.Surface;
import android.view.accessibility.CaptioningManager;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.FrameworkSampleSource;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

abstract public class BaseTvInputService extends TvInputService {
    private static final String TAG = "BaseTvInputService";
    private static final boolean DEBUG = true;

    private final LongSparseArray<ChannelInfo> mChannelMap = new LongSparseArray<ChannelInfo>();
    private HandlerThread mHandlerThread;
    private Handler mDbHandler;
    private Handler mHandler;

    protected List<ChannelInfo> mChannels;
    private List<BaseTvInputSessionImpl> mSessions;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mSessions != null) {
                for (BaseTvInputSessionImpl session : mSessions) {
                    session.checkContentBlockNeeded();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mDbHandler = new Handler(mHandlerThread.getLooper());
        mHandler = new Handler();

        buildChannelMap();
        setTheme(android.R.style.Theme_Holo_Light_NoActionBar);

        mSessions = new ArrayList<BaseTvInputSessionImpl>();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TvInputManager.ACTION_BLOCKED_RATINGS_CHANGED);
        intentFilter.addAction(TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED);
        registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mBroadcastReceiver);
        mHandlerThread.quit();
        mHandlerThread = null;
        mDbHandler = null;
    }

    @Override
    public final Session onCreateSession(String inputId) {
        BaseTvInputSessionImpl session = onCreateSessionInternal(inputId);
        mSessions.add(session);
        return session;
    }

    /**
     * Child classes should extend this to change the result of onCreateSession.
     */
    public BaseTvInputSessionImpl onCreateSessionInternal(String inputId) {
        return new BaseTvInputSessionImpl(this);
    }

    abstract public List<ChannelInfo> createSampleChannels();

    private synchronized void buildChannelMap() {
        Uri uri = TvContract.buildChannelsUriForInput(Utils.getInputIdFromComponentName(this,
                new ComponentName(this, this.getClass())));
        String[] projection = {
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER
        };
        mChannels = createSampleChannels();
        if (mChannels == null || mChannels.isEmpty()) {
            return;
        }

        try {
            Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return;
            }

            while (cursor.moveToNext()) {
                long channelId = cursor.getLong(0);
                String channelNumber = cursor.getString(1);
                mChannelMap.put(channelId, getChannelByNumber(channelNumber, false));
            }
        } catch (Exception e) {
            Log.d(TAG, "Content provider query: " + e.getStackTrace());
        }
    }

    private ChannelInfo getChannelByNumber(String channelNumber, boolean isRetry) {
        for (ChannelInfo info : mChannels) {
            if (info.mNumber.equals(channelNumber)) {
                return info;
            }
        }
        if (!isRetry) {
            buildChannelMap();
            return getChannelByNumber(channelNumber, true);
        }
        throw new IllegalArgumentException("Unknown channel: " + channelNumber);
    }

    private ChannelInfo getChannelByUri(Uri channelUri, boolean isRetry) {
        ChannelInfo info = mChannelMap.get(ContentUris.parseId(channelUri));
        if (info == null) {
            if (!isRetry) {
                buildChannelMap();
                return getChannelByUri(channelUri, true);
            }
            throw new IllegalArgumentException("Unknown channel: " + channelUri);
        }
        return info;
    }

    class BaseTvInputSessionImpl extends TvInputService.Session {
        private TvInputManager mTvInputManager;
        protected ExoPlayer mPlayer;
        private Surface mSurface;
        private float mVolume;
        private boolean mCaptionEnabled;
        private TvContentRating mLastBlockedRating;
        private ChannelInfo mChannelInfo;
        private ProgramInfo mCurrentProgramInfo;
        private TvContentRating mCurrentContentRating;
        private final Set<TvContentRating> mUnblockedRatingSet = new HashSet<>();
        private MediaCodecVideoTrackRenderer mVideoRenderer;
        private MediaCodecAudioTrackRenderer mAudioRenderer;

        private final ExoPlayer.Listener mPlayerListener = new ExoPlayer.Listener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playWhenReady == true && playbackState == ExoPlayer.STATE_BUFFERING) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
                } else if (playWhenReady == true && playbackState == ExoPlayer.STATE_READY) {
                    notifyVideoAvailable();
                }
            }

            @Override
            public void onPlayWhenReadyCommitted() {
                // Do nothing.
            }

            @Override
            public void onPlayerError(ExoPlaybackException e) {
                // Do nothing.
            }
        };

        private final Runnable mPlayCurrentProgramRunnable = new Runnable() {
            @Override
            public void run() {
                playCurrentProgram();
            }
        };

        protected BaseTvInputSessionImpl(Context context) {
            super(context);

            mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            mLastBlockedRating = null;

            CaptioningManager captionManager = (CaptioningManager) getSystemService(
                    CAPTIONING_SERVICE);
            mCaptionEnabled = captionManager.isEnabled();
        }

        @Override
        public void onRelease() {
            releasePlayer();
            mSessions.remove(this);
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            if (mPlayer != null) {
                mPlayer.sendMessage(mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                        surface);
            }
            mSurface = surface;
            return true;
        }

        @Override
        public void onSetStreamVolume(float volume) {
            if (mPlayer != null) {
                mPlayer.sendMessage(mAudioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                        volume);
            }
            mVolume = volume;
        }

        private boolean setDataSource(ExoPlayer player, ProgramInfo program) {
            FrameworkSampleSource sampleSource = new FrameworkSampleSource(BaseTvInputService.this,
                    Uri.parse(program.mUrl), null, 2);
            mVideoRenderer = new MediaCodecVideoTrackRenderer(
                    sampleSource, MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 0, mHandler, null,
                    50);
            mAudioRenderer = new MediaCodecAudioTrackRenderer(
                    sampleSource);
            player.prepare(mVideoRenderer, mAudioRenderer);
            return true;
        }

        private Pair<ProgramInfo, Long> getCurrentProgramStatus() {
            long durationSumSec = 0;
            for (ProgramInfo program : mChannelInfo.mPrograms) {
                durationSumSec += program.mDurationSec;
            }
            long nowSec = System.currentTimeMillis() / 1000;
            long startTimeSec = nowSec - nowSec % durationSumSec;
            for (ProgramInfo program : mChannelInfo.mPrograms) {
                if (nowSec < startTimeSec + program.mDurationSec) {
                    return new Pair(program, startTimeSec + program.mDurationSec - nowSec);
                }
                startTimeSec += program.mDurationSec;
            }
            ProgramInfo first = mChannelInfo.mPrograms.get(0);
            return new Pair(first, first.mDurationSec);
        }

        private boolean changeChannel(Uri channelUri) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);

            mUnblockedRatingSet.clear();
            mChannelInfo = getChannelByUri(channelUri, false);
            if (!playCurrentProgram()) {
                return false;
            }
            mDbHandler.post(new AddProgramRunnable(channelUri, mChannelInfo));
            return true;
        }

        private boolean playCurrentProgram() {
            if (mPlayer != null) {
                releasePlayer();
            }

            Pair<ProgramInfo, Long> status = getCurrentProgramStatus();
            mCurrentProgramInfo = status.first;
            long remainingTimeSec = status.second;
            mCurrentContentRating = mCurrentProgramInfo.mContentRatings.length > 0 ?
                    mCurrentProgramInfo.mContentRatings[0] : null;

            mPlayer = ExoPlayer.Factory.newInstance(2, 1000, 5000);
            mPlayer.addListener(mPlayerListener);
            if (!setDataSource(mPlayer, mCurrentProgramInfo)) {
                return false;
            }
            mPlayer.sendMessage(mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                    mSurface);
            mPlayer.sendMessage(mAudioRenderer, MediaCodecAudioTrackRenderer.MSG_SET_VOLUME,
                    mVolume);

            int seekPosSec = (int) (mCurrentProgramInfo.mDurationSec - remainingTimeSec);
            mPlayer.seekTo(seekPosSec * 1000);
            mPlayer.setPlayWhenReady(true);

            checkContentBlockNeeded();
            mHandler.removeCallbacks(mPlayCurrentProgramRunnable);
            mHandler.postDelayed(mPlayCurrentProgramRunnable, (remainingTimeSec + 1) * 1000);
            // TODO: Report the available tracks to the application.
            return true;
        }

        @Override
        public boolean onTune(Uri channelUri) {
            return changeChannel(channelUri);
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // TODO: Implement this.
        }

        @Override
        public boolean onSelectTrack(int type, String trackId) {
            // TODO: Implement this.
            return false;
        }

        @Override
        public void onUnblockContent(TvContentRating rating) {
            if (rating != null) {
                unblockContent(rating);
            }
        }

        private void releasePlayer() {
            mPlayer.removeListener(mPlayerListener);
            mPlayer.sendMessage(mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, null);
            mPlayer.release();
            mPlayer = null;
        }

        private void checkContentBlockNeeded() {
            if (mCurrentContentRating == null || !mTvInputManager.isParentalControlsEnabled()
                    || !mTvInputManager.isRatingBlocked(mCurrentContentRating)
                    || mUnblockedRatingSet.contains(mCurrentContentRating)) {
                // Content rating is changed so we don't need to block anymore.
                // Unblock content here explicitly to resume playback.
                unblockContent(null);
                return;
            }

            mLastBlockedRating = mCurrentContentRating;
            if (mPlayer != null) {
                // Children restricted content might be blocked by TV app as well,
                // but TIS should do its best not to show any single frame of blocked content.
                releasePlayer();
            }

            notifyContentBlocked(mCurrentContentRating);
        }

        private void unblockContent(TvContentRating rating) {
            // TIS should unblock content only if unblock request is legitimate.
            if (rating == null || mLastBlockedRating == null
                    || (mLastBlockedRating != null && rating.equals(mLastBlockedRating))) {
                mLastBlockedRating = null;
                if (rating != null) {
                    mUnblockedRatingSet.add(rating);
                }
                if (mPlayer == null) {
                    playCurrentProgram();
                }
                notifyContentAllowed();
            }
        }

        private class AddProgramRunnable implements Runnable {
            private static final int PROGRAM_REPEAT_COUNT = 24;
            private final Uri mChannelUri;
            private final ChannelInfo mChannelInfo;

            public AddProgramRunnable(Uri channelUri, ChannelInfo channel) {
                mChannelUri = channelUri;
                mChannelInfo = channel;
            }

            @Override
            public void run() {
                long durationSumSec = 0;
                List<ContentValues> programs = new ArrayList<>();
                for (ProgramInfo program : mChannelInfo.mPrograms) {
                    durationSumSec += program.mDurationSec;

                    ContentValues values = new ContentValues();
                    values.put(Programs.COLUMN_CHANNEL_ID, ContentUris.parseId(mChannelUri));
                    values.put(Programs.COLUMN_TITLE, program.mTitle);
                    values.put(Programs.COLUMN_SHORT_DESCRIPTION, program.mDescription);
                    values.put(Programs.COLUMN_CONTENT_RATING,
                            Utils.contentRatingsToString(program.mContentRatings));
                    if (!TextUtils.isEmpty(program.mPosterArtUri)) {
                        values.put(Programs.COLUMN_POSTER_ART_URI, program.mPosterArtUri);
                    }
                    programs.add(values);
                }

                long nowSec = System.currentTimeMillis() / 1000;
                long epgStartTimeSec = nowSec - nowSec % durationSumSec;
                for (int i = 0; i < PROGRAM_REPEAT_COUNT; ++i) {
                    long startSec = epgStartTimeSec + i * durationSumSec;
                    if (!hasProgramInfo(startSec * 1000 + 1, (startSec + durationSumSec) * 1000 )) {
                        long programStartSec = startSec;
                        for (int j = 0; j < mChannelInfo.mPrograms.size(); ++j) {
                            ProgramInfo program = mChannelInfo.mPrograms.get(j);
                            ContentValues values = programs.get(j);
                            values.put(Programs.COLUMN_START_TIME_UTC_MILLIS,
                                    programStartSec * 1000);
                            values.put(Programs.COLUMN_END_TIME_UTC_MILLIS,
                                    (programStartSec + program.mDurationSec) * 1000);
                            getContentResolver().insert(TvContract.Programs.CONTENT_URI, values);
                            programStartSec = programStartSec + program.mDurationSec;
                        }
                    }
                }
            }

            private boolean hasProgramInfo(long startTimeMs, long endTimeMs) {
                Uri uri = TvContract.buildProgramsUriForChannel(mChannelUri, startTimeMs,
                        endTimeMs);
                String[] projection = {TvContract.Programs._ID};
                try {
                    Cursor cursor =
                            getContentResolver().query(uri, projection, null, null, null);
                    if (cursor.getCount() > 0) {
                        return true;
                    }
                } catch (Exception e) {

                }
                return false;
            }
        }
    }

    public static final class ChannelInfo {
        public final String mNumber;
        public final String mName;
        public final String mLogoUrl;
        public final int mOriginalNetworkId;
        public final int mTransportStreamId;
        public final int mServiceId;
        public final int mVideoWidth;
        public final int mVideoHeight;
        public final int mAudioChannel;
        public final boolean mHasClosedCaption;
        public final List<ProgramInfo> mPrograms;

        public ChannelInfo(String number, String name, String logoUrl, int originalNetworkId,
                           int transportStreamId, int serviceId, int videoWidth, int videoHeight,
                           int audioChannel, boolean hasClosedCaption, List<ProgramInfo> programs) {
            mNumber = number;
            mName = name;
            mLogoUrl = logoUrl;
            mOriginalNetworkId = originalNetworkId;
            mTransportStreamId = transportStreamId;
            mServiceId = serviceId;
            mVideoWidth = videoWidth;
            mVideoHeight = videoHeight;
            mAudioChannel = audioChannel;
            mHasClosedCaption = hasClosedCaption;
            mPrograms = programs;
        }
    }

    public static final class ProgramInfo {
        public final String mTitle;
        public final String mPosterArtUri;
        public final String mDescription;
        public final long mDurationSec;
        public final String mUrl;
        public final int mResourceId;
        public final TvContentRating[] mContentRatings;

        public ProgramInfo(String title, String posterArtUri, String description, long durationSec,
                TvContentRating[] contentRatings, String url, int resourceId) {
            mTitle = title;
            mPosterArtUri = posterArtUri;
            mDescription = description;
            mDurationSec = durationSec;
            mContentRatings = contentRatings;
            mUrl = url;
            mResourceId = resourceId;
        }
    }

    public static final class TvInput {
        public final String mDisplayName;
        public final String mName;
        public final String mDescription;
        public final String mLogoThumbUrl;
        public final String mLogoBackgroundUrl;

        public TvInput(String displayName,
                       String name,
                       String description,
                       String logoThumbUrl,
                       String logoBackgroundUrl) {
            mDisplayName = displayName;
            mName = name;
            mDescription = description;
            mLogoThumbUrl = logoThumbUrl;
            mLogoBackgroundUrl = logoBackgroundUrl;
        }

        public String getLogoBackgroundUrl() {
            return mLogoBackgroundUrl;
        }

        public String getDisplayName() {
            return mDisplayName;
        }

        public String getName() {
            return mName;
        }

        public String getDescription() {
            return mDescription;
        }
    }


}
