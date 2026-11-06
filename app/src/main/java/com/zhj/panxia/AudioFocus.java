package com.zhj.panxia;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * 音频焦点。
 * <p>
 * 手机上和电视不一样：来电话、其他应用放音乐、插拔耳机都会抢音频。
 * 不申请焦点的话，自己的声音会和别人叠在一起，来电时也照放。
 * 这里统一申请一次焦点，被抢走就暂停当前播放，交给系统决定谁来说话。
 */
final class AudioFocus {

    interface OnLoss {
        /** 焦点被别的应用或来电拿走，应该暂停播放 */
        void onFocusLoss();
    }

    private final AudioManager am;
    private AudioFocusRequest request;
    private AudioManager.OnAudioFocusChangeListener legacy;
    private boolean held;

    AudioFocus(Context c) {
        am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    boolean request(final OnLoss loss) {
        if (am == null) {
            return false;
        }
        abandon();
        AudioManager.OnAudioFocusChangeListener l = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int change) {
                if (change == AudioManager.AUDIOFOCUS_LOSS
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    held = false;
                    if (loss != null) {
                        loss.onFocusLoss();
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(l)
                        .build();
                held = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                legacy = l;
                held = am.requestAudioFocus(l, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Exception ignored) {
            held = false;
        }
        return held;
    }

    boolean held() {
        return held;
    }

    void abandon() {
        if (am == null || !held) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && request != null) {
                am.abandonAudioFocusRequest(request);
            } else if (legacy != null) {
                am.abandonAudioFocus(legacy);
            }
        } catch (Exception ignored) {
        }
        held = false;
    }
}
