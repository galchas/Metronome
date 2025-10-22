/*
 * This file is part of Metronome.
 * Copyright (C) 2025 Philipp Bobek <philipp.bobek@mailbox.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Metronome is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Reusable MetronomeView that encapsulates the UI and interaction logic
 * to be used independently inside other apps.
 */
package com.bobek.metronome.view

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bobek.metronome.MetronomeService
import com.bobek.metronome.data.Tempo
import com.bobek.metronome.data.Tick
import com.bobek.metronome.databinding.ViewMetronomeBinding
import com.bobek.metronome.view.component.TickVisualization
import com.bobek.metronome.view.model.MetronomeViewModel

class MetronomeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewMetronomeBinding
    private val viewModel = MetronomeViewModel()

    // Service binding and tick receiver
    private var metronomeService: MetronomeService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MetronomeService.LocalBinder
            metronomeService = binder.getService()
            viewModel.connected.value = true
            synchronizeWithService()
            // When service is connected, we rely on service ticks via broadcast
            stopInternalScheduler()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            metronomeService = null
            viewModel.connected.value = false
            // Fallback to internal scheduler for visual ticks without sound
            if (viewModel.playing.value == true) startInternalScheduler()
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MetronomeService.ACTION_TICK) {
                val tick: Tick? = intent.getParcelableExtra(MetronomeService.EXTRA_TICK)
                tick?.let { blinkForBeat(it.beat) }
            }
        }
    }

    // Simple internal tick scheduler as fallback when service not connected
    private val handler = Handler(Looper.getMainLooper())
    private var currentBeat = 1
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (metronomeService == null && viewModel.playing.value == true) {
                blinkForBeat(currentBeat)
                advanceBeat()
                val interval = calculateBeatIntervalMs()
                handler.postDelayed(this, interval)
            }
        }
    }

    init {
        val inflater = LayoutInflater.from(context)
        binding = ViewMetronomeBinding.inflate(inflater, this, true)
        binding.metronome = viewModel
        (context as? LifecycleOwner)?.let { binding.lifecycleOwner = it }

        // Wire up UI actions (replicating MetronomeFragment logic)
        binding.content.incrementTempoButton.setOnClickListener { incrementTempo() }
        binding.content.incrementTempoButton.setOnLongClickListener {
            repeat(LARGE_TEMPO_CHANGE_SIZE) { incrementTempo() }
            true
        }

        binding.content.decrementTempoButton.setOnClickListener { decrementTempo() }
        binding.content.decrementTempoButton.setOnLongClickListener {
            repeat(LARGE_TEMPO_CHANGE_SIZE) { decrementTempo() }
            true
        }

        binding.content.tapTempoButton.setOnClickListener { tapTempo() }
        binding.content.startStopButton.setOnClickListener {
            viewModel.startStop()
            // Start/stop sound via service if available, otherwise drive internal scheduler
            metronomeService?.let { it.playing = viewModel.playing.value == true }
            if (metronomeService == null) {
                if (viewModel.playing.value == true) startInternalScheduler() else stopInternalScheduler()
            }
        }

        // Mirror view model changes into service when connected
        viewModel.beatsData.observeForever { metronomeService?.beats = it }
        viewModel.subdivisionsData.observeForever { metronomeService?.subdivisions = it }
        viewModel.gapsData.observeForever { metronomeService?.gaps = it }
        viewModel.tempoData.observeForever { metronomeService?.tempo = it }
        viewModel.emphasizeFirstBeat.observeForever { metronomeService?.emphasizeFirstBeat = it }
        viewModel.sound.observeForever { metronomeService?.sound = it }
        viewModel.playing.observeForever { metronomeService?.playing = it }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ensure lifecycle owner is present when attached in host app
        if (binding.lifecycleOwner == null) {
            (context as? LifecycleOwner)?.let { binding.lifecycleOwner = it }
        }
        // Register tick receiver and bind to the service
        ContextCompat.registerReceiver(
            context,
            tickReceiver,
            IntentFilter(MetronomeService.ACTION_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startAndBindService()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean-up
        try { context.unregisterReceiver(tickReceiver) } catch (_: Exception) { }
        unbindFromService()
        stopInternalScheduler()
    }

    // Public API to integrate with host app if needed
    fun setTempo(bpm: Int) { viewModel.tempoData.value = Tempo(bpm) }
    fun getTempo(): Int = viewModel.tempoData.value?.value ?: Tempo.DEFAULT

    fun start() { if (viewModel.playing.value != true) binding.content.startStopButton.performClick() }
    fun stop() { if (viewModel.playing.value == true) binding.content.startStopButton.performClick() }

    private fun incrementTempo() {
        viewModel.tempoData.value?.value?.let {
            if (it < Tempo.MAX) viewModel.tempoData.value = Tempo(it + 1)
        }
    }

    private fun decrementTempo() {
        viewModel.tempoData.value?.value?.let {
            if (it > Tempo.MIN) viewModel.tempoData.value = Tempo(it - 1)
        }
    }

    // Tap tempo logic copied from MetronomeFragment (simplified)
    private var taps = ArrayDeque<Long>()
    private fun tapTempo() {
        val now = System.currentTimeMillis()
        pruneOldTaps(now)
        taps.add(now)
        averageTapIntervalInMillis()?.let { setTempoWithinBounds(bpmFromMillis(it)) }
    }

    private fun pruneOldTaps(now: Long) {
        taps.removeAll { now - it > TAP_WINDOW_MILLIS }
    }

    private fun averageTapIntervalInMillis(): Int? = taps
        .zipWithNext { a, b -> b - a }
        .average()
        .toInt()
        .takeIf { it > 0 }

    private fun bpmFromMillis(millis: Int): Int = (MILLIS_PER_MINUTE / millis).toInt()

    private fun setTempoWithinBounds(tempoValue: Int) {
        viewModel.tempoData.value = when {
            tempoValue > Tempo.MAX -> Tempo(Tempo.MAX)
            tempoValue < Tempo.MIN -> Tempo(Tempo.MIN)
            else -> Tempo(tempoValue)
        }
    }

    private fun startAndBindService() {
        // Start the service to enable audio playback
        val intent = Intent(context, MetronomeService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindFromService() {
        try { context.unbindService(serviceConnection) } catch (_: Exception) { }
    }

    private fun synchronizeWithService() {
        metronomeService?.let { service ->
            // Push current UI state into the service when connected
            viewModel.beatsData.value?.let { service.beats = it }
            viewModel.subdivisionsData.value?.let { service.subdivisions = it }
            viewModel.gapsData.value?.let { service.gaps = it }
            viewModel.tempoData.value?.let { service.tempo = it }
            viewModel.emphasizeFirstBeat.value?.let { service.emphasizeFirstBeat = it }
            viewModel.sound.value?.let { service.sound = it }
            service.playing = viewModel.playing.value == true
        }
    }

    private fun startInternalScheduler() {
        stopInternalScheduler()
        currentBeat = 1
        handler.post(tickRunnable)
    }

    private fun stopInternalScheduler() {
        handler.removeCallbacks(tickRunnable)
    }

    private fun calculateBeatIntervalMs(): Long {
        val bpm = viewModel.tempoData.value?.value ?: Tempo.DEFAULT
        return (MILLIS_PER_MINUTE / bpm).toLong()
    }

    private fun advanceBeat() {
        val beats = (viewModel.beatsData.value?.value ?: 4).coerceIn(1, 8)
        currentBeat = if (currentBeat >= beats) 1 else currentBeat + 1
    }

    private fun blinkForBeat(beat: Int) {
        getTickVisualization(beat)?.blink()
    }

    private fun getTickVisualization(beat: Int): TickVisualization? = when (beat) {
        1 -> binding.content.tickVisualization1
        2 -> binding.content.tickVisualization2
        3 -> binding.content.tickVisualization3
        4 -> binding.content.tickVisualization4
        5 -> binding.content.tickVisualization5
        6 -> binding.content.tickVisualization6
        7 -> binding.content.tickVisualization7
        8 -> binding.content.tickVisualization8
        else -> null
    }

    companion object {
        private const val LARGE_TEMPO_CHANGE_SIZE = 10
        private const val TAP_WINDOW_MILLIS = 5_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
