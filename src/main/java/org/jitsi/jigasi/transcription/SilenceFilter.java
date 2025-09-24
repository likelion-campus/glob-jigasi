/*
 * Jigasi, the JItsi GAteway to SIP.
 *
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.jigasi.transcription;

// Removed WebRTC VAD imports - using simple audio level detection instead

/**
 * This class provides simple Voice Activity Detection (VAD) without storing audio segments.
 * Uses basic audio level analysis to determine speech vs silence.
 * Memory-optimized version that doesn't accumulate audio data.
 *
 * @author Nik Vaessen
 */
public class SilenceFilter
{
    // Removed WebRTC VAD constants - using simple audio level detection instead

    /**
     * Simple VAD state tracking without storing audio segments
     */
    private boolean previousSegmentWasSpeech = false;
    private boolean isCurrentlySpeech = false;
    
    /**
     * Simple audio level threshold for VAD
     */
    private static final double AUDIO_LEVEL_THRESHOLD = 0.001;

    /**
     * Give a new segment of audio - VAD only, no storage
     *
     * @param audio the audio
     */
    public void giveSegment(byte[] audio)
    {
        // Simple audio level based VAD without storing segments
        double audioLevel = calculateAudioLevel(audio);
        previousSegmentWasSpeech = isCurrentlySpeech;
        isCurrentlySpeech = audioLevel > AUDIO_LEVEL_THRESHOLD;
    }
    
    /**
     * Calculate audio level for simple VAD
     */
    private double calculateAudioLevel(byte[] audio)
    {
        if (audio == null || audio.length == 0) return 0.0;
        
        long sum = 0;
        for (byte b : audio) {
            sum += b * b;
        }
        return Math.sqrt((double) sum / audio.length);
    }

    /**
     * Get the whole window size in a single array.
     * Since we don't store segments, return empty array.
     * The Participant will use the original audio buffer instead.
     *
     * @return empty array (no stored segments)
     */
    public byte[] getSpeechWindow()
    {
        // No segments stored - return empty array
        // Participant will use original audio buffer
        return new byte[0];
    }

    /**
     * Whether the current window is considered to be speech.
     * @return true if should filter (silence), false if speech
     */
    public boolean shouldFilter()
    {
        return !isCurrentlySpeech;
    }

    /**
     * Whether the last given segment indicated that the audio transistioned
     * from silence to speech, which means the whole window is now
     * considered speech.
     *
     * @return true when a transition from silence to speech took place.
     */
    public boolean newSpeech()
    {
        return !previousSegmentWasSpeech && isCurrentlySpeech;
    }

}
