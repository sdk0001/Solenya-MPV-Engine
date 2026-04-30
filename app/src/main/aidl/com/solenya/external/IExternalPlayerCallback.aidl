package com.solenya.external;

interface IExternalPlayerCallback {
    void onReady();
    void onBuffering(boolean buffering);
    void onPlayingChanged(boolean playing);
    void onPositionChanged(long positionMs, long durationMs);
    void onError(String code, String message);
    void onVideoFormatChanged(int width, int height, String label, String hdrType, String audioChannels, int fps);
}
