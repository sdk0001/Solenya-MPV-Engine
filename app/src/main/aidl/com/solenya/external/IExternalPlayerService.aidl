package com.solenya.external;

import android.os.Bundle;
import android.view.Surface;
import com.solenya.external.IExternalPlayerCallback;

interface IExternalPlayerService {
    int getApiVersion();
    String getEngineVersion();
    void setCallback(IExternalPlayerCallback callback);
    void attachSurface(in Surface surface);
    void clearSurface();
    void play(String url, in Bundle options, long startPositionMs);
    void retry();
    void pause();
    void resume();
    void togglePlayPause();
    void stop();
    void release();
    void seekTo(long positionMs);
    void seekRelative(int seconds);
    void selectTrack(int trackType, int trackId);
    void disableSubtitles();
}
