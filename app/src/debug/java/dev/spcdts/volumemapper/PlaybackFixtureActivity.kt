package dev.spcdts.volumemapper

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.TextView

/** 仅 Debug：循环静音 PCM，让宿主 E2E 在真实媒体播放、后台及熄屏状态下注入侧键。 */
class PlaybackFixtureActivity : Activity() {
    private var track: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { setText(R.string.app_name) })
        val samples = ShortArray(48_000)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build().also {
                check(it.write(samples, 0, samples.size) == samples.size)
                check(it.setLoopPoints(0, samples.size, -1) == AudioTrack.SUCCESS)
                it.play()
            }
    }

    override fun onDestroy() {
        track?.release()
        track = null
        super.onDestroy()
    }
}
